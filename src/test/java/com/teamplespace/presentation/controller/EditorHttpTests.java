package com.teamplespace.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

// 편집 화면이 쓰는 저장, 동기화 스냅샷, 이벤트 구독을 실제 HTTP로 확인한다
@TestPropertySource(properties = "teamplespace.editor.sample-presentation-enabled=true")
class EditorHttpTests extends HttpIntegrationSupport {

	@MockitoSpyBean
	ProjectEvents events;

	@Test
	void snapshotFollowsContractBeforeAndAfterDocumentExists() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		String project = createProject(owner, "편집 스냅샷");
		join(owner, member, project);

		JsonNode empty = owner.get("/projects/" + project + "/sync-snapshot?scope=editor");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/SyncSnapshot", empty.toString())).isEmpty();
		assertThat(empty.get("presentation").isNull()).isTrue();
		assertThat(empty.get("designSettings").get("mode").asString()).isEqualTo("unset");
		assertThat(empty.get("members")).hasSize(2);
		assertThat(empty.get("members").get(0).get("displayName").asString()).isEqualTo("소유자");
		assertThat(empty.get("members").get(0).get("isOwner").asBoolean()).isTrue();
		assertThat(empty.get("members").get(1).get("isOwner").asBoolean()).isFalse();

		// 여러 번 요청해도 문서는 하나다
		owner.post("/projects/" + project + "/sample-presentation", null, 204);
		member.post("/projects/" + project + "/sample-presentation", null, 204);
		JsonNode snapshot = member.get("/projects/" + project + "/sync-snapshot?scope=editor");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/SyncSnapshot", snapshot.toString())).isEmpty();
		assertThat(snapshot.get("presentation").get("slides")).hasSize(3);
		assertThat(snapshot.get("eventCursor").asString()).isEqualTo(empty.get("eventCursor").asString());

