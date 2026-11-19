package com.teamplespace.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

class OutlineHttpTests extends HttpIntegrationSupport {

	@Test
	void emptyOutlineAndFullReplacementKeepOrderAndPinnedSources() throws Exception {
		Browser owner = signup("구성 소유자");
		String project = createProject(owner, "발표 순서");
		String path = "/projects/" + project + "/outline";
		JsonNode empty = owner.get(path);
		assertThat(empty.get("version").asLong()).isEqualTo(1);
		assertThat(empty.get("items").isEmpty()).isTrue();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM outlines WHERE project_id=UUID_TO_BIN(?)")
			.param(project).query(Integer.class).single()).isZero();

		JsonNode first = memo(owner, project, "첫 근거");
		JsonNode second = memo(owner, project, "두 번째 근거");
		String firstId = first.get("id").asString();
		String secondId = second.get("id").asString();
		var create = input(1, item("배경", secondId, firstId), item("결론"));
		assertThat(ContractSchemas.validate("stage-3.schema.json#/$defs/OutlinePutRequest",
				json.writeValueAsString(create))).isEmpty();
		JsonNode saved = put(owner, path, create, 200);
		assertThat(ContractSchemas.validate("stage-3.schema.json#/$defs/Outline",
				json.writeValueAsString(saved))).isEmpty();
		assertThat(saved.get("version").asLong()).isEqualTo(2);
		assertThat(owner.get(path)).isEqualTo(saved);
		JsonNode a = saved.get("items").get(0);
		JsonNode b = saved.get("items").get(1);
		assertThat(a.get("position").asInt()).isZero();
		assertThat(b.get("position").asInt()).isEqualTo(1);
		assertThat(a.get("sourceRefs").get(0).get("materialId").asString()).isEqualTo(secondId);
		assertThat(a.get("sourceRefs").get(1).get("revisionId")).isEqualTo(first.get("currentRevision").get("id"));

		owner.request("PATCH", "/materials/" + firstId,
				Map.of("expectedVersion", first.get("version").asLong(), "markdown", "새 원문"), 200);
		assertThat(owner.get(path)).isEqualTo(saved);

