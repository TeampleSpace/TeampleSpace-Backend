package com.teamplespace.composition.service;

import static com.teamplespace.composition.service.OutlineInput.invalid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.composition.infra.OpenAiOutlineClient;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.processing.service.JobFailure;
import com.teamplespace.processing.service.JobHandler;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OutlineGeneration implements JobHandler {

	public record Registration(int status, JsonNode body) {
	}

	record Source(UUID materialId, long materialVersion, UUID revisionId, long sourceVersion) {
	}

	// mode가 extend면 기존 구성을 유지하고 새 자료를 더한다. 이전 작업처럼 값이 없으면 전체를 새로 만든다
	record Input(long outlineVersion, List<Source> sources, String instructions,
			String model, int schemaVersion, String promptVersion, String mode) {

		boolean extend() {
			return EXTEND.equals(mode);
		}
	}

	private record Evidence(Source reference, String title, String text) {
	}

	static final String TYPE = "outline_generation";
	private static final int SCHEMA_VERSION = 1;
	private static final String PROMPT_VERSION = "outline-v1";
	private static final int MAX_GENERATED_ITEMS = 20;
	private static final int MAX_EXTENDED_ITEMS = 10;
	private static final String EXTEND = "extend";
	private static final Set<String> MODES = Set.of("replace", EXTEND);
	private static final Set<String> REQUEST_FIELDS = Set.of("expectedVersion", "sourceIds", "instructions", "mode");

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final ProjectAccess access;
	private final OutlineService outlines;
	private final JobQueue jobs;
	private final IdempotencyStore idempotency;
	private final OpenAiOutlineClient provider;

	public OutlineGeneration(JdbcClient jdbc, ObjectMapper json, ProjectAccess access, OutlineService outlines,
			JobQueue jobs, IdempotencyStore idempotency, OpenAiOutlineClient provider) {
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.outlines = outlines;
		this.jobs = jobs;
		this.idempotency = idempotency;
		this.provider = provider;
	}

	@Transactional
	public Registration create(UUID actor, UUID projectId, String keyValue, JsonNode body) {
		access.lock(projectId);
		access.requireMember(projectId, actor);
		if (keyValue == null || keyValue.isBlank() || keyValue.length() > 128) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		var key = IdempotencyStore.Key.of(actor, "POST", "/v1/projects/" + projectId + "/outline-jobs", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
		if (stored.isPresent()) {
			return new Registration(stored.get().status(), json.readTree(stored.get().body()));
		}
		if (!provider.available()) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		OutlineInput.checkFields(body, REQUEST_FIELDS, "outlineJob");
		long expected = OutlineInput.expectedVersion(body);
		long current = outlines.version(projectId);
		if (expected != current) {
			return complete(key, 409, json.createObjectNode().put("outlineVersion", current), projectId);
		}
		if (current == OutlineInput.MAX_VERSION) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}

		List<UUID> ids = OutlineInput.sources(body.path("sourceIds"), "sourceIds");
		if (ids.isEmpty()) {
			throw invalid("sourceIds", "발표에 사용할 자료를 하나 이상 선택해 주세요.");
		}
		List<Source> sources = readEvidence(projectId, ids, false).stream().map(Evidence::reference).toList();
		if (sources.size() != ids.size()) {
			throw invalid("sourceIds", "같은 프로젝트에서 이용할 수 있는 자료를 선택해 주세요.");
		}
		String instructions = body.has("instructions") ? OutlineInput.text(body.get("instructions"), "instructions", 1_000) : "";
		String mode = body.has("mode") ? OutlineInput.text(body.get("mode"), "mode", 16) : "replace";
		if (!MODES.contains(mode)) {
			throw invalid("mode", "구성 생성 방식을 확인해 주세요.");
		}
		if (EXTEND.equals(mode) && outlines.get(actor, projectId).items().isEmpty()) {
			throw invalid("mode", "새 자료를 반영할 발표 구성이 없어요. 구성을 먼저 만들어 주세요.");
		}
		boolean alreadyRunning = jdbc.sql("""
				SELECT 1 FROM jobs WHERE project_id = UUID_TO_BIN(?) AND type = ?
				  AND status IN ('queued', 'running') LIMIT 1
				""").params(projectId.toString(), TYPE).query(Integer.class).optional().isPresent();
		if (alreadyRunning) {
			throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS, IdempotencyStore.RETRY_AFTER_SECONDS);
		}
		Input input = new Input(current, sources, instructions, provider.model(), SCHEMA_VERSION, PROMPT_VERSION, mode);
		ObjectNode snapshot = json.createObjectNode();
		snapshot.set("input", json.valueToTree(input));
		snapshot.putObject("inputVersions").put("outlineVersion", current);
		UUID jobId = jobs.register(projectId, actor, TYPE, "outline", projectId, snapshot);
		return complete(key, 202, json.valueToTree(jobs.get(actor, jobId)), jobId);
	}

	private Registration complete(IdempotencyStore.Key key, int status, JsonNode body, UUID resource) {
		idempotency.complete(key, status == 202, status, json.writeValueAsString(body), resource);
		return new Registration(status, body);
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Object execute(JobQueue.Job job) {
		Input input = input(job);
		List<Evidence> sources = current(job, input, true);
		var prompt = json.createObjectNode().put("instructions", input.instructions());
		var numbered = prompt.putArray("sources");
		for (int index = 0; index < sources.size(); index++) {
			Evidence source = sources.get(index);
			numbered.addObject().put("number", index + 1).put("title", source.title()).put("text", source.text());
		}
		if (!input.extend()) {
			return parseResult(input, provider.generate(input.model(), json.writeValueAsString(prompt), false));
		}
		// 기존 항목에는 고른 자료에 든 근거만 번호로 알려 준다
		List<OutlineService.Item> existing = outlines.get(job.requesterId(), job.projectId()).items();
		Map<UUID, Integer> numbers = new HashMap<>();
		for (int index = 0; index < input.sources().size(); index++) {
			numbers.put(input.sources().get(index).materialId(), index + 1);
		}
		var items = prompt.putArray("existingItems");
		for (int index = 0; index < existing.size(); index++) {
			OutlineService.Item item = existing.get(index);
			var entry = items.addObject().put("number", index + 1).put("title", item.title())
				.put("summary", item.summary() == null ? "" : item.summary());
			var linked = entry.putArray("sourceNumbers");
			item.sourceRefs().stream().map(ref -> numbers.get(ref.materialId())).filter(number -> number != null)
				.forEach(linked::add);
		}
		return extend(input, existing, provider.generate(input.model(), json.writeValueAsString(prompt), true));
	}

	@Override
	public ObjectNode publish(JobQueue.Job job, Object output) {
		Input input = input(job);
		current(job, input, false);
		@SuppressWarnings("unchecked")
		OutlineService.Outline outline = output instanceof OutlineInput generated
				? outlines.publishGenerated(job.requesterId(), job.projectId(), generated)
				: outlines.publishItems(job.requesterId(), job.projectId(), (List<OutlineService.Item>) output);
		return json.createObjectNode().put("type", "outline").put("projectId", job.projectId().toString())
			.put("outlineVersion", outline.version());
	}

	@Override
	public void settle(JobQueue.Job job, String status) {
		// 발행 전에는 공유 구성에 작업 상태를 저장하지 않는다
	}

	private Input input(JobQueue.Job job) {
		Input result = json.treeToValue(job.input().get("input"), Input.class);
		if (result.schemaVersion() != SCHEMA_VERSION || !PROMPT_VERSION.equals(result.promptVersion())) {
			throw stale();
		}
		return result;
	}

	private List<Evidence> current(JobQueue.Job job, Input input, boolean withText) {
		try {
			access.requireMember(job.projectId(), job.requesterId());
		}
		catch (ApiException ex) {
			throw new JobFailure("JOB_OUTLINE_ACCESS_REVOKED", "프로젝트 접근 권한이 없어 구성 생성을 완료하지 않았어요.", false);
		}
		if (outlines.version(job.projectId()) != input.outlineVersion()) {
			throw stale();
		}
		List<UUID> ids = input.sources().stream().map(Source::materialId).toList();
		List<Evidence> evidence = readEvidence(job.projectId(), ids, withText);
		if (!evidence.stream().map(Evidence::reference).toList().equals(input.sources())) {
			throw stale();
		}
		return evidence;
	}

	private List<Evidence> readEvidence(UUID projectId, List<UUID> ids, boolean withText) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(m.id) AS material_id, m.version AS material_version,
				       BIN_TO_UUID(r.id) AS revision_id, r.revision_no, m.title,
				       CASE WHEN :withText THEN LEFT(COALESCE(r.markdown, r.extracted_text, ''), 2000) ELSE '' END AS body
				FROM JSON_TABLE(:ids, '$[*]' COLUMNS (
				     position FOR ORDINALITY, material_id VARCHAR(36) PATH '$')) selected
				JOIN materials m ON m.id = UUID_TO_BIN(selected.material_id)
				JOIN material_revisions r ON r.id = m.current_revision_id AND r.project_id = m.project_id AND r.material_id = m.id
				WHERE m.project_id = UUID_TO_BIN(:project) AND m.state = 'ready' AND m.deleted_at IS NULL
				ORDER BY selected.position
				""").param("ids", json.writeValueAsString(ids)).param("project", projectId.toString())
			.param("withText", withText).query((rs, unused) -> new Evidence(
				new Source(UUID.fromString(rs.getString("material_id")), rs.getLong("material_version"),
					UUID.fromString(rs.getString("revision_id")), rs.getLong("revision_no")),
				rs.getString("title"), rs.getString("body"))).list();
	}

	private OutlineInput parseResult(Input input, JsonNode generated) {
		try {
			OutlineInput.checkFields(generated, Set.of("items"), "items");
			JsonNode items = generated.path("items");
			if (!items.isArray() || items.isEmpty() || items.size() > MAX_GENERATED_ITEMS) {
				throw OpenAiOutlineClient.invalidResult();
			}
			var replacement = json.createObjectNode().put("expectedVersion", input.outlineVersion());
			var mapped = replacement.putArray("items");
			for (JsonNode item : items) {
				OutlineInput.checkFields(item, Set.of("title", "summary", "sourceNumbers"), "items");
				var output = mapped.addObject();
				output.set("title", item.path("title"));
				output.set("summary", item.path("summary"));
				JsonNode numbers = item.path("sourceNumbers");
				if (!numbers.isArray() || numbers.isEmpty() || numbers.size() > input.sources().size()) {
					throw OpenAiOutlineClient.invalidResult();
				}
				var ids = output.putArray("sourceIds");
				for (JsonNode number : numbers) {
					if (!number.isIntegralNumber() || !number.canConvertToInt()
							|| number.asInt() < 1 || number.asInt() > input.sources().size()) {
						throw OpenAiOutlineClient.invalidResult();
					}
					ids.add(input.sources().get(number.asInt() - 1).materialId().toString());
				}
			}
			return OutlineInput.parse(replacement);
		}
		catch (ApiException ex) {
			throw OpenAiOutlineClient.invalidResult();
		}
	}

	// 기존 항목은 제목, 설명, 근거를 그대로 두고 새 근거만 덧붙인다. 새 항목은 앞서 나온 기존 항목 뒤에 둔다
	// 응답에서 빠진 기존 항목도 원래 자리에 남긴다
	private List<OutlineService.Item> extend(Input input, List<OutlineService.Item> existing, JsonNode generated) {
		try {
			OutlineInput.checkFields(generated, Set.of("items"), "items");
			JsonNode items = generated.path("items");
			if (!items.isArray()) {
				throw OpenAiOutlineClient.invalidResult();
			}
			Map<Integer, List<OutlineService.Item>> added = new HashMap<>();
			List<List<OutlineService.SourceRef>> refs = new ArrayList<>();
			existing.forEach(item -> refs.add(new ArrayList<>(item.sourceRefs())));
			int anchor = 0;
			int created = 0;
			for (JsonNode item : items) {
				OutlineInput.checkFields(item, Set.of("existing", "title", "summary", "sourceNumbers"), "items");
				JsonNode number = item.path("existing");
				if (!number.isInt() || number.asInt() < 0 || number.asInt() > existing.size()) {
					throw OpenAiOutlineClient.invalidResult();
				}
				List<OutlineService.SourceRef> sources = sources(input, item.path("sourceNumbers"));
				if (number.asInt() > 0) {
					anchor = Math.max(anchor, number.asInt());
					List<OutlineService.SourceRef> current = refs.get(number.asInt() - 1);
					sources.stream().filter(ref -> current.stream().noneMatch(old -> old.materialId().equals(ref.materialId())))
						.forEach(current::add);
					continue;
				}
				String title = OutlineInput.text(item.path("title"), "title", 255).strip();
				if (title.isEmpty() || sources.isEmpty() || ++created > MAX_EXTENDED_ITEMS) {
					throw OpenAiOutlineClient.invalidResult();
				}
				added.computeIfAbsent(anchor, unused -> new ArrayList<>()).add(new OutlineService.Item(UUID.randomUUID(), title,
						OutlineInput.text(item.path("summary"), "summary", 10_000), 0, sources));
			}
			List<OutlineService.Item> result = new ArrayList<>(added.getOrDefault(0, List.of()));
			for (int index = 0; index < existing.size(); index++) {
				OutlineService.Item item = existing.get(index);
				if (refs.get(index).size() > OutlineInput.MAX_SOURCES) {
					throw OpenAiOutlineClient.invalidResult();
				}
				result.add(new OutlineService.Item(item.id(), item.title(), item.summary(), 0, List.copyOf(refs.get(index))));
				result.addAll(added.getOrDefault(index + 1, List.of()));
			}
			if (result.size() > OutlineInput.MAX_ITEMS) {
				throw OpenAiOutlineClient.invalidResult();
			}
			return result;
		}
		catch (ApiException ex) {
			throw OpenAiOutlineClient.invalidResult();
		}
	}

	private static List<OutlineService.SourceRef> sources(Input input, JsonNode numbers) {
		if (!numbers.isArray() || numbers.size() > input.sources().size()) {
			throw OpenAiOutlineClient.invalidResult();
		}
		List<OutlineService.SourceRef> refs = new ArrayList<>();
		for (JsonNode number : numbers) {
			if (!number.isInt() || number.asInt() < 1 || number.asInt() > input.sources().size()) {
				throw OpenAiOutlineClient.invalidResult();
			}
			Source source = input.sources().get(number.asInt() - 1);
			if (refs.stream().noneMatch(ref -> ref.materialId().equals(source.materialId()))) {
				refs.add(new OutlineService.SourceRef(source.materialId(), source.revisionId(), source.sourceVersion(), false));
			}
		}
		return refs;
	}

	private static JobFailure stale() {
		return new JobFailure("JOB_INPUT_STALE", "저장한 발표 구성이나 자료가 바뀌었어요. 최신 값으로 다시 생성해 주세요.", false);
	}
}
