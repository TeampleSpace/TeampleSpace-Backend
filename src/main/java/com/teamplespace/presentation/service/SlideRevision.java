package com.teamplespace.presentation.service;

import static com.teamplespace.composition.service.OutlineInput.invalid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.composition.service.OutlineInput;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.presentation.infra.OpenAiRevisionClient;
import com.teamplespace.processing.service.JobFailure;
import com.teamplespace.processing.service.JobHandler;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 문장으로 받은 요청으로 슬라이드 하나의 글 수정안을 만들고, 요청자가 확인하면 기존 요소 수정 경로로 적용한다
// 수정안은 작업 결과에 두고 적용 전에는 공유 문서를 바꾸지 않는다
@Service
public class SlideRevision implements JobHandler {

	public record Result(int status, JsonNode body) {
	}

	record Source(UUID elementId, long version, String text) {
	}

	record Input(int schemaVersion, UUID slideId, long generation, String prompt, String model, String promptVersion,
			List<Source> elements) {
	}

	record Target(UUID elementId, long baseVersion, String before, String after) {
	}

	static final String TYPE = "slide_revision";
	private static final int SCHEMA_VERSION = 1;
	private static final String PROMPT_VERSION = "slide-revision-v1";
	private static final int PROMPT_MAX = 500;
	private static final int TEXT_MAX = 2_000;

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final TransactionTemplate tx;
	private final ProjectAccess access;
	private final JobQueue jobs;
	private final IdempotencyStore idempotency;
	private final ElementPatchService patches;
	private final OpenAiRevisionClient provider;

	public SlideRevision(JdbcClient jdbc, ObjectMapper json, TransactionTemplate tx, ProjectAccess access,
			JobQueue jobs, IdempotencyStore idempotency, ElementPatchService patches,
			OpenAiRevisionClient provider) {
		this.jdbc = jdbc;
		this.json = json;
		this.tx = tx;
		this.access = access;
		this.jobs = jobs;
		this.idempotency = idempotency;
		this.patches = patches;
		this.provider = provider;
	}

	public Result create(Actor actor, UUID slideId, String keyValue, JsonNode body) {
		return tx.execute(status -> {
			UUID projectId = jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM slides WHERE id = UUID_TO_BIN(?) AND deleted_at IS NULL")
				.param(slideId.toString()).query(String.class).optional().map(UUID::fromString)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			access.lock(projectId);
			access.requireMember(projectId, actor.userId());
			var key = key(actor, keyValue, "/v1/slides/" + slideId + "/ai-revision-jobs");
			var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
			if (stored.isPresent()) {
				return new Result(stored.get().status(), json.readTree(stored.get().body()));
			}
			if (!provider.available()) {
				throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
			}
			OutlineInput.checkFields(body, Set.of("generation", "prompt"), "revisionJob");
			long generation = OutlineInput.version(body.path("generation"), "generation");
			String prompt = OutlineInput.text(body.path("prompt"), "prompt", PROMPT_MAX).strip();
			if (prompt.isEmpty()) {
				throw invalid("prompt", "어떻게 바꿀지 적어 주세요.");
			}
			long current = jdbc.sql("SELECT generation FROM slides WHERE id = UUID_TO_BIN(?)")
				.param(slideId.toString()).query(Long.class).single();
			if (current != generation) {
				throw new ApiException(ErrorCode.VERSION_CONFLICT);
			}
			List<Source> elements = jdbc.sql("""
					SELECT BIN_TO_UUID(id) AS id, version, JSON_UNQUOTE(JSON_EXTRACT(content, '$.text')) AS text
					FROM slide_elements
					WHERE slide_id = UUID_TO_BIN(?) AND type = 'text' AND deleted_at IS NULL
					ORDER BY z_order, id
					""").param(slideId.toString())
				.query((rs, row) -> new Source(UUID.fromString(rs.getString("id")), rs.getLong("version"),
						rs.getString("text")))
				.list();
			if (elements.isEmpty()) {
				throw invalid("slideId", "고칠 글이 있는 슬라이드에서 요청해 주세요.");
			}
			var input = new Input(SCHEMA_VERSION, slideId, generation, prompt, provider.model(), PROMPT_VERSION, elements);
			ObjectNode snapshot = json.createObjectNode();
			snapshot.set("input", json.valueToTree(input));
			UUID jobId = jobs.register(projectId, actor.userId(), TYPE, "slide", slideId, snapshot);
			JsonNode job = json.valueToTree(jobs.get(actor.userId(), jobId));
			idempotency.complete(key, true, 202, json.writeValueAsString(job), jobId);
			return new Result(202, job);
		});
	}

