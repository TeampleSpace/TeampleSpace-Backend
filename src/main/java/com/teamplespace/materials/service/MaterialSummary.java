package com.teamplespace.materials.service;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.materials.infra.OpenAiMaterialSummaryClient;
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
public class MaterialSummary implements JobHandler {

	static final String TYPE = "material_summary";
	private static final String PROMPT_VERSION = "material-analysis-v1";
	private static final int INPUT_CHARACTERS = 20_000;

	record Input(UUID materialId, String title, String model, String promptVersion) {
	}

	private record Source(String analysis, String text) {
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
		// 분석 정리가 없는 이전 요약 개정은 다시 분석할 수 있다
		if (source.analysis() != null) {
			throw new ApiException(ErrorCode.MATERIAL_STATE_CONFLICT);
		}
		if (source.text().isEmpty()) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "expectedRevisionId", "SOURCE_EMPTY",
					"분석할 본문이 없어요.");
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
		if (source.analysis() != null || source.text().isEmpty()) {
			throw stale();
		}
		return provider.analyze(input.model(), input.title(), source.text());
	}

	@Override
	public ObjectNode publish(JobQueue.Job job, Object output) {
		MaterialService.Row row = current(job, input(job), true);
		JsonNode analysis = (JsonNode) output;
		// summary에는 개요를 함께 남겨 요약 문장만 읽는 화면과 호환한다
		int updated = jdbc.sql("""
				UPDATE material_revisions SET summary = ?, analysis = ?
				WHERE project_id = UUID_TO_BIN(?) AND material_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?) AND analysis IS NULL
				""").params(analysis.path("overview").asString(), json.writeValueAsString(analysis), row.projectId().toString(),
						row.id().toString(), job.targetId().toString()).update();
		if (updated == 0) {
			throw stale();
		}
		return json.createObjectNode().put("materialId", row.id().toString()).put("revisionId", job.targetId().toString());
	}

	@Override
	public void settle(JobQueue.Job job, String status) {
		// 분석 정리 작업이 실패하거나 취소되어도 원문과 원문 분석 상태는 유지한다
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
			throw new JobFailure("JOB_MATERIAL_SUMMARY_ACCESS_REVOKED", "프로젝트 접근 권한이 없어 분석하지 않았어요.", false);
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
				SELECT analysis, LEFT(COALESCE(markdown, extracted_text, ''), ?) AS body FROM material_revisions
				WHERE project_id = UUID_TO_BIN(?) AND material_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?)
				""").params(INPUT_CHARACTERS, row.projectId().toString(), row.id().toString(), row.currentRevisionId().toString())
			.query((rs, index) -> new Source(rs.getString("analysis"), MaterialAnalysis.clean(rs.getString("body"))))
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
				"분석할 원문 개정을 확인해 주세요.");
	}

	private static JobFailure stale() {
		return new JobFailure("JOB_INPUT_STALE", "원문이 변경됐어요. 현재 자료에서 분석을 다시 요청해 주세요.", false);
	}
}
