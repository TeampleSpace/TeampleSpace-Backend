package com.teamplespace.materials;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.processing.JobFailure;
import com.teamplespace.processing.JobHandler;
import com.teamplespace.processing.JobQueue;
import com.teamplespace.workspace.application.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class MaterialSummary implements JobHandler {

	static final String TYPE = "material_summary";
	private static final String PROMPT_VERSION = "material-summary-v1";
	private static final int INPUT_CHARACTERS = 20_000;

	record Input(UUID materialId, String title, String model, String promptVersion) {
	}

	private record Source(String summary, String text) {
	}

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final ProjectAccess access;
	private final MaterialService materials;
	private final JobQueue jobs;
	private final IdempotencyStore idempotency;
	private final OpenAiMaterialSummaryClient provider;

	public MaterialSummary(JdbcClient jdbc, ObjectMapper json, ProjectAccess access, MaterialService materials,
			JobQueue jobs, IdempotencyStore idempotency, OpenAiMaterialSummaryClient provider) {
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.materials = materials;
		this.jobs = jobs;
		this.idempotency = idempotency;
		this.provider = provider;
	}

	@Transactional
	public JsonNode create(UUID actor, UUID materialId, String keyValue, JsonNode body) {
		MaterialService.Row row = materials.writable(actor, materialId);
		if (keyValue == null || keyValue.isBlank() || keyValue.length() > 128) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		var key = IdempotencyStore.Key.of(actor, "POST", "/v1/materials/" + materialId + "/summary-jobs", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
		if (stored.isPresent()) {
			return json.readTree(stored.get().body());
		}
		if (!provider.available()) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		UUID revisionId = expectedRevision(body);
		if (!isCurrent(row, revisionId)) {
			throw new ApiException(ErrorCode.MATERIAL_STATE_CONFLICT);
		}
		Source source = source(row);
		if (source.summary() != null) {
			throw new ApiException(ErrorCode.MATERIAL_STATE_CONFLICT);
		}
		if (source.text().isEmpty()) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "expectedRevisionId", "SOURCE_EMPTY",
					"요약할 본문이 없어요.");
		}
		boolean active = jdbc.sql("""
				SELECT 1 FROM jobs WHERE project_id = UUID_TO_BIN(?) AND target_type = ?
				  AND target_id = UUID_TO_BIN(?) AND status IN ('queued', 'running') LIMIT 1
				""").params(row.projectId().toString(), TYPE, revisionId.toString()).query(Integer.class).optional().isPresent();
		if (active) {
			throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS, IdempotencyStore.RETRY_AFTER_SECONDS);
		}
		var input = new Input(materialId, MaterialAnalysis.clean(row.title()), provider.model(), PROMPT_VERSION);
		var snapshot = json.createObjectNode();
		snapshot.set("input", json.valueToTree(input));
		UUID jobId = jobs.register(row.projectId(), actor, TYPE, TYPE, revisionId, snapshot);
		var response = json.valueToTree(jobs.get(actor, jobId));
		idempotency.complete(key, true, 202, json.writeValueAsString(response), jobId);
		return response;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Object execute(JobQueue.Job job) {
		Input input = input(job);
		Source source = source(current(job, input, false));
		if (source.summary() != null || source.text().isEmpty()) {
			throw stale();
		}
		return provider.summarize(input.model(), input.title(), source.text());
	}

	@Override
	public ObjectNode publish(JobQueue.Job job, Object output) {
		MaterialService.Row row = current(job, input(job), true);
		int updated = jdbc.sql("""
				UPDATE material_revisions SET summary = ?
				WHERE project_id = UUID_TO_BIN(?) AND material_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?) AND summary IS NULL
				""").params(output, row.projectId().toString(), row.id().toString(), job.targetId().toString()).update();
		if (updated == 0) {
			throw stale();
		}
		return json.createObjectNode().put("materialId", row.id().toString()).put("revisionId", job.targetId().toString());
	}

	@Override
	public void settle(JobQueue.Job job, String status) {
		// 요약 작업이 실패하거나 취소되어도 원문과 분석 상태는 유지한다
	}

	private Input input(JobQueue.Job job) {
		Input input = json.treeToValue(job.input().get("input"), Input.class);
		if (input == null || !PROMPT_VERSION.equals(input.promptVersion())) {
			throw stale();
		}
		return input;
	}

	private MaterialService.Row current(JobQueue.Job job, Input input, boolean lock) {
		try {
			access.requireMember(job.projectId(), job.requesterId());
		}
		catch (ApiException ex) {
			throw new JobFailure("JOB_MATERIAL_SUMMARY_ACCESS_REVOKED", "프로젝트 접근 권한이 없어 요약하지 않았어요.", false);
		}
		MaterialService.Row row = materials.find(input.materialId(), lock);
		if (row.deleted() || !row.projectId().equals(job.projectId())) {
			throw JobFailure.targetGone();
		}
		if (!isCurrent(row, job.targetId())) {
			throw stale();
		}
		return row;
	}

	private static boolean isCurrent(MaterialService.Row row, UUID revisionId) {
		return row.state().equals("ready") && Objects.equals(row.currentRevisionId(), revisionId);
	}

	private Source source(MaterialService.Row row) {
		return jdbc.sql("""
				SELECT summary, LEFT(COALESCE(markdown, extracted_text, ''), ?) AS body FROM material_revisions
				WHERE project_id = UUID_TO_BIN(?) AND material_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?)
				""").params(INPUT_CHARACTERS, row.projectId().toString(), row.id().toString(), row.currentRevisionId().toString())
			.query((rs, index) -> new Source(rs.getString("summary"), MaterialAnalysis.clean(rs.getString("body"))))
			.single();
	}

	private static UUID expectedRevision(JsonNode body) {
		if (body != null && body.isObject() && body.size() == 1 && body.path("expectedRevisionId").isString()) {
			String value = body.path("expectedRevisionId").asString();
			try {
				UUID id = UUID.fromString(value);
				if (id.toString().equalsIgnoreCase(value)) {
					return id;
				}
			}
			catch (IllegalArgumentException ignored) {
				// 잘못된 개정 식별자는 같은 입력 오류로 응답한다
			}
		}
		throw ApiException.field(ErrorCode.VALIDATION_FAILED, "expectedRevisionId", "INVALID_REVISION",
				"요약할 원문 개정을 확인해 주세요.");
	}

	private static JobFailure stale() {
		return new JobFailure("JOB_INPUT_STALE", "원문이 변경됐어요. 현재 자료에서 요약을 다시 요청해 주세요.", false);
	}
}