	// 모든 대상이 기준 버전 그대로일 때만 한 트랜잭션으로 반영한다. 하나라도 어긋나면 아무것도 바꾸지 않는다
	public Result apply(Actor actor, UUID jobId, String keyValue) {
		return tx.execute(status -> {
			var job = jdbc.sql("""
					SELECT BIN_TO_UUID(project_id) AS project, BIN_TO_UUID(requester_id) AS requester, status,
					       CAST(result_ref AS CHAR) AS result
					FROM jobs WHERE id = UUID_TO_BIN(?) AND type = ?
					""").params(jobId.toString(), TYPE)
				.query((rs, row) -> new String[] { rs.getString("project"), rs.getString("requester"),
						rs.getString("status"), rs.getString("result") })
				.optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			UUID projectId = UUID.fromString(job[0]);
			access.lock(projectId);
			access.requireMember(projectId, actor.userId());
			var key = key(actor, keyValue, "/v1/ai-proposals/" + jobId + "/apply");
			var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(json.createObjectNode()));
			if (stored.isPresent()) {
				return new Result(stored.get().status(), json.readTree(stored.get().body()));
			}
			if (!actor.userId().toString().equals(job[1])) {
				throw new ApiException(ErrorCode.FORBIDDEN);
			}
			if (!"succeeded".equals(job[2])) {
				throw invalid("proposalId", "수정 제안이 준비된 뒤 적용해 주세요.");
			}
			JsonNode proposal = json.readTree(job[3]);
			var response = json.createObjectNode().put("proposalId", jobId.toString());
			var changes = response.putArray("changes");
			int index = 0;
			for (JsonNode target : proposal.get("targets")) {
				var patch = json.createObjectNode().put("generation", proposal.get("generation").asLong())
					.put("baseVersion", target.get("baseVersion").asLong())
					.put("editGroupId", UUID.randomUUID().toString())
					.put("clientMutationId", "ai-" + jobId + "-" + index)
					.put("finalizeGroup", true);
				patch.putObject("patch").putObject("content").put("text", target.get("after").asString());
				var saved = patches.patch(actor, UUID.fromString(proposal.get("slideId").asString()),
						UUID.fromString(target.get("elementId").asString()), keyValue + ":" + index, patch);
				if (saved.status() != 200) {
					// 이미 적용했거나 그 뒤에 누가 고쳤다. 앞서 반영한 대상까지 되돌린다
					throw new ApiException(ErrorCode.VERSION_CONFLICT);
				}
				changes.add(saved.body());
				index++;
			}
			idempotency.complete(key, true, 200, json.writeValueAsString(response), jobId);
			return new Result(200, response);
		});
	}

	private static IdempotencyStore.Key key(Actor actor, String value, String path) {
		if (value == null || value.isBlank() || value.length() > 100) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		return IdempotencyStore.Key.of(actor.userId(), "POST", path, value);
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Object execute(JobQueue.Job job) {
		Input input = input(job);
		requireAccess(job);
		var prompt = json.createObjectNode().put("request", input.prompt());
		var elements = prompt.putArray("elements");
		for (int index = 0; index < input.elements().size(); index++) {
			elements.addObject().put("number", index + 1).put("text", input.elements().get(index).text());
		}
		JsonNode result = provider.revise(input.model(), json.writeValueAsString(prompt));
		List<Target> targets = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		for (JsonNode item : result.path("elements")) {
			int number = item.path("number").asInt(0);
			String text = item.path("text").asString("");
			if (number < 1 || number > input.elements().size() || !seen.add(number) || text.isBlank()
					|| text.codePointCount(0, text.length()) > TEXT_MAX) {
				throw OpenAiRevisionClient.invalidResult();
			}
			Source source = input.elements().get(number - 1);
			if (!text.equals(source.text())) {
				targets.add(new Target(source.elementId(), source.version(), source.text(), text));
			}
		}
		if (targets.isEmpty()) {
			throw new JobFailure("JOB_REVISION_NO_CHANGE", "바꿀 내용을 찾지 못했어요. 요청을 조금 더 구체적으로 적어 주세요.", false);
		}
		return targets;
	}

	@Override
	@SuppressWarnings("unchecked")
	public ObjectNode publish(JobQueue.Job job, Object output) {
		Input input = input(job);
		requireAccess(job);
		var result = json.createObjectNode().put("type", "slide_revision").put("slideId", input.slideId().toString())
			.put("generation", input.generation());
		result.set("targets", json.valueToTree(output));
		return result;
	}

	@Override
	public void settle(JobQueue.Job job, String status) {
		// 공유 문서를 바꾸지 않으므로 정리할 것이 없다
	}

	private Input input(JobQueue.Job job) {
		Input result = json.treeToValue(job.input().get("input"), Input.class);
		if (result.schemaVersion() != SCHEMA_VERSION || !PROMPT_VERSION.equals(result.promptVersion())) {
			throw new JobFailure("JOB_INPUT_STALE", "요청 뒤에 입력이 바뀌어 수정 제안을 만들지 않았어요.", false);
		}
		return result;
	}

	private void requireAccess(JobQueue.Job job) {
		try {
			access.requireMember(job.projectId(), job.requesterId());
		}
		catch (ApiException ex) {
			throw new JobFailure("JOB_REVISION_ACCESS_REVOKED", "프로젝트 접근 권한이 없어 수정 제안을 만들지 않았어요.", false);
		}
	}

}
