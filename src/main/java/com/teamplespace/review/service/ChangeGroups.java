package com.teamplespace.review.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 연속 저장을 하나의 변경으로 묶고 규칙에 따라 닫는다
// 이름에 InLock이 붙은 메서드는 프로젝트 잠금을 잡은 트랜잭션 안에서만 호출한다
@Component
public class ChangeGroups {

	public record Group(UUID changeId, boolean open, UUID authorId, UUID sessionId, UUID elementId,
			long generation) {
	}

	// 검토 화면이 읽을 묶음의 상태와 전후 내용
	public record View(UUID changeId, String groupState, long beforeVersion, long afterVersion, JsonNode beforeState,
			JsonNode afterState) {
	}

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final Clock clock;

	private final ProjectEvents events;

	private final ProjectAccess access;

	private final Duration idleTimeout;

	private final Duration maxDuration;

	public ChangeGroups(JdbcClient jdbc, ObjectMapper json, Clock clock, ProjectEvents events, ProjectAccess access,
			@Value("${teamplespace.edit-group.idle-timeout:2s}") Duration idleTimeout,
			@Value("${teamplespace.edit-group.max-duration:30s}") Duration maxDuration) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.events = events;
		this.access = access;
		this.idleTimeout = idleTimeout;
		this.maxDuration = maxDuration;
	}

	// 입력이 끊긴 지 오래됐거나 최대 시간을 넘긴 묶음을 닫는다. 닫힌 시각은 넘긴 순간이다
	public void closeExpiredInLock(UUID projectId) {
		LocalDateTime now = LocalDateTime.now(clock);
		jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id,
				       LEAST(TIMESTAMPADD(MICROSECOND, :idleMicros, last_saved_at),
				             TIMESTAMPADD(MICROSECOND, :maxMicros, opened_at)) AS deadline
				FROM document_changes
				WHERE project_id = UUID_TO_BIN(:project) AND group_state = 'open'
				  AND (last_saved_at <= :idleLimit OR opened_at <= :maxLimit)
				ORDER BY opened_at, id
				""")
			.param("project", projectId.toString())
			.param("idleMicros", idleTimeout.toNanos() / 1000)
			.param("maxMicros", maxDuration.toNanos() / 1000)
			.param("idleLimit", now.minus(idleTimeout))
			.param("maxLimit", now.minus(maxDuration))
			.query((rs, rowNum) -> new Object[] { UUID.fromString(rs.getString("id")),
					rs.getObject("deadline", LocalDateTime.class) })
			.list()
			.forEach(row -> closeInLock(projectId, (UUID) row[0], (LocalDateTime) row[1], null));
	}

	// 다른 작성자가 같은 요소를 고치면 열려 있던 묶음을 닫는다
	public void closeOtherAuthorsInLock(UUID projectId, UUID elementId, UUID authorId) {
		closeMatchingInLock(projectId, """
				AND c.author_id <> UUID_TO_BIN(:author)
				AND EXISTS (SELECT 1 FROM change_targets t WHERE t.change_id = c.id AND t.element_id = UUID_TO_BIN(:element))
				""", authorId, null, elementId);
	}

	// 같은 편집 세션이 다른 요소로 옮겨 가면 이전 묶음을 닫는다
	public void closeOtherTargetsInLock(UUID projectId, Actor actor, UUID elementId) {
		closeMatchingInLock(projectId, """
				AND c.author_id = UUID_TO_BIN(:author) AND c.session_id = UUID_TO_BIN(:session)
				AND NOT EXISTS (SELECT 1 FROM change_targets t WHERE t.change_id = c.id AND t.element_id = UUID_TO_BIN(:element))
				""", actor.userId(), actor.sessionId(), elementId);
	}

	public Optional<Group> find(UUID editGroupId) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(c.id) AS id, c.group_state, BIN_TO_UUID(c.author_id) AS author_id,
				       BIN_TO_UUID(c.session_id) AS session_id, BIN_TO_UUID(t.element_id) AS element_id, c.generation
				FROM document_changes c
				JOIN change_targets t ON t.change_id = c.id AND t.ordinal = 0
				WHERE c.edit_group_id = UUID_TO_BIN(:group)
				""")
			.param("group", editGroupId.toString())
			.query((rs, rowNum) -> new Group(UUID.fromString(rs.getString("id")),
					rs.getString("group_state").equals("open"), UUID.fromString(rs.getString("author_id")),
					UUID.fromString(rs.getString("session_id")), UUID.fromString(rs.getString("element_id")),
					rs.getLong("generation")))
			.optional();
	}

	// 묶음의 첫 저장. 처음 이전 내용을 남기고 변경이 생겼음을 알린다
	public UUID openInLock(UUID projectId, UUID presentationId, long generation, Actor actor, UUID editGroupId,
			String mutationId, UUID slideId, UUID elementId, long beforeVersion, JsonNode beforeState,
			long afterVersion, JsonNode afterState, long presentationVersion) {
		UUID changeId = UUID.randomUUID();
		LocalDateTime now = LocalDateTime.now(clock);
		jdbc.sql("""
				INSERT INTO document_changes (id, project_id, presentation_id, generation, author_id, kind,
				    client_mutation_id, edit_group_id, session_id, group_state, opened_at, last_saved_at, presentation_version)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:presentation), :generation,
				    UUID_TO_BIN(:author), 'element_edit', :mutation, UUID_TO_BIN(:group), UUID_TO_BIN(:session),
				    'open', :now, :now, :presentationVersion)
				""")
			.param("id", changeId.toString())
			.param("project", projectId.toString())
			.param("presentation", presentationId.toString())
			.param("generation", generation)
			.param("author", actor.userId().toString())
			.param("mutation", mutationId)
			.param("group", editGroupId.toString())
			.param("session", actor.sessionId().toString())
			.param("now", now)
			.param("presentationVersion", presentationVersion)
			.update();
		jdbc.sql("""
				INSERT INTO change_targets (id, project_id, change_id, scope, element_id, slide_id, before_version,
				    after_version, before_state, after_state, ordinal)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:change), 'element', UUID_TO_BIN(:element),
				    UUID_TO_BIN(:slide), :beforeVersion, :afterVersion, CAST(:beforeState AS JSON),
				    CAST(:afterState AS JSON), 0)
				""")
			.param("id", UUID.randomUUID().toString())
			.param("project", projectId.toString())
			.param("change", changeId.toString())
			.param("element", elementId.toString())
			.param("slide", slideId.toString())
			.param("beforeVersion", beforeVersion)
			.param("afterVersion", afterVersion)
			.param("beforeState", json.writeValueAsString(beforeState))
			.param("afterState", json.writeValueAsString(afterState))
			.update();
		events.append(projectId, "change.created", actor.userId(), mutationId, changeId, payload(changeId, "open"));
		return changeId;
	}

	// 묶음의 이어지는 저장. 처음 이전 내용은 그대로 두고 마지막 이후 내용만 바꾼다
	public void appendInLock(UUID changeId, long afterVersion, JsonNode afterState, long presentationVersion) {
		jdbc.sql("UPDATE document_changes SET last_saved_at = :now, presentation_version = :presentationVersion"
				+ " WHERE id = UUID_TO_BIN(:change) AND group_state = 'open'")
			.param("now", LocalDateTime.now(clock))
			.param("presentationVersion", presentationVersion)
			.param("change", changeId.toString())
			.update();
		jdbc.sql("UPDATE change_targets SET after_version = :afterVersion, after_state = CAST(:afterState AS JSON)"
				+ " WHERE change_id = UUID_TO_BIN(:change) AND ordinal = 0")
			.param("afterVersion", afterVersion)
			.param("afterState", json.writeValueAsString(afterState))
			.param("change", changeId.toString())
			.update();
	}

	// 묶음을 닫고 한 번만 알린다. 돌려주는 값은 종료 이벤트의 순번이다
	public long closeInLock(UUID projectId, UUID changeId, LocalDateTime closedAt, String mutationId) {
		UUID authorId = jdbc.sql("SELECT BIN_TO_UUID(author_id) FROM document_changes WHERE id = UUID_TO_BIN(:change)")
			.param("change", changeId.toString())
			.query(String.class)
			.optional()
			.map(UUID::fromString)
			.orElseThrow();
		int closed = jdbc.sql("UPDATE document_changes SET group_state = 'closed', closed_at = :closedAt"
				+ " WHERE id = UUID_TO_BIN(:change) AND group_state = 'open'")
			.param("closedAt", closedAt)
			.param("change", changeId.toString())
			.update();
		if (closed == 0) {
			throw new IllegalStateException("이미 닫힌 묶음");
		}
		return events.append(projectId, "change.finalized", authorId, mutationId, changeId,
				payload(changeId, "closed"));
	}

	public LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	// 변경 하나를 읽는다. 읽기 전에 시간이 지난 묶음부터 닫는다
	@Transactional
	public View read(Actor actor, UUID changeId) {
		UUID projectId = jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM document_changes WHERE id = UUID_TO_BIN(:change)")
			.param("change", changeId.toString())
			.query(String.class)
			.optional()
			.map(UUID::fromString)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		access.requireActiveMember(projectId, actor.userId());
		access.lock(projectId);
		closeExpiredInLock(projectId);
		return jdbc.sql("""
				SELECT c.group_state, t.before_version, t.after_version,
				       CAST(t.before_state AS CHAR) AS before_state, CAST(t.after_state AS CHAR) AS after_state
				FROM document_changes c
				JOIN change_targets t ON t.change_id = c.id AND t.ordinal = 0
				WHERE c.id = UUID_TO_BIN(:change)
				""")
			.param("change", changeId.toString())
			.query((rs, rowNum) -> new View(changeId, rs.getString("group_state"), rs.getLong("before_version"),
					rs.getLong("after_version"), json.readTree(rs.getString("before_state")),
					json.readTree(rs.getString("after_state"))))
			.single();
	}

	// 승인 기능이 가장 먼저 호출할 검사. 열린 묶음은 승인할 수 없다
	@Transactional
	public void requireFinalized(Actor actor, UUID changeId) {
		if (!read(actor, changeId).groupState().equals("closed")) {
			throw new ApiException(ErrorCode.CHANGE_NOT_FINALIZED);
		}
	}

	private void closeMatchingInLock(UUID projectId, String condition, UUID authorId, UUID sessionId,
			UUID elementId) {
		LocalDateTime now = LocalDateTime.now(clock);
		var query = jdbc
			.sql("SELECT BIN_TO_UUID(c.id) FROM document_changes c"
					+ " WHERE c.project_id = UUID_TO_BIN(:project) AND c.group_state = 'open' " + condition
					+ " ORDER BY c.opened_at, c.id")
			.param("project", projectId.toString())
			.param("author", authorId.toString())
			.param("element", elementId.toString());
		if (sessionId != null) {
			query = query.param("session", sessionId.toString());
		}
		query.query(String.class).list().forEach(id -> closeInLock(projectId, UUID.fromString(id), now, null));
	}

	private ObjectNode payload(UUID changeId, String groupState) {
		ObjectNode payload = json.createObjectNode();
		payload.put("changeId", changeId.toString());
		payload.put("groupState", groupState);
		return payload;
	}

}
