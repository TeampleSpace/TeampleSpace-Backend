package com.teamplespace.presentation.service;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 편집 화면을 확인할 예제 문서를 만든다. 개발과 시험 환경에서만 켠다
// 문서 생성 기능이 생기면 이 경로를 없앤다
@Service
public class SamplePresentations {

	private static final String NAVY = "#1F4E79";

	private static final String INK = "#1F2933";

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final ProjectAccess access;

	private final boolean enabled;

	public SamplePresentations(JdbcClient jdbc, ObjectMapper json, ProjectAccess access,
			@Value("${teamplespace.editor.sample-presentation-enabled:false}") boolean enabled) {
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.enabled = enabled;
	}

	// 이미 문서가 있으면 그대로 둔다
	@Transactional
	public void create(UUID actor, UUID projectId) {
		if (!enabled) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		access.requireMember(projectId, actor);
		access.lock(projectId);
		int existing = jdbc.sql("SELECT COUNT(*) FROM presentations WHERE project_id = UUID_TO_BIN(:project)")
			.param("project", projectId.toString())
			.query(Integer.class)
			.single();
		if (existing > 0) {
			return;
		}
		UUID presentation = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO presentations (id, project_id, schema_version, canvas_width, canvas_height, canvas_unit)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), 1, 1280, 720, 'px')
				""").param("id", presentation.toString()).param("project", projectId.toString()).update();

		UUID cover = slide(projectId, presentation, 0, "title");
		shape(projectId, cover, 0, 0, 0, 1280, 220, "rect", NAVY, null);
		text(projectId, cover, 1, 80, 64, 1120, 90, "팀플 발표 자료를 함께 만드는 방법", 56, 700, "#FFFFFF");
		text(projectId, cover, 2, 80, 280, 1120, 60, "편집과 저장을 확인하는 예제 문서", 28, 400, INK);

		UUID roles = slide(projectId, presentation, 1, "content");
		text(projectId, roles, 0, 80, 64, 1120, 70, "역할 나누기", 40, 700, NAVY);
		text(projectId, roles, 1, 80, 180, 680, 320, "자료 조사\n발표 구성\n슬라이드 제작", 26, 400, INK);
		shape(projectId, roles, 2, 880, 200, 260, 260, "ellipse", "#E8F1FA", NAVY);

		UUID schedule = slide(projectId, presentation, 2, "content");
		text(projectId, schedule, 0, 80, 64, 1120, 70, "일정", 40, 700, NAVY);
		shape(projectId, schedule, 1, 80, 180, 1120, 150, "rect", "#F3F5F7", null);
		text(projectId, schedule, 2, 112, 212, 1056, 90, "1주차 자료 모으기, 2주차 구성 확정, 3주차 발표 연습", 26, 400, INK);
	}

	private UUID slide(UUID projectId, UUID presentation, int position, String layout) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO slides (id, project_id, presentation_id, generation, position, layout_id)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:presentation), 1, :position, :layout)
				""")
			.param("id", id.toString())
			.param("project", projectId.toString())
			.param("presentation", presentation.toString())
			.param("position", position)
			.param("layout", layout)
			.update();
		return id;
	}

	private void text(UUID projectId, UUID slide, int z, int x, int y, int width, int height, String text,
			int fontSize, int fontWeight, String color) {
		ObjectNode style = json.createObjectNode();
		style.put("fontFamily", "Pretendard");
		style.put("fontSize", fontSize);
		style.put("fontWeight", fontWeight);
		style.put("color", color);
		style.put("align", "left");
		style.put("lineHeight", 1.4);
		element(projectId, slide, "text", z, x, y, width, height, json.createObjectNode().put("text", text), style);
	}

	private void shape(UUID projectId, UUID slide, int z, int x, int y, int width, int height, String shape,
			String fill, String stroke) {
		ObjectNode style = json.createObjectNode();
		style.put("fill", fill);
		style.put("stroke", stroke);
		style.put("strokeWidth", stroke == null ? 0 : 2);
		element(projectId, slide, "shape", z, x, y, width, height, json.createObjectNode().put("shape", shape), style);
	}

	private void element(UUID projectId, UUID slide, String type, int z, int x, int y, int width, int height,
			JsonNode content, JsonNode style) {
		jdbc.sql("""
				INSERT INTO slide_elements (id, project_id, slide_id, type, x, y, width, height, z_order, content, style)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:slide), :type, :x, :y, :width, :height, :z,
				        CAST(:content AS JSON), CAST(:style AS JSON))
				""")
			.param("id", UUID.randomUUID().toString())
			.param("project", projectId.toString())
			.param("slide", slide.toString())
			.param("type", type)
			.param("x", x)
			.param("y", y)
			.param("width", width)
			.param("height", height)
			.param("z", z)
			.param("content", json.writeValueAsString(content))
			.param("style", json.writeValueAsString(style))
			.update();
	}

}
