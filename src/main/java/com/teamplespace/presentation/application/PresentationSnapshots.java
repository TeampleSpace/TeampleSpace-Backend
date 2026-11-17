package com.teamplespace.presentation.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.collaboration.application.ProjectEvents;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.presentation.application.ElementPatchRequest.Geometry;
import com.teamplespace.workspace.application.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// 문서와 이벤트 커서를 같은 DB 시점에서 읽는다
// 팀원, 디자인, 작업, 자료 요약은 해당 기능을 만들 때 동기화 스냅샷에 더한다
@Component
public class PresentationSnapshots {

	public record Snapshot(String eventCursor, JsonNode presentation) {
	}

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final ProjectAccess access;

	private final ProjectEvents events;

	private final TransactionTemplate consistentRead;

	public PresentationSnapshots(JdbcClient jdbc, ObjectMapper json, ProjectAccess access, ProjectEvents events,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.events = events;
		// 첫 조회 시점의 스냅샷으로 커서와 문서를 함께 읽는다
		this.consistentRead = new TransactionTemplate(transactionManager);
		this.consistentRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.consistentRead.setReadOnly(true);
	}

	public Snapshot read(Actor actor, UUID projectId) {
		return consistentRead.execute(status -> {
			access.requireActiveMember(projectId, actor.userId());
			long cursor = events.currentSeq(projectId);
			return new Snapshot(Long.toString(cursor), presentation(projectId));
		});
	}

	// contracts/schemas/document.schema.json의 Presentation. 아직 생성 전이면 null
	private JsonNode presentation(UUID projectId) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id, schema_version, generation, version, order_version,
				       canvas_width, canvas_height, canvas_unit
				FROM presentations
				WHERE project_id = UUID_TO_BIN(:project) AND state = 'ready'
				""").param("project", projectId.toString()).query((rs, rowNum) -> {
			String presentationId = rs.getString("id");
			long generation = rs.getLong("generation");
			ObjectNode presentation = json.createObjectNode();
			presentation.put("id", presentationId);
			presentation.put("projectId", projectId.toString());
			presentation.put("schemaVersion", rs.getInt("schema_version"));
			presentation.put("generation", generation);
			presentation.put("version", rs.getLong("version"));
			presentation.put("orderVersion", rs.getLong("order_version"));
			ObjectNode canvas = presentation.putObject("canvas");
			canvas.put("width", ElementJson.plain(rs.getBigDecimal("canvas_width")));
			canvas.put("height", ElementJson.plain(rs.getBigDecimal("canvas_height")));
			canvas.put("unit", rs.getString("canvas_unit"));
			Map<String, ObjectNode> slides = slides(presentationId, generation);
			ArrayNode slideIds = presentation.putArray("slideIds");
			slides.keySet().forEach(slideIds::add);
			presentation.putArray("slides").addAll(slides.values());
			return (JsonNode) presentation;
		}).optional().orElse(null);
	}

	private Map<String, ObjectNode> slides(String presentationId, long generation) {
		Map<String, ObjectNode> slides = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id, version, layout_id
				FROM slides
				WHERE presentation_id = UUID_TO_BIN(:presentation) AND generation = :generation AND deleted_at IS NULL
				ORDER BY position
				""").param("presentation", presentationId).param("generation", generation).query(rs -> {
			ObjectNode slide = json.createObjectNode();
			slide.put("id", rs.getString("id"));
			slide.put("presentationId", presentationId);
			slide.put("generation", generation);
			slide.put("version", rs.getLong("version"));
			slide.put("layoutId", rs.getString("layout_id"));
			slide.putArray("elementIds");
			slide.putArray("elements");
			slides.put(rs.getString("id"), slide);
		});
		List<ObjectNode> elements = new ArrayList<>();
		jdbc.sql("""
				SELECT BIN_TO_UUID(e.id) AS id, BIN_TO_UUID(e.slide_id) AS slide_id, e.type, e.version,
				       e.x, e.y, e.width, e.height, e.rotation, e.z_order,
				       CAST(e.content AS CHAR) AS content, CAST(e.style AS CHAR) AS style
				FROM slide_elements e
				JOIN slides s ON s.id = e.slide_id
				WHERE s.presentation_id = UUID_TO_BIN(:presentation) AND s.generation = :generation
				  AND s.deleted_at IS NULL AND e.deleted_at IS NULL
				ORDER BY e.z_order, e.id
				""")
			.param("presentation", presentationId)
			.param("generation", generation)
			.query(rs -> {
				elements.add(ElementJson.of(json, UUID.fromString(rs.getString("id")),
						UUID.fromString(rs.getString("slide_id")), rs.getString("type"), rs.getLong("version"),
						new Geometry(rs.getBigDecimal("x"), rs.getBigDecimal("y"), rs.getBigDecimal("width"),
								rs.getBigDecimal("height"), rs.getBigDecimal("rotation"), rs.getInt("z_order")),
						json.readTree(rs.getString("content")), json.readTree(rs.getString("style"))));
			});
		for (ObjectNode element : elements) {
			ObjectNode slide = slides.get(element.get("slideId").asString());
			((ArrayNode) slide.get("elementIds")).add(element.get("id").asString());
			((ArrayNode) slide.get("elements")).add(element);
		}
		return slides;
	}

}
