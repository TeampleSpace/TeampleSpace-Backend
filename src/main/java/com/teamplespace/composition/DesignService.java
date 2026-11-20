package com.teamplespace.composition;

import static com.teamplespace.composition.OutlineInput.invalid;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.collaboration.application.ProjectEvents;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.workspace.application.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DesignService {

	public record Template(UUID id, String key, long version, String name, long schemaVersion,
			JsonNode preview, JsonNode fonts, JsonNode layouts) {
	}

	public record Catalog(List<Template> items, List<String> allowedFonts, List<String> allowedLayouts,
			boolean projectFontsEnabled) {
	}

	public record Settings(long version, String mode, UUID templateId, Long templateVersion,
			JsonNode custom, Instant updatedAt) {
	}

	record Result(int status, JsonNode body) {
	}

	private record Selection(String mode, UUID templateId, JsonNode custom) {
	}

	private static final List<String> FONTS = List.of("Pretendard");
	private static final List<String> LAYOUTS = List.of("title", "content", "summary");
	private static final Set<String> TEMPLATE_FIELDS = Set.of("expectedVersion", "mode", "templateId", "templateVersion");
	private static final Set<String> CUSTOM_FIELDS = Set.of("expectedVersion", "mode", "custom");
	private static final Set<String> CUSTOM_PROPERTIES = Set.of("name", "font", "fontAssetId", "primaryColor", "guidelines");
	private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final ProjectAccess access;
	private final IdempotencyStore idempotency;
	private final ProjectEvents events;
	private final ProjectFontService fonts;

	public DesignService(JdbcClient jdbc, ObjectMapper json, ProjectAccess access,
			IdempotencyStore idempotency, ProjectEvents events, ProjectFontService fonts) {
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.idempotency = idempotency;
		this.events = events;
		this.fonts = fonts;
	}

	public Catalog catalog() {
		var templates = jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id, template_key, release_no, name, schema_version, preview, fonts, layouts
				FROM templates WHERE state = 'published'
				ORDER BY display_position, template_key, release_no DESC
				""").query((rs, index) -> new Template(UUID.fromString(rs.getString("id")), rs.getString("template_key"),
					rs.getLong("release_no"), rs.getString("name"), rs.getLong("schema_version"),
					json.readTree(rs.getString("preview")), json.readTree(rs.getString("fonts")),
					json.readTree(rs.getString("layouts")))).list();
		return new Catalog(templates, FONTS, LAYOUTS, true);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Settings get(UUID actor, UUID projectId) {
		access.requireMember(projectId, actor);
		return read(projectId);
	}

	@Transactional
	public Result save(UUID actor, UUID projectId, String keyValue, JsonNode body) {
		access.lock(projectId);
		access.requireMember(projectId, actor);
		if (keyValue == null || keyValue.isBlank() || keyValue.length() > 128) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		var key = IdempotencyStore.Key.of(actor, "PUT", "/v1/projects/" + projectId + "/design-settings", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
		if (stored.isPresent()) {
			return new Result(stored.get().status(), json.readTree(stored.get().body()));
		}
		long expected = OutlineInput.expectedVersion(body);
		Settings current = read(projectId);
		if (expected != current.version()) {
			return finish(key, projectId, 409, json.createObjectNode().put("designVersion", current.version()));
		}
		if (current.version() == OutlineInput.MAX_VERSION) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		Selection selection = selection(projectId, body);
		long version = current.version() + 1;
		var values = jdbc.sql("""
				INSERT INTO design_settings (project_id, mode, template_id, custom, version, updated_by)
				VALUES (UUID_TO_BIN(:project), :mode, UUID_TO_BIN(:template), CAST(:custom AS JSON), :version, UUID_TO_BIN(:actor))
				AS next_settings
				ON DUPLICATE KEY UPDATE mode = next_settings.mode, template_id = next_settings.template_id,
				    custom = next_settings.custom, version = next_settings.version, updated_by = next_settings.updated_by
				""");
		values.param("project", projectId.toString()).param("mode", selection.mode())
			.param("template", selection.templateId() == null ? null : selection.templateId().toString())
			.param("custom", selection.custom() == null ? null : json.writeValueAsString(selection.custom()))
			.param("version", version).param("actor", actor.toString()).update();
		events.append(projectId, "design.updated", actor, null, projectId,
				json.createObjectNode().put("designVersion", version));
		return finish(key, projectId, 200, json.valueToTree(read(projectId)));
	}

	private Settings read(UUID projectId) {
		return jdbc.sql("""
				SELECT COALESCE(d.version, 1) AS version, COALESCE(d.mode, 'unset') AS mode,
				       BIN_TO_UUID(d.template_id) AS template_id, t.release_no, d.custom,
				       COALESCE(d.updated_at, p.created_at) AS updated_at
				FROM projects p
				LEFT JOIN design_settings d ON p.id = d.project_id
				LEFT JOIN templates t ON d.template_id = t.id
				WHERE p.id = UUID_TO_BIN(?)
				""").param(projectId.toString()).query((rs, index) -> {
					String template = rs.getString("template_id");
					String custom = rs.getString("custom");
					return new Settings(rs.getLong("version"), rs.getString("mode"),
							template == null ? null : UUID.fromString(template),
							template == null ? null : rs.getLong("release_no"),
							custom == null ? null : json.readTree(custom), rs.getTimestamp("updated_at").toInstant());
				}).single();
	}

	private Selection selection(UUID projectId, JsonNode body) {
		String mode = body.path("mode").asString("");
		return switch (mode) {
			case "template" -> template(body);
			case "custom" -> custom(projectId, body);
			default -> throw invalid("mode", "템플릿 또는 직접 입력을 선택해 주세요.");
		};
	}

	private Selection template(JsonNode body) {
		OutlineInput.checkFields(body, TEMPLATE_FIELDS, "designSettings");
		UUID templateId = OutlineInput.uuid(body.path("templateId"), "templateId");
		JsonNode version = body.path("templateVersion");
		if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 1) {
			throw invalid("templateVersion", "템플릿 버전을 확인해 주세요.");
		}
		boolean published = jdbc.sql("""
				SELECT 1 FROM templates WHERE id = UUID_TO_BIN(?) AND release_no = ? AND state = 'published'
				""").params(templateId.toString(), version.asLong()).query(Integer.class).optional().isPresent();
		if (!published) {
			throw invalid("templateVersion", "현재 선택할 수 있는 템플릿 버전을 선택해 주세요.");
		}
		return new Selection("template", templateId, null);
	}

	private Selection custom(UUID projectId, JsonNode body) {
		OutlineInput.checkFields(body, CUSTOM_FIELDS, "designSettings");
		JsonNode source = body.path("custom");
		OutlineInput.checkFields(source, CUSTOM_PROPERTIES, "custom");
		String name = OutlineInput.text(source.path("name"), "custom.name", 80).strip();
		if (name.isEmpty()) {
			throw invalid("custom.name", "템플릿 이름을 입력해 주세요.");
		}
		String font = source.path("font").asString("");
		if (!FONTS.contains(font)) {
			throw invalid("custom.font", "목록에서 쓸 수 있는 글꼴을 선택해 주세요.");
		}
		String color = source.path("primaryColor").asString("");
		if (!HEX_COLOR.matcher(color).matches()) {
			throw invalid("custom.primaryColor", "대표 색상을 6자리 HEX로 입력해 주세요.");
		}
		String guidelines = source.has("guidelines") ? OutlineInput.text(source.get("guidelines"), "custom.guidelines", 2_000) : "";
		var settings = json.createObjectNode().put("name", name).put("font", font)
				.put("primaryColor", color.toUpperCase(Locale.ROOT)).put("guidelines", guidelines);
		if (source.has("fontAssetId")) {
			UUID fontId = OutlineInput.uuid(source.get("fontAssetId"), "custom.fontAssetId");
			if (!fonts.contains(projectId, fontId)) {
				throw invalid("custom.fontAssetId", "프로젝트에서 등록한 글꼴을 선택해 주세요.");
			}
			settings.put("fontAssetId", fontId.toString());
		}
		return new Selection("custom", null, settings);
	}

	private Result finish(IdempotencyStore.Key key, UUID projectId, int status, JsonNode body) {
		idempotency.complete(key, status == 200, status, json.writeValueAsString(body), projectId);
		return new Result(status, body);
	}
}
