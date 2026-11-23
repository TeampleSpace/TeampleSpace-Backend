package com.teamplespace.materials.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 자료에 팀원이 남기는 댓글. 읽기와 쓰기 권한은 자료 규칙을 그대로 따른다
@Service
public class MaterialComments {

	public record CommentRequest(String body) {
	}

	static final int BODY_MAX = 2000;
	private static final int LIMIT_DEFAULT = 50;
	private static final int LIMIT_MAX = 100;

	private final MaterialService materials;
	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final ProjectEvents events;
	private final IdempotencyStore idempotency;

	public MaterialComments(MaterialService materials, JdbcClient jdbc, ObjectMapper json, ProjectEvents events,
			IdempotencyStore idempotency) {
		this.materials = materials;
		this.jdbc = jdbc;
		this.json = json;
		this.events = events;
		this.idempotency = idempotency;
	}

	// 오래된 댓글부터 돌려준다. cursor는 앞 페이지 마지막 댓글의 식별자다
	@Transactional(readOnly = true)
	public Map<String, Object> list(UUID actor, UUID materialId, String cursor, Integer limit) {
		materials.readable(actor, materialId);
		int size = limit == null ? LIMIT_DEFAULT : limit;
		if (size < 1 || size > LIMIT_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		String after = cursor == null ? null : cursorId(cursor);
		// 이 자료의 댓글이 아닌 커서는 빈 페이지 대신 오류로 알린다
		if (after != null && jdbc.sql("SELECT COUNT(*) FROM material_comments WHERE id = UUID_TO_BIN(?) AND material_id = UUID_TO_BIN(?)")
			.params(after, materialId.toString()).query(Integer.class).single() == 0) {
			throw new ApiException(ErrorCode.INVALID_CURSOR);
		}
		List<Map<String, Object>> items = jdbc.sql("""
				SELECT BIN_TO_UUID(c.id) AS id, c.body, BIN_TO_UUID(c.author_id) AS author_id, u.display_name, c.created_at
				FROM material_comments c JOIN users u ON u.id = c.author_id
				WHERE c.material_id = UUID_TO_BIN(:material) AND c.deleted_at IS NULL
				  AND (:after IS NULL OR (c.created_at, c.id) > (
				      SELECT created_at, id FROM material_comments WHERE id = UUID_TO_BIN(:after) AND material_id = UUID_TO_BIN(:material)))
				ORDER BY c.created_at, c.id
				LIMIT :size
				""")
			.param("material", materialId.toString())
			.param("after", after)
			.param("size", size + 1)
			.query((rs, n) -> view(materialId, rs.getString("id"), rs.getString("body"), rs.getString("author_id"),
					rs.getString("display_name"), rs.getObject("created_at", LocalDateTime.class)))
			.list();
		Map<String, Object> page = new LinkedHashMap<>();
		page.put("items", items.stream().limit(size).toList());
		page.put("nextCursor", items.size() > size ? items.get(size - 1).get("id") : null);
		return page;
	}

	@Transactional
	public Map<String, Object> create(UUID actor, UUID materialId, CommentRequest input, String keyValue) {
		var row = materials.writable(actor, materialId);
		// 앞뒤 공백을 걷은 뒤 길이를 잰다
		String body = materials.text(input.body() == null ? null : input.body().strip(), "body", BODY_MAX, true);
		if (body.isEmpty()) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "body", "REQUIRED", "내용을 입력해 주세요.");
		}
		var key = materials.key(actor, "/v1/materials/" + materialId + "/comments", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(json.valueToTree(input)));
		if (stored.isPresent()) {
			return materials.read(stored.get().body());
		}
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO material_comments (id, project_id, material_id, author_id, body)
				VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)
				""")
			.params(id.toString(), row.projectId().toString(), materialId.toString(), actor.toString(), body)
			.update();
		append(row.projectId(), "material.comment.created", actor, materialId, id);
		Map<String, Object> created = jdbc.sql("""
				SELECT c.body, u.display_name, c.created_at FROM material_comments c JOIN users u ON u.id = c.author_id
				WHERE c.id = UUID_TO_BIN(?)
				""")
			.param(id.toString())
			.query((rs, n) -> view(materialId, id.toString(), rs.getString("body"), actor.toString(),
					rs.getString("display_name"), rs.getObject("created_at", LocalDateTime.class)))
			.single();
		idempotency.complete(key, true, 201, json.writeValueAsString(created), id);
		return created;
	}

	// 작성자만 지운다. 이미 지운 댓글을 다시 지우면 같은 결과를 돌려준다
	@Transactional
	public Map<String, Object> delete(UUID actor, UUID materialId, UUID commentId) {
		var row = materials.writable(actor, materialId);
		var comment = jdbc.sql("""
				SELECT BIN_TO_UUID(author_id) AS author_id, deleted_at FROM material_comments
				WHERE id = UUID_TO_BIN(?) AND material_id = UUID_TO_BIN(?) FOR UPDATE
				""")
			.params(commentId.toString(), materialId.toString())
			.query()
			.listOfRows()
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!actor.toString().equals(comment.get("author_id"))) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		if (comment.get("deleted_at") == null) {
			jdbc.sql("UPDATE material_comments SET deleted_at = UTC_TIMESTAMP(6) WHERE id = UUID_TO_BIN(?)")
				.param(commentId.toString())
				.update();
			append(row.projectId(), "material.comment.deleted", actor, materialId, commentId);
		}
		return Map.of("id", commentId.toString(), "materialId", materialId.toString(), "deleted", true);
	}

	private void append(UUID projectId, String type, UUID actor, UUID materialId, UUID commentId) {
		ObjectNode payload = json.createObjectNode();
		payload.put("materialId", materialId.toString());
		payload.put("commentId", commentId.toString());
		events.append(projectId, type, actor, null, materialId, payload);
	}

	private static Map<String, Object> view(UUID materialId, String id, String body, String authorId, String authorName,
			LocalDateTime createdAt) {
		Map<String, Object> comment = new LinkedHashMap<>();
		comment.put("id", id);
		comment.put("materialId", materialId.toString());
		comment.put("body", body);
		comment.put("author", Map.of("userId", authorId, "name", authorName));
		comment.put("createdAt", createdAt.toInstant(ZoneOffset.UTC));
		return comment;
	}

	private static String cursorId(String cursor) {
		try {
			return UUID.fromString(cursor).toString();
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(ErrorCode.INVALID_CURSOR);
		}
	}

}
