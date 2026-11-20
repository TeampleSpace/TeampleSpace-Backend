package com.teamplespace.composition.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.teamplespace.composition.service.ProjectFontTests;
import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

class ProjectFontHttpTests extends HttpIntegrationSupport {

	@Test
	void uploadIsSharedInProjectAndSavedWithCustomDesign() throws Exception {
		Browser owner = signup("글꼴 등록자");
		Browser member = signup("글꼴 팀원");
		Browser outsider = signup("글꼴 외부인");
		String project = createProject(owner, "공유 글꼴");
		String otherProject = createProject(outsider, "별도 글꼴");
		join(owner, member, project);
		String path = "/projects/" + project + "/design-fonts";
		String settings = "/projects/" + project + "/design-settings";
		byte[] bytes = ProjectFontTests.directory(false);
		assertThat(owner.get("/templates").get("projectFontsEnabled").asBoolean()).isTrue();

		JsonNode invalid = upload(member, path, "broken.ttf", new byte[]{0, 1}, 422);
		assertThat(invalid.get("error").get("code").asString()).isEqualTo("VALIDATION_FAILED");
		upload(outsider, path, "project.ttf", bytes, 404);
		assertThat(owner.get(path).get("items").size()).isZero();

		JsonNode font = upload(member, path, "project.ttf", bytes, 200);
		assertSchema("stage-3-design.schema.json#/$defs/ProjectFont", font);
		assertThat(font.get("format").asString()).isEqualTo("ttf");
		assertThat(font.get("sizeBytes").asInt()).isEqualTo(bytes.length);
		assertThat(upload(owner, path, "duplicate.ttf", bytes, 200)).isEqualTo(font);
		JsonNode list = owner.get(path);
		assertSchema("stage-3-design.schema.json#/$defs/ProjectFontList", list);
		assertThat(list.get("items").size()).isEqualTo(1);
		assertThat(list.get("items").get(0)).isEqualTo(font);

		String asset = font.get("id").asString();
		String url = font.get("contentUrl").asString();
		var content = owner.raw("GET", url, null, Map.of());
		assertThat(content.statusCode()).isEqualTo(200);
		assertThat(content.headers().firstValue("Content-Type")).hasValue("font/ttf");
		assertThat(content.headers().firstValue("Cache-Control")).hasValue("private, no-store");
		assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
		assertThat(content.body()).isEqualTo(bytes);
		assertThat(outsider.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(404);

		JsonNode saved = save(member, settings, custom(1, asset), 200);
		assertSchema("api.schema.json#/$defs/DesignSettings", saved);
		assertThat(saved.get("custom").get("fontAssetId").asString()).isEqualTo(asset);
		assertThat(owner.get(settings)).isEqualTo(saved);
		String otherSettings = "/projects/" + otherProject + "/design-settings";
		JsonNode denied = save(outsider, otherSettings, custom(1, asset), 422);
		assertThat(denied.get("error").get("fieldErrors").get(0).get("field").asString())
				.isEqualTo("custom.fontAssetId");

		removeMember(owner, project, member.userId);
		assertThat(member.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(403);
		upload(member, path, "other.ttf", bytes, 403);
		JsonNode builtIn = save(owner, settings, custom(2, null), 200);
		assertThat(builtIn.get("custom").has("fontAssetId")).isFalse();
	}

	private JsonNode upload(Browser browser, String path, String name, byte[] bytes, int expected) throws Exception {
		String csrf = browser.bootstrap().get("csrfToken").asString();
		var response = browser.raw("POST", "/v1" + path + "?fileName=" + name, bytes,
				Map.of("Origin", "http://localhost:5174", "X-CSRF-Token", csrf,
						"Content-Type", "application/octet-stream"));
		assertThat(response.statusCode()).isEqualTo(expected);
		return json.readTree(response.body());
	}

	private JsonNode save(Browser browser, String path, Object payload, int expected) throws Exception {
		return browser.request("PUT", path, payload, expected, UUID.randomUUID().toString());
	}

	private static Map<String, ?> custom(long version, String asset) {
		var font = new java.util.LinkedHashMap<>(Map.of("name", "팀 글꼴", "font", "Pretendard", "primaryColor", "#2864E8"));
		if (asset != null) font.put("fontAssetId", asset);
		return Map.of("expectedVersion", version, "mode", "custom", "custom", font);
	}

	private void assertSchema(String name, JsonNode body) {
		assertThat(ContractSchemas.validate(name, json.writeValueAsString(body))).isEmpty();
	}
}
