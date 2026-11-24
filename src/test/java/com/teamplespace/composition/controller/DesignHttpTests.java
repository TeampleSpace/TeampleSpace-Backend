package com.teamplespace.composition.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

class DesignHttpTests extends HttpIntegrationSupport {

	@Autowired
	DataSource dataSource;

	@Test
	void galleryMigrationPreservesExistingTemplateSelection() throws SQLException {
		try (var connection = dataSource.getConnection()) {
			var session = JdbcClient.create(new SingleConnectionDataSource(connection, true));
			try {
				// 임시 테이블에 이전 카탈로그와 저장된 디자인을 준비한다
				session.sql("CREATE TEMPORARY TABLE upgrade_templates LIKE templates").update();
				session.sql("INSERT INTO upgrade_templates SELECT * FROM templates WHERE display_position < 7").update();
				session.sql("CREATE TEMPORARY TABLE upgrade_design_settings LIKE design_settings").update();
				session.sql("CREATE TEMPORARY TABLE templates LIKE upgrade_templates").update();
				session.sql("CREATE TEMPORARY TABLE design_settings LIKE upgrade_design_settings").update();
				session.sql("INSERT INTO templates SELECT * FROM upgrade_templates").update();
				session.sql("""
					INSERT INTO design_settings (project_id, mode, template_id, version, updated_by)
					SELECT UUID_TO_BIN(UUID()), 'template', id, 4, UUID_TO_BIN(UUID())
					FROM templates WHERE template_key = 'blue-focus' AND release_no = 1
					""").update();
				String savedSelection = """
					SELECT BIN_TO_UUID(template_id) AS template_id, template_key, release_no, version
					FROM design_settings JOIN templates ON design_settings.template_id = templates.id
					""";
				var before = session.sql(savedSelection).query().singleRow();
				assertThat(session.sql("SELECT COUNT(*) FROM templates").query(Integer.class).single()).isEqualTo(7);

				ScriptUtils.executeSqlScript(connection,
						new ClassPathResource("db/migration/V12__add_figma_gallery_templates.sql"));

				assertThat(session.sql(savedSelection).query().singleRow()).isEqualTo(before);
				assertThat(session.sql("SELECT template_key FROM templates ORDER BY display_position")
						.query(String.class).list()).containsExactly("clean-report", "blue-focus", "editorial",
							"pitch-dark", "warm-story", "grid-analysis", "bold-cover", "orange-pitch", "green-insight",
							"mono-editorial", "lilac-studio", "data-briefing", "warm-seminar", "team-story");
			} finally {
				session.sql("DROP TEMPORARY TABLE IF EXISTS templates, design_settings, upgrade_templates, upgrade_design_settings")
					.update();
			}
		}
	}

