package com.teamplespace.collaboration.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

// AI 작업과 디자인 변경을 다른 팀원이 승인해야 실행하는 흐름을 실제 HTTP 요청과 MySQL로 확인
class ChangeRequestHttpTests extends HttpIntegrationSupport {

	private void assertMatches(String schema, JsonNode value) {
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/" + schema, json.writeValueAsString(value)))
			.as(value.toString()).isEmpty();
	}

	private Map<String, Object> design(long expectedVersion, JsonNode template) {
		return Map.of("expectedVersion", expectedVersion, "mode", "template", "templateId", template.get("id").asString(),
				"templateVersion", template.get("version").asLong());
	}

	private JsonNode request(Browser browser, String projectId, String type, Object payload, int status) throws Exception {
		return browser.request("POST", "/projects/" + projectId + "/requests", Map.of("type", type, "payload", payload), status,
				UUID.randomUUID().toString());
	}

	@Test
	void designChangeRunsOnlyAfterAnotherMemberApproves() throws Exception {
		Browser owner = signup("승인 소유자");
		Browser member = signup("승인 요청자");
		Browser outsider = signup("승인 외부인");
		String projectId = createProject(owner, "승인 흐름 " + UUID.randomUUID());
		join(owner, member, projectId);
		JsonNode templates = owner.get("/templates").get("items");
		String path = "/projects/" + projectId + "/requests";

		String key = UUID.randomUUID().toString();
		Map<String, Object> body = Map.of("type", "design_change", "payload", design(1, templates.get(0)));
		JsonNode created = member.request("POST", path, body, 201, key);
		assertMatches("ChangeRequest", created);
		assertThat(created.get("state").asString()).isEqualTo("pending");
		assertThat(created.get("requestedBy").get("name").asString()).isEqualTo("승인 요청자");
		// 같은 키로 다시 보내면 같은 요청이고, 같은 종류를 또 요청하면 기다리게 한다
		assertThat(member.request("POST", path, body, 201, key)).isEqualTo(created);
		assertThat(request(owner, projectId, "design_change", design(1, templates.get(1)), 409).get("error").get("code")
			.asString()).isEqualTo("REQUEST_IN_PROGRESS");
		member.request("POST", path, body, 400, null);
		request(member, projectId, "unknown", Map.of(), 422);
		outsider.request("POST", path, body, 404, UUID.randomUUID().toString());
		outsider.request("GET", path, null, 404);

		// 아직 디자인은 바뀌지 않았고 요청자는 스스로 승인할 수 없다
		assertThat(owner.get("/projects/" + projectId + "/design-settings").get("mode").asString()).isEqualTo("unset");
		String id = created.get("id").asString();
		member.request("POST", "/requests/" + id + "/approve", null, 403);
		member.request("POST", "/requests/" + id + "/reject", null, 403);
		outsider.request("POST", "/requests/" + id + "/approve", null, 404);
		JsonNode list = owner.get(path);
		assertMatches("ChangeRequestList", list);
		assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(id);

		JsonNode approved = owner.request("POST", "/requests/" + id + "/approve", null, 200);
		assertMatches("ChangeRequest", approved);
		assertThat(approved.get("state").asString()).isEqualTo("approved");
		assertThat(approved.get("decidedBy").get("name").asString()).isEqualTo("승인 소유자");
		assertThat(approved.get("result").get("designVersion").asLong()).isEqualTo(2);
		JsonNode settings = owner.get("/projects/" + projectId + "/design-settings");
		assertThat(settings.get("templateId").asString()).isEqualTo(templates.get(0).get("id").asString());
		assertThat(owner.request("POST", "/requests/" + id + "/approve", null, 409).get("error").get("code").asString())
			.isEqualTo("REQUEST_NOT_PENDING");

		// 요청 뒤에 디자인이 바뀌었으면 승인해도 실행하지 않고 이유를 남긴다
		JsonNode stale = request(member, projectId, "design_change", design(1, templates.get(1)), 201);
		JsonNode failed = owner.request("POST", "/requests/" + stale.get("id").asString() + "/approve", null, 200);
		assertMatches("ChangeRequest", failed);
		assertThat(failed.get("state").asString()).isEqualTo("failed");
		assertThat(failed.get("error").get("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(owner.get("/projects/" + projectId + "/design-settings").get("version").asLong()).isEqualTo(2);

		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM project_events WHERE project_id = UUID_TO_BIN(?) AND type IN ('request.created', 'request.updated')
				""").param(projectId).query(Integer.class).single()).isEqualTo(4);
	}

	@Test
	void rejectedCanceledAndFailedRequestsChangeNothing() throws Exception {
		Browser owner = signup("거절 소유자");
		Browser member = signup("거절 요청자");
		String projectId = createProject(owner, "거절 흐름 " + UUID.randomUUID());
		join(owner, member, projectId);
		JsonNode template = owner.get("/templates").get("items").get(0);

		JsonNode rejected = request(member, projectId, "design_change", design(1, template), 201);
		String id = rejected.get("id").asString();
		member.request("POST", "/requests/" + id + "/cancel", null, 200);
		owner.request("POST", "/requests/" + id + "/reject", null, 409);

		JsonNode next = request(member, projectId, "design_change", design(1, template), 201);
		String nextId = next.get("id").asString();
		owner.request("POST", "/requests/" + nextId + "/cancel", null, 403);
		JsonNode result = owner.request("POST", "/requests/" + nextId + "/reject", null, 200);
		assertThat(result.get("state").asString()).isEqualTo("rejected");
		owner.request("POST", "/requests/" + nextId + "/approve", null, 409);
		assertThat(owner.get("/projects/" + projectId + "/design-settings").get("mode").asString()).isEqualTo("unset");

		// 이 테스트 환경은 AI 구성 생성을 꺼 두었으니 승인해도 등록하지 못하고 이유를 남긴다
		JsonNode outline = request(member, projectId, "outline_generation",
				Map.of("expectedVersion", 1, "sourceIds", List.of(UUID.randomUUID().toString())), 201);
		JsonNode unavailable = owner.request("POST", "/requests/" + outline.get("id").asString() + "/approve", null, 200);
		assertThat(unavailable.get("state").asString()).isEqualTo("failed");
		assertThat(unavailable.get("error").get("message").asString()).isNotBlank();

		// 내보낸 팀원은 요청을 볼 수도 처리할 수도 없다
		JsonNode pending = request(member, projectId, "design_change", design(1, template), 201);
		removeMember(owner, projectId, member.userId);
		member.request("GET", "/projects/" + projectId + "/requests", null, 403);
		member.request("POST", "/requests/" + pending.get("id").asString() + "/cancel", null, 403);
		// 요청자가 나갔으면 승인해도 요청자 이름으로 실행할 수 없다
		JsonNode orphan = owner.request("POST", "/requests/" + pending.get("id").asString() + "/approve", null, 200);
		assertThat(orphan.get("state").asString()).isEqualTo("failed");
		assertThat(owner.get("/projects/" + projectId + "/design-settings").get("mode").asString()).isEqualTo("unset");
	}

}
