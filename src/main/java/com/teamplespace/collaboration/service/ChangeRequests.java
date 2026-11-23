package com.teamplespace.collaboration.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.composition.service.DesignService;
import com.teamplespace.composition.service.OutlineGeneration;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.presentation.service.PresentationGeneration;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// AI 구성 만들기, PPT 만들기, 디자인 변경을 다른 팀원 한 명이 승인하면 실행한다
// 승인한 뒤에는 요청자의 이름으로 기존 등록과 저장을 그대로 호출해 같은 검사를 다시 받는다
@Service
public class ChangeRequests {

	public record CreateRequest(String type, JsonNode payload) {
	}

	static final Set<String> TYPES = Set.of("outline_generation", "presentation_generation", "design_change");
	private static final int PAYLOAD_MAX = 20_000;
	private static final int LIST_MAX = 50;
	// 멱등 기록 보존 기간인 하루보다 짧게 둔다
	private static final int RECOVERY_LIMIT_HOURS = 20;

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final ProjectAccess access;
	private final ProjectEvents events;
	private final IdempotencyStore idempotency;
	private final TransactionTemplate tx;
	private final OutlineGeneration outlines;
	private final PresentationGeneration presentations;
	private final DesignService designs;

	public ChangeRequests(JdbcClient jdbc, ObjectMapper json, ProjectAccess access, ProjectEvents events,
			IdempotencyStore idempotency, TransactionTemplate tx, OutlineGeneration outlines,
			PresentationGeneration presentations, DesignService designs) {
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.events = events;
		this.idempotency = idempotency;
		this.tx = tx;
		this.outlines = outlines;
		this.presentations = presentations;
		this.designs = designs;
	}