		JsonNode reordered = put(owner, path, input(2,
				Map.of("id", b.get("id").asString(), "title", "결론 먼저", "sourceIds", List.of()),
				Map.of("id", a.get("id").asString(), "title", "배경 나중", "sourceIds", List.of(firstId))), 200);
		assertThat(reordered.get("items").get(0).get("id")).isEqualTo(b.get("id"));
		assertThat(reordered.get("items").get(1).get("sourceRefs").get(0).get("sourceVersion").asLong()).isEqualTo(2);
		assertThat(owner.get(path)).isEqualTo(reordered);
		JsonNode cleared = put(owner, path, input(3), 200);
		assertThat(cleared.get("version").asLong()).isEqualTo(4);
		assertThat(cleared.get("items").isEmpty()).isTrue();
		assertThat(owner.get(path)).isEqualTo(cleared);
	}

	@Test
	void rejectsForeignDeletedAndUnreadyReferencesWithoutChangingOutline() throws Exception {
		Browser owner = signup("근거 검증");
		String project = createProject(owner, "근거 범위");
		String other = createProject(owner, "다른 프로젝트");
		String path = "/projects/" + project + "/outline";
		JsonNode valid = memo(owner, project, "현재 근거");
		String material = valid.get("id").asString();
		JsonNode saved = put(owner, path, input(1, item("보존할 항목", material)), 200);
		String foreign = memo(owner, other, "다른 근거").get("id").asString();
		String processing = owner.post("/projects/" + project + "/materials/link",
				Map.of("url", "https://example.com/"), 201).get("material").get("id").asString();
		for (String bad : List.of(foreign, processing, UUID.randomUUID().toString())) {
			JsonNode result = put(owner, path, input(2, item("유효한 항목", material), item("잘못된 항목", bad)), 422);
			assertThat(result.get("error").get("code").asString()).isEqualTo("VALIDATION_FAILED");
			assertThat(result.get("error").get("fieldErrors").get(0).get("field").asString())
				.isEqualTo("items[1].sourceIds[0]");
			assertThat(owner.get(path)).isEqualTo(saved);
		}
		owner.request("DELETE", "/materials/" + material, Map.of("expectedVersion", valid.get("version").asLong()), 200);
		JsonNode afterDelete = owner.get(path);
		assertThat(afterDelete.get("version").asLong()).isEqualTo(2);
		assertThat(afterDelete.get("items").get(0).get("sourceRefs").get(0).get("sourceDeleted").asBoolean()).isTrue();
		put(owner, path, input(2, item("삭제한 자료 연결", material)), 422);
		assertThat(owner.get(path)).isEqualTo(afterDelete);
	}

	@Test
	void replacesOnlyTheSelectedItemsSourcesAndReplaysEvenAfterItsRemoval() throws Exception {
		Browser owner = signup("근거 수정 소유자");
		Browser member = signup("근거 수정 팀원");
		Browser outsider = signup("근거 수정 외부인");
		String project = createProject(owner, "근거 교체");
		join(owner, member, project);
		String projectPath = "/projects/" + project + "/outline";
		String first = memo(owner, project, "첫 자료").get("id").asString();
		String second = memo(owner, project, "둘째 자료").get("id").asString();
		JsonNode original = put(owner, projectPath, input(1, item("배경", first), item("결론", first)), 200);
		String itemId = original.get("items").get(0).get("id").asString();
		String path = "/outline-items/" + itemId + "/sources";
		var body = Map.of("expectedVersion", 2, "sourceIds", List.of(second, first));
		assertThat(ContractSchemas.validate("stage-3.schema.json#/$defs/OutlineSourcesPutRequest",
			json.writeValueAsString(body))).isEmpty();
		put(outsider, path, body, 404);
		put(member, path, Map.of("expectedVersion", 2, "sourceIds", List.of(second, UUID.randomUUID().toString())), 422);
		assertThat(owner.get(projectPath)).isEqualTo(original);
		String key = UUID.randomUUID().toString();
		JsonNode saved = member.request("PUT", path, body, 200, key);
		assertThat(saved.get("version").asLong()).isEqualTo(3);
		assertThat(saved.get("items").get(0).get("id").asString()).isEqualTo(itemId);
		assertThat(saved.get("items").get(0).get("sourceRefs").get(0).get("materialId").asString()).isEqualTo(second);
		assertThat(saved.get("items").get(1)).isEqualTo(original.get("items").get(1));
		assertThat(owner.get(projectPath)).isEqualTo(saved);
		assertThat(put(owner, path, body, 409).get("error").get("currentVersions").get("outlineVersion").asLong())
			.isEqualTo(3);
		JsonNode cleared = put(owner, path, Map.of("expectedVersion", 3, "sourceIds", List.of()), 200);
		assertThat(cleared.get("items").get(0).get("sourceRefs").isEmpty()).isTrue();
		assertThat(cleared.get("items").get(1)).isEqualTo(original.get("items").get(1));
		put(owner, projectPath, input(4), 200);
		assertThat(member.request("PUT", path, body, 200, key)).isEqualTo(saved);
		removeMember(owner, project, member.userId);
		member.request("PUT", path, body, 403, key);
		assertThat(owner.get(projectPath).get("version").asLong()).isEqualTo(5);
	}

	@Test
	void twoMembersRaceAndOnlyTheWinningPayloadIsStoredAndReplayed() throws Exception {
		Browser owner = signup("동시 저장 소유자");
		Browser member = signup("동시 저장 팀원");
		Browser outsider = signup("외부 사용자");
		String project = createProject(owner, "동시 저장");
		join(owner, member, project);
		String path = "/projects/" + project + "/outline";
		var memberFirst = input(1, item("팀원이 처음 저장"));
		String memberKey = UUID.randomUUID().toString();
		member.request("PUT", path, memberFirst, 200, memberKey);
		long version = owner.get(path).get("version").asLong();
		assertThat(member.get(path).get("version").asLong()).isEqualTo(version);
		var bodyA = input(version, item("소유자 수정"));
		var bodyB = input(version, item("팀원 수정"));
		String keyA = UUID.randomUUID().toString();
		String keyB = UUID.randomUUID().toString();
		String csrfA = owner.bootstrap().get("csrfToken").asString();
		String csrfB = member.bootstrap().get("csrfToken").asString();
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(() -> race(owner, path, bodyA, keyA, csrfA, start));
			var b = pool.submit(() -> race(member, path, bodyB, keyB, csrfB, start));
			start.countDown();
			var responseA = a.get(20, TimeUnit.SECONDS);
			var responseB = b.get(20, TimeUnit.SECONDS);
			assertThat(List.of(responseA.statusCode(), responseB.statusCode())).containsExactlyInAnyOrder(200, 409);
			boolean ownerWon = responseA.statusCode() == 200;
			JsonNode saved = json.readTree(ownerWon ? responseA.body() : responseB.body());
			JsonNode rejected = json.readTree(ownerWon ? responseB.body() : responseA.body());
			assertThat(rejected.get("error").get("code").asString()).isEqualTo("VERSION_CONFLICT");
			assertThat(rejected.get("error").get("currentVersions").get("outlineVersion").asLong())
				.isEqualTo(version + 1);
			assertThat(saved.get("version").asLong()).isEqualTo(version + 1);
			assertThat(saved.get("items").get(0).get("title").asString()).isEqualTo(ownerWon ? "소유자 수정" : "팀원 수정");
			assertThat(owner.get(path)).isEqualTo(saved);
			Browser winner = ownerWon ? owner : member;
			String key = ownerWon ? keyA : keyB;
			assertThat(winner.request("PUT", path, ownerWon ? bodyA : bodyB, 200, key)).isEqualTo(saved);
			JsonNode reused = winner.request("PUT", path, input(version, item("다른 요청")), 409, key);
			assertThat(reused.get("error").get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
			assertThat(owner.get(path)).isEqualTo(saved);
		}
		assertThat(jdbc.sql("SELECT COUNT(*) FROM project_events WHERE project_id=UUID_TO_BIN(?) AND type='outline.updated'")
			.param(project).query(Integer.class).single()).isEqualTo(2);
		outsider.request("GET", path, null, 404);
		put(outsider, path, bodyA, 404);
		removeMember(owner, project, member.userId);
		member.request("GET", path, null, 403);
		put(member, path, bodyB, 403);
		member.request("PUT", path, memberFirst, 403, memberKey);
	}

	private HttpResponse<byte[]> race(Browser browser, String path, Map<String, Object> body, String key, String csrf,
			CountDownLatch start) throws Exception {
		assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
		return browser.raw("PUT", "/v1" + path, json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8),
				Map.of("Origin", "http://localhost:5174", "Content-Type", "application/json",
						"X-CSRF-Token", csrf, "Idempotency-Key", key));
	}

	private JsonNode memo(Browser browser, String project, String title) throws Exception {
		return browser.post("/projects/" + project + "/materials/memo", Map.of("title", title, "markdown", "본문"), 201)
			.get("material");
	}

	private JsonNode put(Browser browser, String path, Map<String, Object> body, int status) throws Exception {
		return browser.request("PUT", path, body, status, UUID.randomUUID().toString());
	}

	private Map<String, Object> input(long version, Object... items) {
		return Map.of("expectedVersion", version, "items", List.of(items));
	}

	private Map<String, Object> item(String title, String... sources) {
		return Map.of("title", title, "summary", "발표할 내용", "sourceIds", List.of(sources));
	}
}
