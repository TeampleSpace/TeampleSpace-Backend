package com.teamplespace.collaboration.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 프로젝트별 순번으로 쌓는 이벤트 기록과 커서 이후 재생
@Component
public class ProjectEvents {

	// 커서가 쓸 수 없으면 이벤트 대신 스냅샷을 다시 읽으라고 알린다
	public record Replay(boolean snapshotRequired, List<JsonNode> events) {
	}

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final Clock clock;

	private final ProjectAccess access;

	public ProjectEvents(JdbcClient jdbc, ObjectMapper json, Clock clock, ProjectAccess access) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.access = access;
	}

	// 프로젝트 잠금을 잡은 트랜잭션 안에서만 호출한다. 문서 변경과 함께 커밋된다
	// 실행자가 만든 변경처럼 요청한 사용자가 없으면 actorId는 null이다
	public long append(UUID projectId, String type, UUID actorId, String mutationId, UUID resourceId,
			JsonNode payload) {
		jdbc.sql("UPDATE projects SET event_seq = event_seq + 1 WHERE id = UUID_TO_BIN(:project)")
			.param("project", projectId.toString())
			.update();
		long seq = currentSeq(projectId);
		jdbc.sql("""
				INSERT INTO project_events (project_id, seq, type, actor_id, mutation_id, resource_id, payload, occurred_at)
				VALUES (UUID_TO_BIN(:project), :seq, :type, UUID_TO_BIN(:actor), :mutation, UUID_TO_BIN(:resource),
				        CAST(:payload AS JSON), :occurredAt)
				""")
			.param("project", projectId.toString())
			.param("seq", seq)
			.param("type", type)
			.param("actor", actorId == null ? null : actorId.toString())
			.param("mutation", mutationId)
			.param("resource", resourceId.toString())
			.param("payload", json.writeValueAsString(payload))
			.param("occurredAt", LocalDateTime.now(clock))
			.update();
		return seq;
	}

	public long currentSeq(UUID projectId) {
		return jdbc.sql("SELECT event_seq FROM projects WHERE id = UUID_TO_BIN(:project)")
			.param("project", projectId.toString())
			.query(Long.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	// 커서 다음 이벤트를 순번대로 돌려준다. 재연결할 때 마지막으로 받은 eventId를 커서로 쓴다
	@Transactional(readOnly = true)
	public Replay after(Actor actor, UUID projectId, String cursor, int limit) {
		access.requireMember(projectId, actor.userId());
		long current = currentSeq(projectId);
		Long oldest = jdbc.sql("SELECT MIN(seq) FROM project_events WHERE project_id = UUID_TO_BIN(:project)")
			.param("project", projectId.toString())
			.query(Long.class)
			.optional()
			.orElse(null);
		long after;
		try {
			after = Long.parseLong(cursor);
		}
		catch (NumberFormatException ex) {
			return new Replay(true, List.of());
		}
		// 아직 없는 순번이거나 필요한 이벤트가 이미 정리됐으면 이어 받을 수 없다
		long firstAvailable = oldest != null ? oldest : current + 1;
		if (after < 0 || after > current || after + 1 < firstAvailable) {
			return new Replay(true, List.of());
		}
		List<JsonNode> events = jdbc.sql("""
				SELECT seq, type, BIN_TO_UUID(actor_id) AS actor_id, mutation_id, BIN_TO_UUID(resource_id) AS resource_id,
				       CAST(payload AS CHAR) AS payload, occurred_at
				FROM project_events
				WHERE project_id = UUID_TO_BIN(:project) AND seq > :after
				ORDER BY seq
				LIMIT :limit
				""")
			.param("project", projectId.toString())
			.param("after", after)
			.param("limit", limit)
			.query((rs, rowNum) -> envelope(projectId, rs.getLong("seq"), rs.getString("type"),
					rs.getString("actor_id"), rs.getString("mutation_id"), rs.getString("resource_id"),
					json.readTree(rs.getString("payload")), rs.getObject("occurred_at", LocalDateTime.class)))
			.list();
		return new Replay(false, events);
	}

	// contracts/schemas/api.schema.json의 Event
	private JsonNode envelope(UUID projectId, long seq, String type, String actorId, String mutationId,
			String resourceId, JsonNode payload, LocalDateTime occurredAt) {
		ObjectNode event = json.createObjectNode();
		event.put("eventId", Long.toString(seq));
		event.put("projectId", projectId.toString());
		event.put("type", type);
		event.put("actorId", actorId);
		if (mutationId != null) {
			event.put("clientMutationId", mutationId);
		}
		event.put("occurredAt", occurredAt.format(TIMESTAMP));
		if (resourceId != null) {
			event.put("resourceId", resourceId);
		}
		if (payload.has("elementVersion")) {
			event.put("resourceVersion", payload.get("elementVersion").asLong());
		}
		if (payload.has("presentationVersion")) {
			event.put("presentationVersion", payload.get("presentationVersion").asLong());
		}
		if (payload.has("generation")) {
			event.put("generation", payload.get("generation").asLong());
		}
		event.set("payload", payload);
		return event;
	}

}