	public Map<String, Object> create(UUID actor, UUID projectId, String keyValue, CreateRequest input) {
		return tx.execute(status -> {
			access.lock(projectId);
			access.requireMember(projectId, actor);
			if (input.type() == null || !TYPES.contains(input.type())) {
				throw ApiException.field(ErrorCode.VALIDATION_FAILED, "type", "INVALID", "요청 종류를 확인해 주세요.");
			}
			if (input.payload() == null || !input.payload().isObject() || input.payload().toString().length() > PAYLOAD_MAX) {
				throw ApiException.field(ErrorCode.VALIDATION_FAILED, "payload", "INVALID", "요청 내용을 확인해 주세요.");
			}
			if (keyValue == null || keyValue.isBlank() || keyValue.length() > 128) {
				throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
			}
			var key = IdempotencyStore.Key.of(actor, "POST", "/v1/projects/" + projectId + "/requests", keyValue);
			var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(json.valueToTree(input)));
			if (stored.isPresent()) {
				return read(stored.get().body());
			}
			// 같은 종류의 요청은 하나만 기다리게 한다
			if (jdbc.sql("SELECT COUNT(*) FROM change_requests WHERE project_id = UUID_TO_BIN(?) AND type = ? AND state IN ('pending', 'approving')")
				.params(projectId.toString(), input.type()).query(Integer.class).single() > 0) {
				throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS);
			}
			UUID id = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO change_requests (id, project_id, type, requested_by, payload)
					VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, UUID_TO_BIN(?), ?)
					""")
				.params(id.toString(), projectId.toString(), input.type(), actor.toString(), input.payload().toString())
				.update();
			append(projectId, "request.created", actor, id, input.type(), "pending");
			Map<String, Object> view = view(id);
			idempotency.complete(key, true, 201, json.writeValueAsString(view), id);
			return view;
		});
	}

	public Map<String, Object> list(UUID actor, UUID projectId) {
		access.requireMember(projectId, actor);
		// 기다리는 요청을 먼저, 그다음 최근에 처리한 요청을 보여 준다
		List<String> ids = jdbc.sql("""
				SELECT BIN_TO_UUID(id) FROM change_requests WHERE project_id = UUID_TO_BIN(?)
				ORDER BY state IN ('pending', 'approving') DESC, created_at DESC LIMIT ?
				""").params(projectId.toString(), LIST_MAX).query(String.class).list();
		return Map.of("items", ids.stream().map((id) -> view(UUID.fromString(id))).toList());
	}

	// 실행은 트랜잭션 밖에서 기존 등록을 부른다. 등록 쪽 오류가 상태 기록까지 되돌리지 않게 한다
	public Map<String, Object> approve(UUID actor, UUID requestId) {
		Row row = tx.execute(status -> {
			Row current = lock(actor, requestId);
			if (current.requestedBy().equals(actor)) {
				throw new ApiException(ErrorCode.FORBIDDEN);
			}
			// 승인하던 중 멈춘 요청은 2분이 지나면 다시 승인할 수 있다. 같은 멱등 키로 실행해 결과를 되찾는다
			// 멱등 기록이 지워질 만큼 오래 멈춘 요청은 이미 실행됐는지 알 수 없으니 실패로 닫는다
			if (current.state().equals("approving") && stale(requestId, RECOVERY_LIMIT_HOURS + " HOUR")) {
				jdbc.sql("""
						UPDATE change_requests SET state = 'failed', error = ?, decided_at = UTC_TIMESTAMP(6) WHERE id = UUID_TO_BIN(?)
						""").params(failure(ErrorCode.REQUEST_NOT_PENDING, "처리 결과를 확인하지 못했어요. 다시 요청해 주세요.").toString(),
						requestId.toString()).update();
				append(current.projectId(), "request.updated", actor, requestId, current.type(), "failed");
				return null;
			}
			int claimed = jdbc.sql("""
					UPDATE change_requests SET state = 'approving', decided_by = UUID_TO_BIN(?), decided_at = UTC_TIMESTAMP(6)
					WHERE id = UUID_TO_BIN(?) AND (state = 'pending'
					  OR (state = 'approving' AND decided_at < UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE))
					""").params(actor.toString(), requestId.toString()).update();
			if (claimed == 0) {
				throw new ApiException(ErrorCode.REQUEST_NOT_PENDING);
			}
			return current;
		});
		if (row == null) {
			return view(requestId);
		}
		ObjectNode result = json.createObjectNode();
		ObjectNode error = null;
		try {
			String key = "change-request-" + requestId;
			switch (row.type()) {
				case "outline_generation" -> {
					var registered = outlines.create(row.requestedBy(), row.projectId(), key, row.payload());
					if (registered.status() == 409) {
						error = failure(ErrorCode.VERSION_CONFLICT, "발표 구성이 바뀌었어요. 다시 요청해 주세요.");
					}
					else {
						result.put("jobId", registered.body().get("id").asString());
					}
				}
				case "presentation_generation" -> {
					var registered = presentations.create(row.requestedBy(), row.projectId(), key, row.payload());
					if (registered.status() == 409) {
						error = failure(ErrorCode.VERSION_CONFLICT, "발표 구성이나 디자인이 바뀌었어요. 다시 요청해 주세요.");
					}
					else {
						result.put("jobId", registered.body().get("id").asString());
					}
				}
				default -> {
					var saved = designs.save(row.requestedBy(), row.projectId(), key, row.payload());
					if (saved.status() == 409) {
						error = failure(ErrorCode.VERSION_CONFLICT, "디자인이 바뀌었어요. 다시 요청해 주세요.");
					}
					else {
						result.put("designVersion", saved.body().get("version").asLong());
					}
				}
			}
		}
		catch (ApiException ex) {
			error = failure(ex.code(), message(ex));
		}
		ObjectNode failed = error;
		tx.executeWithoutResult(status -> {
			// 다른 처리와 같은 순서로 프로젝트를 먼저 잠근다
			access.lock(row.projectId());
			jdbc.sql("""
					UPDATE change_requests SET state = ?, result = ?, error = ?, decided_at = UTC_TIMESTAMP(6)
					WHERE id = UUID_TO_BIN(?) AND state = 'approving'
					""")
				.params(failed == null ? "approved" : "failed", failed == null ? result.toString() : null,
						failed == null ? null : failed.toString(), requestId.toString())
				.update();
			append(row.projectId(), "request.updated", actor, requestId, row.type(), failed == null ? "approved" : "failed");
		});
		return view(requestId);
	}

	public Map<String, Object> reject(UUID actor, UUID requestId) {
		return finish(actor, requestId, "rejected");
	}

	public Map<String, Object> cancel(UUID actor, UUID requestId) {
		return finish(actor, requestId, "canceled");
	}

	// 거절은 요청자가 아닌 팀원이, 취소는 요청자만 한다
	private Map<String, Object> finish(UUID actor, UUID requestId, String state) {
		return tx.execute(status -> {
			Row row = lock(actor, requestId);
			boolean requester = row.requestedBy().equals(actor);
			if (state.equals("canceled") != requester) {
				throw new ApiException(ErrorCode.FORBIDDEN);
			}
			if (!row.state().equals("pending")) {
				throw new ApiException(ErrorCode.REQUEST_NOT_PENDING);
			}
			jdbc.sql("""
					UPDATE change_requests SET state = ?, decided_by = UUID_TO_BIN(?), decided_at = UTC_TIMESTAMP(6)
					WHERE id = UUID_TO_BIN(?)
					""").params(state, actor.toString(), requestId.toString()).update();
			append(row.projectId(), "request.updated", actor, requestId, row.type(), state);
			return view(requestId);
		});
	}

	private record Row(UUID projectId, String type, UUID requestedBy, String state, JsonNode payload) {
	}

	// 프로젝트를 먼저 잠가 팀원 제거와 순서를 맞춘 뒤 요청을 잠근다
	private Row lock(UUID actor, UUID requestId) {
		String projectId = jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM change_requests WHERE id = UUID_TO_BIN(?)")
			.param(requestId.toString()).query(String.class).optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		access.requireMember(UUID.fromString(projectId), actor);
		access.lock(UUID.fromString(projectId));
		access.requireMember(UUID.fromString(projectId), actor);
		return jdbc.sql("""
				SELECT type, BIN_TO_UUID(requested_by) AS requested_by, state, CAST(payload AS CHAR) AS payload
				FROM change_requests WHERE id = UUID_TO_BIN(?) FOR UPDATE
				""").param(requestId.toString())
			.query((rs, n) -> new Row(UUID.fromString(projectId), rs.getString("type"), UUID.fromString(rs.getString("requested_by")),
					rs.getString("state"), json.readTree(rs.getString("payload"))))
			.single();
	}

	private Map<String, Object> view(UUID id) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(r.id) AS id, BIN_TO_UUID(r.project_id) AS project_id, r.type, r.state,
				       CAST(r.payload AS CHAR) AS payload, CAST(r.result AS CHAR) AS result, CAST(r.error AS CHAR) AS error,
				       BIN_TO_UUID(r.requested_by) AS requested_by, requester.display_name AS requester_name,
				       BIN_TO_UUID(r.decided_by) AS decided_by, decider.display_name AS decider_name, r.created_at, r.decided_at
				FROM change_requests r
				JOIN users requester ON requester.id = r.requested_by
				LEFT JOIN users decider ON decider.id = r.decided_by
				WHERE r.id = UUID_TO_BIN(?)
				""").param(id.toString()).query((rs, n) -> {
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("id", rs.getString("id"));
			view.put("projectId", rs.getString("project_id"));
			view.put("type", rs.getString("type"));
			// 승인 중인 요청은 화면에 대기로 보인다
			view.put("state", rs.getString("state").equals("approving") ? "pending" : rs.getString("state"));
			view.put("payload", json.readTree(rs.getString("payload")));
			view.put("requestedBy", Map.of("userId", rs.getString("requested_by"), "name", rs.getString("requester_name")));
			view.put("decidedBy", rs.getString("decided_by") == null || rs.getString("state").equals("approving") ? null
					: Map.of("userId", rs.getString("decided_by"), "name", rs.getString("decider_name")));
			view.put("result", rs.getString("result") == null ? null : json.readTree(rs.getString("result")));
			view.put("error", rs.getString("error") == null ? null : json.readTree(rs.getString("error")));
			view.put("createdAt", rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
			LocalDateTime decided = rs.getObject("decided_at", LocalDateTime.class);
			view.put("decidedAt", decided == null || rs.getString("state").equals("approving") ? null : decided.toInstant(ZoneOffset.UTC));
			return view;
		}).single();
	}

	// 입력 오류는 첫 항목의 안내를, 나머지는 오류 코드의 안내를 쓴다
	private static String message(ApiException ex) {
		if (ex.details().get("fieldErrors") instanceof List<?> errors && !errors.isEmpty()
				&& errors.getFirst() instanceof Map<?, ?> first && first.get("message") instanceof String text) {
			return text;
		}
		return ex.code().message();
	}

	private boolean stale(UUID requestId, String interval) {
		return jdbc.sql("SELECT COUNT(*) FROM change_requests WHERE id = UUID_TO_BIN(?) AND decided_at < UTC_TIMESTAMP(6) - INTERVAL " + interval)
			.param(requestId.toString()).query(Integer.class).single() == 1;
	}

	private ObjectNode failure(ErrorCode code, String message) {
		return json.createObjectNode().put("code", code.name()).put("message", message);
	}

	private void append(UUID projectId, String type, UUID actor, UUID requestId, String requestType, String state) {
		ObjectNode payload = json.createObjectNode();
		payload.put("requestId", requestId.toString());
		payload.put("type", requestType);
		payload.put("state", state);
		events.append(projectId, type, actor, null, requestId, payload);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> read(String body) {
		return json.readValue(body, Map.class);
	}

}
