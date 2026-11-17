package com.teamplespace.presentation.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.presentation.application.ElementPatchRequest.Geometry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 요소 수정 저장 경로. 문서 변경, 이벤트, 멱등 결과를 한 트랜잭션으로 저장한다
// 운영 인증이 생기기 전까지 사용자 식별자는 호출자가 넘기며 HTTP 경로로 노출하지 않는다
@Service
public class ElementPatchService {

	public record Result(int status, JsonNode body, boolean replayed) {
	}

	private record Current(String type, long elementVersion, boolean elementDeleted, Geometry geometry,
			String content, String style, long slideVersion, boolean slideDeleted, String presentationId,
			long generation, long presentationVersion) {
	}

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final IdempotencyStore idempotency;

	private final ObjectMapper json;

	public ElementPatchService(JdbcClient jdbc, TransactionTemplate transaction, IdempotencyStore idempotency,
			ObjectMapper json) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.idempotency = idempotency;
		this.json = json;
	}

	public Result patch(UUID actorId, UUID slideId, UUID elementId, String idempotencyKey, JsonNode body) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		ElementPatchRequest request = ElementPatchRequest.parse(body);
		IdempotencyStore.Key key = IdempotencyStore.Key.of(actorId, "PATCH",
				"/v1/slides/" + slideId + "/elements/" + elementId, idempotencyKey);
		byte[] requestHash = CanonicalJson.sha256(body);
		return transaction.execute(status -> patch(actorId, slideId, elementId, key, requestHash, request));
	}

	private Result patch(UUID actorId, UUID slideId, UUID elementId, IdempotencyStore.Key key, byte[] requestHash,
			ElementPatchRequest request) {
		// 1. 인증된 사용자의 프로젝트 참여 여부를 먼저 확인한다
		UUID projectId = jdbc
			.sql("SELECT BIN_TO_UUID(project_id) FROM slide_elements"
					+ " WHERE id = UUID_TO_BIN(:element) AND slide_id = UUID_TO_BIN(:slide)")
			.param("element", elementId.toString())
			.param("slide", slideId.toString())
			.query(String.class)
			.optional()
			.map(UUID::fromString)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireActiveMember(projectId, actorId);

		// 2. 버전 검사보다 먼저 같은 멱등키의 기존 결과를 찾는다
		Optional<IdempotencyStore.Stored> stored = idempotency.findOrClaim(key, requestHash);
		if (stored.isPresent()) {
			return new Result(stored.get().status(), json.readTree(stored.get().body()), true);
		}

		// 3. 프로젝트, 문서, 대상 순서로 잠그고 권한을 다시 확인한다
		jdbc.sql("SELECT 1 FROM projects WHERE id = UUID_TO_BIN(:project) AND status = 'active' FOR UPDATE")
			.param("project", projectId.toString())
			.query(Integer.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireActiveMember(projectId, actorId);
		Current current = lockCurrent(elementId);

		// 4. 새 요청만 세대와 버전을 검사한다. 충돌 결과도 같은 키의 재시도에 그대로 돌려준다
		if (current.elementDeleted() || current.slideDeleted() || current.generation() != request.generation()
				|| current.elementVersion() != request.baseVersion()) {
			ObjectNode conflict = conflict(current);
			idempotency.complete(key, false, ErrorCode.VERSION_CONFLICT.httpStatus(), json.writeValueAsString(conflict),
					elementId);
			return new Result(ErrorCode.VERSION_CONFLICT.httpStatus(), conflict, false);
		}

		// 5. 요소와 상위 버전을 함께 올린다
		Geometry geometry = request.geometry() != null ? request.geometry() : current.geometry();
		JsonNode content = request.content() != null ? request.content() : json.readTree(current.content());
		JsonNode style = request.style() != null ? request.style() : json.readTree(current.style());
		long elementVersion = current.elementVersion() + 1;
		long slideVersion = current.slideVersion() + 1;
		long presentationVersion = current.presentationVersion() + 1;
		jdbc.sql("""
				UPDATE slide_elements
				SET x = :x, y = :y, width = :width, height = :height, rotation = :rotation, z_order = :z,
				    content = CAST(:content AS JSON), style = CAST(:style AS JSON), version = :version
				WHERE id = UUID_TO_BIN(:element)
				""")
			.param("x", geometry.x())
			.param("y", geometry.y())
			.param("width", geometry.width())
			.param("height", geometry.height())
			.param("rotation", geometry.rotation())
			.param("z", geometry.z())
			.param("content", json.writeValueAsString(content))
			.param("style", json.writeValueAsString(style))
			.param("version", elementVersion)
			.param("element", elementId.toString())
			.update();
		jdbc.sql("UPDATE slides SET version = :version WHERE id = UUID_TO_BIN(:slide)")
			.param("version", slideVersion)
			.param("slide", slideId.toString())
			.update();
		jdbc.sql("UPDATE presentations SET version = :version WHERE id = UUID_TO_BIN(:presentation)")
			.param("version", presentationVersion)
			.param("presentation", current.presentationId())
			.update();

		ObjectNode change = json.createObjectNode();
		change.set("resource", element(elementId, slideId, current.type(), elementVersion, geometry, content, style));
		change.put("elementVersion", elementVersion);
		change.put("slideVersion", slideVersion);
		change.put("presentationVersion", presentationVersion);
		change.put("generation", current.generation());

		// 6. 프로젝트 잠금 아래에서 이벤트 순번을 할당한다
		long eventSeq = appendEvent(projectId, "element.updated", actorId, request.clientMutationId(), elementId,
				change);

		// 7. 응답을 멱등 결과로 남긴다
		ObjectNode response = change.deepCopy();
		response.put("clientMutationId", request.clientMutationId());
		response.put("eventId", Long.toString(eventSeq));
		idempotency.complete(key, true, 200, json.writeValueAsString(response), elementId);
		return new Result(200, response, false);
	}

	private void requireActiveMember(UUID projectId, UUID actorId) {
		int active = jdbc
			.sql("SELECT COUNT(*) FROM project_memberships WHERE project_id = UUID_TO_BIN(:project)"
					+ " AND user_id = UUID_TO_BIN(:user) AND state = 'active'")
			.param("project", projectId.toString())
			.param("user", actorId.toString())
			.query(Integer.class)
			.single();
		if (active == 0) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}

	private Current lockCurrent(UUID elementId) {
		return jdbc.sql("""
				SELECT e.type, e.version AS element_version, e.deleted_at IS NOT NULL AS element_deleted,
				       e.x, e.y, e.width, e.height, e.rotation, e.z_order,
				       CAST(e.content AS CHAR) AS content, CAST(e.style AS CHAR) AS style,
				       s.version AS slide_version, s.deleted_at IS NOT NULL AS slide_deleted,
				       BIN_TO_UUID(p.id) AS presentation_id, p.generation, p.version AS presentation_version
				FROM slide_elements e
				JOIN slides s ON s.id = e.slide_id
				JOIN presentations p ON p.id = s.presentation_id
				WHERE e.id = UUID_TO_BIN(:element)
				FOR UPDATE
				""")
			.param("element", elementId.toString())
			.query((rs, rowNum) -> new Current(rs.getString("type"), rs.getLong("element_version"),
					rs.getBoolean("element_deleted"),
					new Geometry(rs.getBigDecimal("x"), rs.getBigDecimal("y"), rs.getBigDecimal("width"),
							rs.getBigDecimal("height"), rs.getBigDecimal("rotation"), rs.getInt("z_order")),
					rs.getString("content"), rs.getString("style"), rs.getLong("slide_version"),
					rs.getBoolean("slide_deleted"), rs.getString("presentation_id"), rs.getLong("generation"),
					rs.getLong("presentation_version")))
			.single();
	}

	private long appendEvent(UUID projectId, String type, UUID actorId, String mutationId, UUID resourceId,
			JsonNode payload) {
		jdbc.sql("UPDATE projects SET event_seq = event_seq + 1 WHERE id = UUID_TO_BIN(:project)")
			.param("project", projectId.toString())
			.update();
		long seq = jdbc.sql("SELECT event_seq FROM projects WHERE id = UUID_TO_BIN(:project)")
			.param("project", projectId.toString())
			.query(Long.class)
			.single();
		jdbc.sql("""
				INSERT INTO project_events (project_id, seq, type, actor_id, mutation_id, resource_id, payload, occurred_at)
				VALUES (UUID_TO_BIN(:project), :seq, :type, UUID_TO_BIN(:actor), :mutation, UUID_TO_BIN(:resource),
				        CAST(:payload AS JSON), NOW(6))
				""")
			.param("project", projectId.toString())
			.param("seq", seq)
			.param("type", type)
			.param("actor", actorId.toString())
			.param("mutation", mutationId)
			.param("resource", resourceId.toString())
			.param("payload", json.writeValueAsString(payload))
			.update();
		return seq;
	}

	private ObjectNode conflict(Current current) {
		ObjectNode error = json.createObjectNode();
		error.put("code", ErrorCode.VERSION_CONFLICT.name());
		error.put("message", ErrorCode.VERSION_CONFLICT.message());
		error.put("retryable", ErrorCode.VERSION_CONFLICT.retryable());
		ObjectNode versions = error.putObject("currentVersions");
		versions.put("generation", current.generation());
		versions.put("presentationVersion", current.presentationVersion());
		versions.put("slideVersion", current.slideVersion());
		versions.put("elementVersion", current.elementVersion());
		ObjectNode body = json.createObjectNode();
		body.set("error", error);
		return body;
	}

	// 문서 형식의 요소. 출처 연결은 자료 기능을 만들 때 채운다
	private ObjectNode element(UUID elementId, UUID slideId, String type, long version, Geometry geometry,
			JsonNode content, JsonNode style) {
		ObjectNode element = json.createObjectNode();
		element.put("id", elementId.toString());
		element.put("slideId", slideId.toString());
		element.put("type", type);
		element.put("version", version);
		ObjectNode node = element.putObject("geometry");
		node.put("x", plain(geometry.x()));
		node.put("y", plain(geometry.y()));
		node.put("width", plain(geometry.width()));
		node.put("height", plain(geometry.height()));
		node.put("rotation", plain(geometry.rotation()));
		node.put("z", geometry.z());
		element.set("content", content);
		element.set("style", style);
		element.putArray("sourceRefs");
		return element;
	}

	// 80.0000을 8E+1이 아니라 80으로 내보낸다
	private static BigDecimal plain(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

}
