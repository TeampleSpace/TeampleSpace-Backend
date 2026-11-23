package com.teamplespace.support;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.teamplespace.platform.security.Actor;

// 저장 검증용 사용자와 문서
public class DocumentFixture {

	public final UUID projectId = UUID.randomUUID();

	public final UUID presentationId = UUID.randomUUID();

	public final UUID slideId = UUID.randomUUID();

	public final UUID elementA = UUID.randomUUID();

	public final UUID elementB = UUID.randomUUID();

	public final Actor userA = new Actor(UUID.randomUUID(), UUID.randomUUID());

	public final Actor userB = new Actor(UUID.randomUUID(), UUID.randomUUID());

	private final JdbcClient jdbc;

	public DocumentFixture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void reset() {
		jdbc.sql("UPDATE materials SET current_revision_id = NULL, source_file_id = NULL").update();
		for (String table : List.of("design_settings", "outline_sources", "outline_items", "outlines",
				"material_comments", "material_notes", "material_chunks", "material_revisions", "materials", "uploads",
				"file_objects", "job_attempts", "jobs", "project_invitations", "idempotency_records", "change_targets", "document_changes", "project_events",
				"slide_elements", "slides", "presentations", "project_memberships", "projects")) {
			jdbc.sql("DELETE FROM " + table).update();
		}
		for (Actor user : List.of(userA, userB)) {
			jdbc.sql("INSERT INTO users(id,onboarding_status) VALUES(UUID_TO_BIN(?),'completed') ON DUPLICATE KEY UPDATE status='active'")
				.param(user.userId().toString()).update();
		}
		// 소유자가 있어야 참여 확인을 통과한다
		jdbc.sql("INSERT INTO projects (id, name, owner_user_id) VALUES (UUID_TO_BIN(?), '검증용 프로젝트', UUID_TO_BIN(?))")
			.params(projectId.toString(), userA.userId().toString())
			.update();
		for (Actor user : List.of(userA, userB)) {
			jdbc.sql("INSERT INTO project_memberships (project_id, user_id, state)"
					+ " VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'active')")
				.params(projectId.toString(), user.userId().toString())
				.update();
		}
		jdbc.sql("INSERT INTO presentations (id, project_id, schema_version, canvas_width, canvas_height, canvas_unit)"
				+ " VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 1, 1280, 720, 'px')")
			.params(presentationId.toString(), projectId.toString())
			.update();
		jdbc.sql("INSERT INTO slides (id, project_id, presentation_id, generation, position)"
				+ " VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 1, 0)")
			.params(slideId.toString(), projectId.toString(), presentationId.toString())
			.update();
		for (UUID element : List.of(elementA, elementB)) {
			jdbc.sql("""
					INSERT INTO slide_elements (id, project_id, slide_id, type, x, y, width, height, content, style)
					VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 'text', 80, 64, 620, 236.25,
					        JSON_OBJECT('text', '처음 내용'),
					        JSON_OBJECT('fontFamily', 'Pretendard', 'fontSize', 22, 'fontWeight', 400,
					                    'color', '#1F2933', 'align', 'left', 'lineHeight', 1.6))
					""").params(element.toString(), projectId.toString(), slideId.toString()).update();
		}
	}

	public long version(String table, UUID id) {
		return jdbc.sql("SELECT version FROM " + table + " WHERE id = UUID_TO_BIN(?)")
			.param(id.toString())
			.query(Long.class)
			.single();
	}

	public String text(UUID element) {
		return jdbc.sql("SELECT content ->> '$.text' FROM slide_elements WHERE id = UUID_TO_BIN(?)")
			.param(element.toString())
			.query(String.class)
			.single();
	}

	public long eventSeq() {
		return jdbc.sql("SELECT event_seq FROM projects WHERE id = UUID_TO_BIN(?)")
			.param(projectId.toString())
			.query(Long.class)
			.single();
	}

	public int count(String table) {
		return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
	}

	public int events(String type) {
		return jdbc.sql("SELECT COUNT(*) FROM project_events WHERE type = ?").param(type).query(Integer.class).single();
	}

}