		owner.request("GET", "/projects/" + project + "/sync-snapshot", null, 422);
		owner.request("GET", "/projects/" + project + "/sync-snapshot?scope=workspace", null, 422);
	}

	@Test
	void savedTextIsKeptAndSameKeyReturnsSameResponse() throws Exception {
		Browser owner = signup("소유자");
		String project = createProject(owner, "저장");
		JsonNode title = sampleElement(owner, project, 0, 1);

		ObjectNode body = textPatch(title, "새 제목");
		String key = UUID.randomUUID().toString();
		JsonNode saved = owner.request("PATCH", path(title), body, 200, key);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MutationResponse", saved.toString())).isEmpty();
		assertThat(saved.get("elementVersion").asLong()).isEqualTo(2);

		// 응답을 못 받은 것으로 보고 같은 요청을 다시 보낸다
		assertThat(owner.request("PATCH", path(title), body, 200, key)).isEqualTo(saved);
		JsonNode reread = sampleElement(owner, project, 0, 1);
		assertThat(reread.get("version").asLong()).isEqualTo(2);
		assertThat(reread.get("content").get("text").asString()).isEqualTo("새 제목");

		owner.request("PATCH", path(title), body, 400, null);

		// 요소 종류에 맞지 않는 내용과 스타일은 저장하지 않는다
		JsonNode current = sampleElement(owner, project, 0, 1);
		for (JsonNode patch : List.of(json.readTree("{\"content\":{}}"),
				json.readTree("{\"content\":{\"text\":1}}"),
				json.readTree("{\"content\":{\"text\":\"글\",\"shape\":\"rect\"}}"),
				json.readTree("{\"style\":{\"fontFamily\":\"Pretendard\"}}"),
				json.readTree("{\"style\":{\"fontFamily\":\"Pretendard\",\"fontSize\":20,\"fontWeight\":450,"
						+ "\"color\":\"red\",\"align\":\"left\",\"lineHeight\":1.2}}"))) {
			ObjectNode invalid = textPatch(current, "무시");
			invalid.set("patch", patch);
			owner.request("PATCH", path(current), invalid, 422, UUID.randomUUID().toString());
		}
		JsonNode shape = sampleElement(owner, project, 0, 0);
		ObjectNode wrongShape = textPatch(shape, "무시");
		owner.request("PATCH", path(shape), wrongShape, 422, UUID.randomUUID().toString());
		assertThat(sampleElement(owner, project, 0, 1)).isEqualTo(current);
		assertThat(sampleElement(owner, project, 0, 0)).isEqualTo(shape);

		// 맞는 스타일은 통째로 바꿔 저장한다
		ObjectNode styled = textPatch(current, "무시");
		styled.set("patch", json.readTree("{\"style\":{\"fontFamily\":\"Pretendard\",\"fontSize\":60,"
				+ "\"fontWeight\":700,\"color\":\"#FFFFFF\",\"align\":\"center\",\"lineHeight\":1.2}}"));
		owner.request("PATCH", path(current), styled, 200, UUID.randomUUID().toString());
		JsonNode restyled = sampleElement(owner, project, 0, 1);
		assertThat(restyled.get("style").get("align").asString()).isEqualTo("center");
		assertThat(restyled.get("content")).isEqualTo(current.get("content"));
		assertThat(ContractSchemas.validate("document.schema.json#/$defs/Element", restyled.toString())).isEmpty();
		owner.request("PATCH", path(title), Map.of("generation", 1), 422, UUID.randomUUID().toString());
	}

	@Test
	void aiRevisionIsRefusedWhenProviderIsOff() throws Exception {
		Browser owner = signup("제공자 꺼짐");
		String project = createProject(owner, "AI 수정 꺼짐");
		JsonNode title = sampleElement(owner, project, 1, 0);
		assertThat(owner.get("/capabilities").get("featureFlags").get("aiRevision").asBoolean()).isFalse();
		assertThat(owner.request("POST", "/slides/" + title.get("slideId").asString() + "/ai-revision-jobs",
				Map.of("generation", 1, "prompt", "짧게"), 503, UUID.randomUUID().toString()).get("error").get("code")
			.asString()).isEqualTo("SERVICE_UNAVAILABLE");
	}

	@Test
	void sameElementConflictIsRejectedAndDifferentElementsBothSave() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		String project = createProject(owner, "충돌");
		join(owner, member, project);
		JsonNode title = sampleElement(owner, project, 0, 1);
		JsonNode subtitle = sampleElement(owner, project, 0, 2);

		owner.request("PATCH", path(title), textPatch(title, "소유자의 제목"), 200, UUID.randomUUID().toString());
		// 같은 요소를 예전 버전에서 고친 저장은 덮어쓰지 않는다
		ObjectNode stale = textPatch(title, "팀원의 제목");
		String staleKey = UUID.randomUUID().toString();
		JsonNode conflict = member.request("PATCH", path(title), stale, 409, staleKey);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ErrorResponse", conflict.toString())).isEmpty();
		assertThat(conflict.get("error").get("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(conflict.get("error").get("currentVersions").get("elementVersion").asLong()).isEqualTo(2);
		// 거절된 요청을 같은 키로 다시 보내도 같은 거절이다
		assertThat(member.request("PATCH", path(title), stale, 409, staleKey).get("error"))
			.isEqualTo(conflict.get("error"));
		assertThat(sampleElement(owner, project, 0, 1).get("content").get("text").asString()).isEqualTo("소유자의 제목");

		// 다른 요소는 따로 저장된다
		member.request("PATCH", path(subtitle), textPatch(subtitle, "팀원의 부제"), 200, UUID.randomUUID().toString());
		assertThat(sampleElement(owner, project, 0, 2).get("content").get("text").asString()).isEqualTo("팀원의 부제");
	}

	@Test
	void outsidersAndRemovedMembersCannotUseEditorPaths() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		Browser stranger = signup("외부인");
		String project = createProject(owner, "권한");
		join(owner, member, project);
		JsonNode title = sampleElement(owner, project, 0, 1);

		for (Browser blocked : List.of(stranger, new Browser())) {
			int status = blocked == stranger ? 404 : 401;
			blocked.bootstrap();
			blocked.request("GET", "/projects/" + project + "/sync-snapshot?scope=editor", null, status);
			blocked.request("GET", "/projects/" + project + "/events", null, status);
			blocked.request("POST", "/projects/" + project + "/sample-presentation", null, status,
					UUID.randomUUID().toString());
			blocked.request("PATCH", path(title), textPatch(title, "남의 수정"), status, UUID.randomUUID().toString());
		}

		// 구독 중에 제거되면 연결이 끝난다
		HttpResponse<Stream<String>> stream = member.lines("/projects/" + project + "/events", Map.of());
		assertThat(stream.statusCode()).isEqualTo(200);
		removeMember(owner, project, member.userId);
		assertThat(messages(stream, null)).isEmpty();

		for (String path : List.of("/projects/" + project + "/sync-snapshot?scope=editor",
				"/projects/" + project + "/events")) {
			assertThat(member.request("GET", path, null, 403).get("error").get("code").asString())
				.isEqualTo("ACCESS_DENIED");
		}
		JsonNode denied = member.request("PATCH", path(title), textPatch(title, "제거된 뒤의 수정"), 403,
				UUID.randomUUID().toString());
		assertThat(denied.get("error").get("code").asString()).isEqualTo("ACCESS_DENIED");
		assertThat(sampleElement(owner, project, 0, 1).get("version").asLong()).isEqualTo(1);
	}

	@Test
	void eventStreamReplaysAfterCursorAndDeliversNewEvents() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		String project = createProject(owner, "이벤트");
		join(owner, member, project);
		JsonNode title = sampleElement(owner, project, 0, 1);
		String cursor = owner.get("/projects/" + project + "/sync-snapshot?scope=editor").get("eventCursor").asString();
		JsonNode saved = owner.request("PATCH", path(title), textPatch(title, "한글 제목"), 200,
				UUID.randomUUID().toString());

		// 스냅샷 커서 다음부터 다시 받는다
		HttpResponse<Stream<String>> replay = member.lines("/projects/" + project + "/events",
				Map.of("Last-Event-ID", cursor));
		assertThat(replay.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
		List<Map<String, String>> replayed = messages(replay, saved.get("eventId").asString());
		Map<String, String> updated = replayed.stream()
			.filter(message -> message.get("event").equals("element.updated"))
			.findFirst()
			.orElseThrow();
		assertThat(updated.get("id")).isEqualTo(saved.get("eventId").asString());
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Event", updated.get("data"))).isEmpty();
		JsonNode event = json.readTree(updated.get("data"));
		assertThat(event.get("eventId").asString()).isEqualTo(updated.get("id"));
		assertThat(event.get("payload").get("resource").get("content").get("text").asString()).isEqualTo("한글 제목");

		// 커서 없이 구독하면 그 뒤에 생긴 이벤트만 받는다
		HttpResponse<Stream<String>> live = member.lines("/projects/" + project + "/events", Map.of());
		JsonNode subtitle = sampleElement(owner, project, 0, 2);
		JsonNode next = owner.request("PATCH", path(subtitle), textPatch(subtitle, "새 부제"), 200,
				UUID.randomUUID().toString());
		List<Map<String, String>> delivered = messages(live, next.get("eventId").asString());
		assertThat(delivered).extracting(message -> message.get("id")).doesNotContain(saved.get("eventId").asString());
		assertThat(delivered).extracting(message -> message.get("id")).contains(next.get("eventId").asString());

		// 이어 받을 수 없는 커서에는 스냅샷을 다시 읽으라는 신호만 보내고 끝낸다
		for (String unusable : List.of("999999", "not-a-cursor")) {
			List<Map<String, String>> signal = messages(
					member.lines("/projects/" + project + "/events", Map.of("Last-Event-ID", unusable)), null);
			assertThat(signal).hasSize(1);
			assertThat(signal.get(0).get("event")).isEqualTo("snapshot_required");
			assertThat(ContractSchemas.validate("api.schema.json#/$defs/SnapshotRequired", signal.get(0).get("data")))
				.isEmpty();
			assertThat(json.readTree(signal.get(0).get("data")).get("projectId").asString()).isEqualTo(project);
		}
	}

	@Test
	void cursorPositionsReachOtherMembersWithoutEventIds() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		Browser stranger = signup("외부인");
		String project = createProject(owner, "커서");
		join(owner, member, project);
		String slide = sampleElement(owner, project, 0, 1).get("slideId").asString();
		String path = "/projects/" + project + "/presence";

		HttpResponse<Stream<String>> stream = member.lines("/projects/" + project + "/events", Map.of());
		ObjectNode position = json.createObjectNode().put("slideId", slide).put("x", 412.5).put("y", 180);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/PresenceUpdateRequest", position.toString())).isEmpty();
		owner.request("POST", path, position, 204);
		// 내 위치는 내 구독으로 오지 않는다
		member.request("POST", path, position, 204);

		Map<String, String> moved = firstNamed(stream.body().iterator(), "presence.updated");
		assertThat(moved).doesNotContainKey("id");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/PresenceUpdated", moved.get("data"))).isEmpty();
		JsonNode cursors = json.readTree(moved.get("data")).get("cursors");
		assertThat(cursors).hasSize(1);
		assertThat(cursors.get(0).get("userId").asString()).isEqualTo(owner.userId);
		assertThat(cursors.get(0).get("x").asDouble()).isEqualTo(412.5);

		// 떠나면 빈 목록이 온다
		Iterator<String> next = member.lines("/projects/" + project + "/events", Map.of()).body().iterator();
		assertThat(json.readTree(firstNamed(next, "presence.updated").get("data")).get("cursors")).hasSize(1);
		owner.request("POST", path, json.createObjectNode().putNull("slideId"), 204);
		assertThat(json.readTree(firstNamed(next, "presence.updated").get("data")).get("cursors")).isEmpty();

		// 팀원이 아니면 보낼 수 없고 잘못된 좌표는 입력 오류다
		stranger.request("POST", path, position, 404);
		JsonNode invalid = owner.request("POST", path,
				json.createObjectNode().put("slideId", slide).put("x", 99999).put("y", 0), 422);
		assertThat(invalid.get("error").get("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void viewedTabsReachOtherMembersAndClearWhenTheyLeave() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		Browser stranger = signup("외부인");
		String project = createProject(owner, "보는 탭");
		join(owner, member, project);
		String path = "/projects/" + project + "/viewing";

		Iterator<String> stream = member.lines("/projects/" + project + "/events", Map.of()).body().iterator();

		ObjectNode tab = json.createObjectNode().put("tab", "materials");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ViewingRequest", tab.toString())).isEmpty();
		owner.request("PUT", path, tab, 204);
		// 내 탭은 내 구독으로 오지 않는다
		member.request("PUT", path, json.createObjectNode().put("tab", "outline"), 204);
		Map<String, String> viewed = firstNamed(stream, "viewers.updated");
		assertThat(viewed).doesNotContainKey("id");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ViewersUpdated", viewed.get("data"))).isEmpty();
		JsonNode viewers = json.readTree(viewed.get("data")).get("viewers");
		assertThat(viewers).hasSize(1);
		assertThat(viewers.get(0).get("userId").asString()).isEqualTo(owner.userId);
		assertThat(viewers.get(0).get("tab").asString()).isEqualTo("materials");

		// 떠나면 빈 목록이 온다
		owner.request("PUT", path, json.createObjectNode().putNull("tab"), 204);
		assertThat(json.readTree(firstNamed(stream, "viewers.updated").get("data")).get("viewers")).isEmpty();

		// 팀원이 아니면 보낼 수 없고 모르는 탭은 입력 오류다
		stranger.request("PUT", path, tab, 404);
		JsonNode invalid = owner.request("PUT", path, json.createObjectNode().put("tab", "admin"), 422);
		assertThat(invalid.get("error").get("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void subscriptionEndsWhenEventLookupFailsSoClientCanReconnect() throws Exception {
		Browser owner = signup("소유자");
		String project = createProject(owner, "조회 실패");
		HttpResponse<Stream<String>> stream = owner.lines("/projects/" + project + "/events", Map.of());
		assertThat(stream.statusCode()).isEqualTo(200);

		// 구독 중에 DB 조회가 실패하면 연결을 끝낸다
		doThrow(new DataAccessResourceFailureException("조회 실패")).when(events)
			.after(any(), eq(UUID.fromString(project)), anyString(), anyInt());
		assertThat(messages(stream, null)).isEmpty();

		// 조회가 회복되면 다시 구독해 이어 받는다
		Mockito.reset(events);
		JsonNode title = sampleElement(owner, project, 0, 1);
		JsonNode saved = owner.request("PATCH", path(title), textPatch(title, "다시 구독"), 200,
				UUID.randomUUID().toString());
		List<Map<String, String>> replayed = messages(
				owner.lines("/projects/" + project + "/events", Map.of("Last-Event-ID", "0")),
				saved.get("eventId").asString());
		assertThat(replayed).extracting(message -> message.get("id")).contains(saved.get("eventId").asString());
	}

	@Test
	void snapshotIncludesEveryActiveJobBeyondOnePage() throws Exception {
		Browser owner = signup("소유자");
		String project = createProject(owner, "작업이 많은 프로젝트");
		try {
			// 임대가 한참 남은 실행 중 작업이라 작업 실행기가 건드리지 않는다
			for (int index = 0; index < 101; index++) {
				jdbc.sql("""
						INSERT INTO jobs (id, project_id, requester_id, type, target_type, status, input_snapshot,
						                  input_hash, available_at, attempt_no, lease_token, lease_expires_at)
						VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), 'snapshot_check', 'project',
						        'running', JSON_OBJECT(), UNHEX(SHA2(UUID(), 256)), UTC_TIMESTAMP(6), 1,
						        UNHEX(SHA2(UUID(), 256)), UTC_TIMESTAMP(6) + INTERVAL 1 DAY)
						""").params(project, owner.userId).update();
			}
			JsonNode snapshot = owner.get("/projects/" + project + "/sync-snapshot?scope=editor");
			assertThat(ContractSchemas.validate("api.schema.json#/$defs/SyncSnapshot", snapshot.toString())).isEmpty();
			assertThat(snapshot.get("activeJobs")).hasSize(101);
		}
		finally {
			jdbc.sql("DELETE FROM jobs WHERE project_id = UUID_TO_BIN(?)").param(project).update();
		}
	}

	// 예제 문서를 만들고 지정한 슬라이드의 요소를 읽는다
	private JsonNode sampleElement(Browser browser, String project, int slide, int element) throws Exception {
		browser.post("/projects/" + project + "/sample-presentation", null, 204);
		return browser.get("/projects/" + project + "/sync-snapshot?scope=editor")
			.get("presentation")
			.get("slides")
			.get(slide)
			.get("elements")
			.get(element);
	}

	private String path(JsonNode element) {
		return "/slides/" + element.get("slideId").asString() + "/elements/" + element.get("id").asString();
	}

	private ObjectNode textPatch(JsonNode element, String text) {
		ObjectNode body = json.createObjectNode();
		body.put("generation", 1);
		body.put("baseVersion", element.get("version").asLong());
		body.put("editGroupId", UUID.randomUUID().toString());
		body.put("clientMutationId", UUID.randomUUID().toString());
		body.putObject("patch").putObject("content").put("text", text);
		return body;
	}

	// 이름이 같은 첫 신호를 받을 때까지 읽는다. 연결은 열어 둔다
	private Map<String, String> firstNamed(Iterator<String> iterator, String name) throws Exception {
		return CompletableFuture.supplyAsync(() -> {
			Map<String, String> current = new LinkedHashMap<>();
			while (iterator.hasNext()) {
				String line = iterator.next();
				int colon = line.indexOf(':');
				if (line.isEmpty()) {
					if (name.equals(current.get("event"))) {
						return current;
					}
					current = new LinkedHashMap<>();
				}
				else if (colon > 0) {
					current.put(line.substring(0, colon), line.substring(colon + 1).strip());
				}
			}
			throw new IllegalStateException(name + " 신호를 받기 전에 연결이 끝났다");
		}).get(10, TimeUnit.SECONDS);
	}

}
