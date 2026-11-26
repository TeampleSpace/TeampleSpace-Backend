package com.teamplespace.collaboration.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

class MemoHttpTests extends HttpIntegrationSupport {

	static String piece(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes());
	}

	String memo(Browser owner, String projectId) throws Exception {
		return owner.request("POST", "/projects/" + projectId + "/materials/memo",
				Map.of("title", "공유 메모", "markdown", "처음 본문"), 201, java.util.UUID.randomUUID().toString())
			.get("material")
			.get("id")
			.asString();
	}

	@Test
	void membersShareOrderedPiecesAndReceiveThemLive() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		Browser stranger = signup("외부인");
		String project = createProject(owner, "공유 메모");
		join(owner, member, project);
		String id = memo(owner, project);
		String path = "/materials/" + id + "/memo-updates";

		Iterator<String> stream = member.lines("/projects/" + project + "/events", Map.of()).body().iterator();

		// 처음 조각은 한 사람만 넣을 수 있다
		Map<String, Object> seed = Map.of("data", piece("seed"), "initial", true);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MemoUpdateRequest", json.writeValueAsString(seed)))
			.isEmpty();
		JsonNode first = owner.request("POST", path, seed, 201);
		assertThat(first.get("seq").asLong()).isEqualTo(1);
		JsonNode taken = member.request("POST", path, Map.of("data", piece("other seed"), "initial", true), 409);
		assertThat(taken.get("error").get("code").asString()).isEqualTo("MATERIAL_STATE_CONFLICT");

		member.request("POST", path, Map.of("data", piece("b")), 201);
		owner.request("POST", path, Map.of("data", piece("c")), 201);

		// 구독으로 순번과 조각이 바로 온다
		Map<String, String> live = firstNamed(stream, "memo.updated");
		assertThat(live).doesNotContainKey("id");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MemoUpdated", live.get("data"))).isEmpty();
		assertThat(json.readTree(live.get("data")).get("seq").asLong()).isEqualTo(1);
		assertThat(json.readTree(firstNamed(stream, "memo.updated").get("data")).get("seq").asLong()).isEqualTo(2);

		// 놓친 조각은 순번 다음부터 다시 읽는다
		JsonNode page = member.get(path + "?after=1");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MemoUpdateList", page.toString())).isEmpty();
		assertThat(page.get("updates")).extracting(update -> update.get("seq").asLong()).containsExactly(2L, 3L);
		assertThat(page.get("updates").get(1).get("data").asString()).isEqualTo(piece("c"));

		// 압축하면 앞 순번이 한 덩어리가 되고 뒤 순번은 그대로다
		member.request("POST", path, Map.of("data", piece("d")), 201);
		owner.request("POST", path + "/compaction", Map.of("upTo", 3, "data", piece("seed b c")), 204);
		JsonNode compacted = owner.get(path);
		assertThat(compacted.get("updates")).extracting(update -> update.get("seq").asLong()).containsExactly(3L, 4L);
		assertThat(compacted.get("updates").get(0).get("data").asString()).isEqualTo(piece("seed b c"));
		owner.request("POST", path + "/compaction", Map.of("upTo", 9, "data", piece("x")), 422);

		// 팀원이 아니면 없는 경로처럼 응답하고 빈 조각은 입력 오류다
		stranger.request("GET", path, null, 404);
		stranger.request("POST", path, Map.of("data", piece("x")), 404);
		owner.request("POST", path, Map.of("data", ""), 422);
		owner.request("POST", path, Map.of("data", "%%%"), 422);
	}

	@Test
	void cursorStatesReachMembersWithoutBeingStored() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		Browser stranger = signup("외부인");
		String project = createProject(owner, "메모 커서");
		join(owner, member, project);
		String id = memo(owner, project);
		String path = "/materials/" + id + "/memo-awareness";

		Iterator<String> stream = member.lines("/projects/" + project + "/events", Map.of()).body().iterator();
		Map<String, Object> state = Map.of("data", piece("cursor"));
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MemoAwarenessRequest", json.writeValueAsString(state)))
			.isEmpty();
		owner.request("POST", path, state, 204);
		Map<String, String> live = firstNamed(stream, "memo.awareness");
		assertThat(live).doesNotContainKey("id");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MemoAwareness", live.get("data"))).isEmpty();
		assertThat(json.readTree(live.get("data")).get("data").asString()).isEqualTo(piece("cursor"));

		stranger.request("POST", path, state, 404);
		owner.request("POST", path, Map.of("data", ""), 422);
		owner.request("POST", path, Map.of("data", Base64.getEncoder().encodeToString(new byte[17 * 1024])), 413);
	}

}