	@Test
	void catalogAndBothModesAreValidatedAndRemembered() throws Exception {
		Browser owner = signup("디자인 소유자");
		Browser member = signup("디자인 팀원");
		Browser outsider = signup("디자인 외부인");
		String project = createProject(owner, "디자인 설정");
		join(owner, member, project);
		String path = "/projects/" + project + "/design-settings";
		JsonNode catalog = owner.get("/templates");
		assertSchema("stage-3-design.schema.json#/$defs/TemplateCatalog", catalog);
		JsonNode templates = catalog.get("items");
		var keys = new ArrayList<String>();
		for (JsonNode template : templates) {
			keys.add(template.get("key").asString());
		}
		assertThat(keys).containsExactly("clean-report", "blue-focus", "editorial", "pitch-dark", "warm-story",
				"grid-analysis", "bold-cover", "orange-pitch", "green-insight", "mono-editorial",
				"lilac-studio", "data-briefing", "warm-seminar", "team-story",
				"burgundy-editorial", "tech-pitch", "open-campus", "field-notes");
		assertThat(templates.get(0).get("name").asString()).isEqualTo("클린 리포트");
		assertThat(catalog.get("allowedFonts").get(0).asString()).isEqualTo("Pretendard");

		JsonNode unset = member.get(path);
		assertSchema("api.schema.json#/$defs/DesignSettings", unset);
		assertThat(unset.get("version").asLong()).isEqualTo(1);
		assertThat(unset.get("mode").asString()).isEqualTo("unset");
		outsider.request("GET", path, null, 404);
		Map<String, Object> template = template(1, templates.get(7));
		String key = UUID.randomUUID().toString();
		outsider.request("PUT", path, template, 404, key);
		JsonNode saved = member.request("PUT", path, template, 200, key);
		assertSchema("api.schema.json#/$defs/DesignSettings", saved);
		assertThat(saved.get("version").asLong()).isEqualTo(2);
		assertThat(saved.get("custom").isNull()).isTrue();
		assertThat(owner.get(path)).isEqualTo(saved);
		var invalid = Map.of("expectedVersion", 2, "mode", "template",
				"templateId", saved.get("templateId").asString(), "templateVersion", 2);
		owner.request("PUT", path, invalid, 422, UUID.randomUUID().toString());
		var invalidFont = Map.of("expectedVersion", 2, "mode", "custom",
				"custom", Map.of("name", "직접 입력", "font", "Unknown", "primaryColor", "#2864E8"));
		owner.request("PUT", path, invalidFont, 422, UUID.randomUUID().toString());
		assertThat(owner.get(path)).isEqualTo(saved);

		JsonNode custom = owner.request("PUT", path, custom(2), 200, UUID.randomUUID().toString());
		assertSchema("api.schema.json#/$defs/DesignSettings", custom);
		assertThat(custom.get("version").asLong()).isEqualTo(3);
		assertThat(custom.get("templateId").isNull()).isTrue();
		assertThat(custom.get("templateVersion").isNull()).isTrue();
		assertThat(custom.get("custom").get("primaryColor").asString()).isEqualTo("#2864E8");
		assertThat(member.get(path)).isEqualTo(custom);
		assertThat(member.request("PUT", path, template, 200, key)).isEqualTo(saved);
		assertThat(member.request("PUT", path, custom(2), 409, key).get("error").get("code").asString())
			.isEqualTo("IDEMPOTENCY_KEY_REUSED");
		removeMember(owner, project, member.userId);
		member.request("GET", path, null, 403);
		member.request("PUT", path, template, 403, key);
		assertThat(owner.get(path)).isEqualTo(custom);
		JsonNode newTemplate = templates.get(3);
		JsonNode selected = owner.request("PUT", path, template(3, newTemplate), 200, UUID.randomUUID().toString());
		assertThat(selected.get("templateId").asString()).isEqualTo(newTemplate.get("id").asString());
		assertThat(owner.get(path)).isEqualTo(selected);
		long designVersion = selected.get("version").asLong();
		for (int position = 14; position < templates.size(); position++) {
			JsonNode studio = templates.get(position);
			JsonNode updated = owner.request("PUT", path, template(designVersion, studio), 200, UUID.randomUUID().toString());
			assertThat(updated.get("templateId")).isEqualTo(studio.get("id"));
			assertThat(updated.get("templateVersion")).isEqualTo(studio.get("version"));
			assertThat(owner.get(path)).isEqualTo(updated);
			designVersion = updated.get("version").asLong();
		}
	}

	@Test
	void sameDesignVersionHasOneWinnerAndOneConflict() throws Exception {
		Browser owner = signup("디자인 작성자");
		Browser member = signup("디자인 공동 작성자");
		String project = createProject(owner, "디자인 동시 저장");
		join(owner, member, project);
		String path = "/projects/" + project + "/design-settings";
		assertThat(owner.get(path)).isEqualTo(member.get(path));
		JsonNode choice = owner.get("/templates").get("items").get(1);
		var payloadA = template(1, choice);
		var payloadB = custom(1);
		var go = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(() -> raw(owner, path, payloadA, go));
			var b = pool.submit(() -> raw(member, path, payloadB, go));
			go.countDown();
			var first = a.get(20, TimeUnit.SECONDS);
			var second = b.get(20, TimeUnit.SECONDS);
			assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 409);
			var success = json.readTree(first.statusCode() == 200 ? first.body() : second.body());
			var conflict = json.readTree(first.statusCode() == 409 ? first.body() : second.body());
			assertThat(success.get("version").asLong()).isEqualTo(2);
			assertThat(conflict.get("error").get("currentVersions").get("designVersion").asLong()).isEqualTo(2);
			assertThat(owner.get(path)).isEqualTo(success);
		}
	}

	private java.net.http.HttpResponse<byte[]> raw(Browser browser, String path, Map<String, Object> body,
			CountDownLatch go) throws Exception {
		String csrf = browser.bootstrap().get("csrfToken").asString();
		assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
		return browser.raw("PUT", "/v1" + path, json.writeValueAsBytes(body),
				Map.of("Origin", "http://localhost:5174", "Content-Type", "application/json",
					"X-CSRF-Token", csrf, "Idempotency-Key", UUID.randomUUID().toString()));
	}

	private Map<String, Object> template(long version, JsonNode choice) {
		return Map.of("mode", "template", "expectedVersion", version, "templateId", choice.get("id").asString(),
				"templateVersion", choice.get("version").asLong());
	}

	private Map<String, Object> custom(long version) {
		return Map.of("mode", "custom", "expectedVersion", version, "custom",
				Map.of("name", "팀 디자인", "font", "Pretendard", "primaryColor", "#2864e8", "guidelines", "근거를 표기"));
	}

	private void assertSchema(String name, JsonNode body) {
		assertThat(ContractSchemas.validate(name, json.writeValueAsString(body))).isEmpty();
	}
}
