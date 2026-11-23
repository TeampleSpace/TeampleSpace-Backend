package com.teamplespace.materials.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

// 자료 목록과 상세, 이름과 본문 변경, 삭제, 마크다운 자료, 팀 메모를 실제 HTTP 요청과 MySQL로 확인
class MaterialHttpTests extends HttpIntegrationSupport {

	private static final String S = "stage-2.schema.json#/$defs/";

	private void assertMatches(String schema, JsonNode value) {
		assertThat(ContractSchemas.validate(schema, json.writeValueAsString(value))).as(value.toString()).isEmpty();
	}

	private JsonNode memo(Browser browser, String projectId, String title, String markdown) throws Exception {
		return browser.post("/projects/" + projectId + "/materials/memo", Map.of("title", title, "markdown", markdown), 201)
			.get("material");
	}

	private int count(String table, String materialId) {
		return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE material_id = UUID_TO_BIN(?)")
			.param(materialId)
			.query(Integer.class)
			.single();
	}

	@Test
	void memoIsCreatedOnceAndReadBackWithAuthorAndRevision() throws Exception {
		Browser owner = signup("자료 소유자");
		String projectId = createProject(owner, "자료 등록 " + UUID.randomUUID());
		assertMatches(S + "MaterialList", owner.get("/projects/" + projectId + "/materials"));

		Map<String, Object> body = Map.of("title", "  다회용컵 사용 인터뷰  ", "markdown", "## 인터뷰 요약\n\n- 반납함이 멀어서 쓰지 않는다는 답이 많았다");
		String key = UUID.randomUUID().toString();
		String path = "/projects/" + projectId + "/materials/memo";
		JsonNode created = owner.request("POST", path, body, 201, key);
		assertMatches(S + "MaterialCreated", created);
		// 응답을 받지 못해 같은 키로 다시 보내도 자료는 하나다
		JsonNode replay = owner.request("POST", path, body, 201, key);
		assertThat(replay).isEqualTo(created);
		owner.request("POST", path, Map.of("title", "다른 제목", "markdown", "다른 본문"), 409, key);
		owner.request("POST", path, body, 400, null);

		JsonNode material = created.get("material");
		String id = material.get("id").asString();
		assertThat(material.get("title").asString()).isEqualTo("다회용컵 사용 인터뷰");
		assertThat(material.get("status").asString()).isEqualTo("ready");
		assertThat(material.get("createdBy").get("name").asString()).isEqualTo("자료 소유자");
		assertThat(material.get("currentRevision").get("sourceVersion").asInt()).isEqualTo(1);
		assertThat(material.get("currentRevision").get("excerpt").get("text").asString())
			.isEqualTo("인터뷰 요약 반납함이 멀어서 쓰지 않는다는 답이 많았다");
		assertThat(created.get("job").isNull()).isTrue();

		JsonNode list = owner.get("/projects/" + projectId + "/materials");
		assertThat(list.get("items").size()).isEqualTo(1);
		assertThat(list.get("counts").get("memo").asInt()).isEqualTo(1);
		// 새로고침한 것처럼 다시 조회해도 같은 내용이다
		JsonNode reread = owner.get("/materials/" + id);
		assertMatches(S + "Material", reread);
		assertThat(reread).isEqualTo(material);
		JsonNode source = owner.get("/materials/" + id + "/source");
		assertMatches(S + "MaterialSource", source);
		assertThat(source.get("markdown").asString()).startsWith("## 인터뷰 요약");
	}

	@Test
	void disabledSummaryDoesNotCreateAJobOrChangeTheMaterial() throws Exception {
		Browser owner = signup("요약 비활성 확인");
		String projectId = createProject(owner, "요약 비활성 확인");
		JsonNode material = memo(owner, projectId, "제목", "본문");
		String path = "/materials/" + material.path("id").asString();
		String revision = material.path("currentRevision").path("id").asString();
		assertThat(owner.get("/capabilities").path("featureFlags").path("materialSummary").asBoolean()).isFalse();
		assertThat(owner.post(path + "/summary-jobs", Map.of("expectedRevisionId", revision), 503)
			.path("error").path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(owner.get(path)).isEqualTo(material);
	}

	@Test
	void invalidMemoInputIsRejectedWithFieldErrors() throws Exception {
		Browser owner = signup("입력 검증");
		String projectId = createProject(owner, "입력 검증 " + UUID.randomUUID());
		String path = "/projects/" + projectId + "/materials/memo";
		JsonNode blank = owner.post(path, Map.of("title", "   ", "markdown", "본문"), 422);
		assertThat(blank.get("error").get("fieldErrors").get(0).get("field").asString()).isEqualTo("title");
		JsonNode tooLong = owner.post(path, Map.of("title", "긴 본문", "markdown", "가".repeat(100_001)), 422);
		assertThat(tooLong.get("error").get("fieldErrors").get(0).get("code").asString()).isEqualTo("TOO_LONG");
		owner.post(path, Map.of("title", "가".repeat(256), "markdown", ""), 422);
		// 빈 본문은 허용한다
		JsonNode empty = memo(owner, projectId, "빈 메모", "");
		assertThat(empty.get("currentRevision").get("excerpt").isNull()).isTrue();
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").size()).isEqualTo(1);
	}

	@Test
	void listSearchesFiltersCountsAndPagesNewestFirst() throws Exception {
		Browser owner = signup("목록 소유자");
		String projectId = createProject(owner, "목록 " + UUID.randomUUID());
		List<String> ids = new ArrayList<>();
		for (String title : List.of("환경 실태 보고", "다회용컵 인터뷰", "다회용컵 회수 동선")) {
			ids.add(memo(owner, projectId, title, "본문").get("id").asString());
		}
		String base = "/projects/" + projectId + "/materials";
		JsonNode first = owner.get(base + "?limit=2");
		assertMatches(S + "MaterialList", first);
		assertThat(first.get("items").get(0).get("id").asString()).isEqualTo(ids.get(2));
		assertThat(first.get("items").get(1).get("id").asString()).isEqualTo(ids.get(1));
		assertThat(first.get("counts").get("all").asInt()).isEqualTo(3);
		JsonNode second = owner.get(base + "?limit=2&cursor=" + first.get("nextCursor").asString());
		assertThat(second.get("items").get(0).get("id").asString()).isEqualTo(ids.get(0));
		assertThat(second.get("nextCursor").isNull()).isTrue();

		JsonNode found = owner.get(base + "?q=" + URLEncoder.encode("다회용컵", StandardCharsets.UTF_8));
		assertThat(found.get("items").size()).isEqualTo(2);
		assertThat(found.get("counts").get("all").asInt()).isEqualTo(2);
		JsonNode none = owner.get(base + "?q=" + URLEncoder.encode("없는 검색어", StandardCharsets.UTF_8));
		assertThat(none.get("items").isEmpty()).isTrue();
		assertThat(none.get("counts").get("all").asInt()).isZero();
		// 유형 필터는 개수에 반영하지 않는다
		JsonNode links = owner.get(base + "?kind=link");
		assertThat(links.get("items").isEmpty()).isTrue();
		assertThat(links.get("counts").get("memo").asInt()).isEqualTo(3);
		assertThat(owner.get(base + "?status=ready").get("items").size()).isEqualTo(3);

		owner.request("GET", base + "?kind=video", null, 422);
		owner.request("GET", base + "?cursor=invalid", null, 422);
		owner.request("GET", base + "?limit=0", null, 422);
	}

	@Test
	void renameKeepsTheRevisionAndMarkdownEditAddsOne() throws Exception {
		Browser owner = signup("수정 소유자");
		Browser member = signup("수정 팀원");
		String projectId = createProject(owner, "수정 " + UUID.randomUUID());
		join(owner, member, projectId);
		JsonNode material = memo(owner, projectId, "처음 제목", "처음 본문");
		String id = material.get("id").asString();
		String firstRevision = material.get("currentRevision").get("id").asString();

		// 이름과 설명은 팀원 누구나 바꾼다. 개정은 그대로다
		JsonNode renamed = member.request("PATCH", "/materials/" + id,
				Map.of("expectedVersion", 1, "title", "바꾼 제목", "description", "발표 2장 근거"), 200);
		assertMatches(S + "Material", renamed);
		assertThat(renamed.get("version").asInt()).isEqualTo(2);
		assertThat(renamed.get("title").asString()).isEqualTo("바꾼 제목");
		assertThat(renamed.get("description").asString()).isEqualTo("발표 2장 근거");
		assertThat(renamed.get("currentRevision").get("id").asString()).isEqualTo(firstRevision);
		assertThat(renamed.get("createdBy").get("name").asString()).isEqualTo("수정 소유자");

		// 오래된 버전으로 저장하면 덮어쓰지 않고 현재 버전을 알려 준다
		JsonNode conflict = owner.request("PATCH", "/materials/" + id, Map.of("expectedVersion", 1, "markdown", "덮어쓰기"), 409);
		assertThat(conflict.get("error").get("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(conflict.get("error").get("currentVersions").get("materialVersion").asInt()).isEqualTo(2);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ErrorResponse", json.writeValueAsString(conflict)))
			.isEmpty();

		JsonNode edited = owner.request("PATCH", "/materials/" + id, Map.of("expectedVersion", 2, "markdown", "고친 본문"), 200);
		assertThat(edited.get("version").asInt()).isEqualTo(3);
		assertThat(edited.get("currentRevision").get("sourceVersion").asInt()).isEqualTo(2);
		assertThat(edited.get("currentRevision").get("createdBy").get("name").asString()).isEqualTo("수정 소유자");
		assertThat(owner.get("/materials/" + id + "/source").get("markdown").asString()).isEqualTo("고친 본문");
		// 이전 개정은 바뀌지 않고 다시 읽을 수 있다
		JsonNode old = owner.get("/materials/" + id + "/source?revisionId=" + firstRevision);
		assertThat(old.get("markdown").asString()).isEqualTo("처음 본문");
		assertThat(old.get("sourceVersion").asInt()).isEqualTo(1);

		// 본문이 같으면 개정을 만들지 않고 설명을 비우면 null이 된다
		JsonNode same = owner.request("PATCH", "/materials/" + id,
				Map.of("expectedVersion", 3, "markdown", "고친 본문", "description", ""), 200);
		assertThat(same.get("currentRevision").get("sourceVersion").asInt()).isEqualTo(2);
		assertThat(same.get("description").isNull()).isTrue();
		assertThat(count("material_revisions", id)).isEqualTo(2);

		owner.request("PATCH", "/materials/" + id, Map.of("expectedVersion", 4), 422);
		owner.request("PATCH", "/materials/" + id, Map.of("expectedVersion", 4, "title", " "), 422);
		owner.request("GET", "/materials/" + id + "/source?revisionId=" + UUID.randomUUID(), null, 404);
	}

	@Test
	void onlyOwnerOrAuthorDeletesAndRevisionsSurvive() throws Exception {
		Browser owner = signup("삭제 소유자");
		Browser author = signup("삭제 작성자");
		Browser member = signup("삭제 팀원");
		String projectId = createProject(owner, "삭제 " + UUID.randomUUID());
		join(owner, author, projectId);
		join(owner, member, projectId);
		String byAuthor = memo(author, projectId, "작성자 자료", "본문").get("id").asString();
		String byOwner = memo(owner, projectId, "소유자 자료", "본문").get("id").asString();
		author.request("PUT", "/materials/" + byAuthor + "/note", Map.of("expectedVersion", 0, "markdown", "팀 메모"), 200);

		// 서버가 판단한 삭제 권한을 상세에 싣는다
		assertThat(member.get("/materials/" + byAuthor).get("canDelete").asBoolean()).isFalse();
		assertThat(author.get("/materials/" + byAuthor).get("canDelete").asBoolean()).isTrue();
		assertThat(owner.get("/materials/" + byAuthor).get("canDelete").asBoolean()).isTrue();
		assertThat(author.get("/materials/" + byOwner).get("canDelete").asBoolean()).isFalse();

		member.request("DELETE", "/materials/" + byAuthor, Map.of("expectedVersion", 1), 403);
		author.request("DELETE", "/materials/" + byOwner, Map.of("expectedVersion", 1), 403);
		author.request("DELETE", "/materials/" + byAuthor, Map.of("expectedVersion", 9), 409);
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").size()).isEqualTo(2);

		JsonNode deleted = author.request("DELETE", "/materials/" + byAuthor, Map.of("expectedVersion", 1), 200);
		assertMatches(S + "MaterialDeleted", deleted);
		assertThat(deleted.get("tombstone").get("version").asInt()).isEqualTo(2);
		// 소유자는 다른 팀원의 자료도 지울 수 있다
		owner.request("DELETE", "/materials/" + byOwner, Map.of("expectedVersion", 1), 200);

		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").isEmpty()).isTrue();
		for (String path : List.of("", "/source", "/note")) {
			assertThat(author.request("GET", "/materials/" + byAuthor + path, null, 410).get("error").get("code").asString())
				.isEqualTo("MATERIAL_DELETED");
		}
		author.request("PATCH", "/materials/" + byAuthor, Map.of("expectedVersion", 2, "title", "삭제 뒤 수정"), 410);
		author.request("PUT", "/materials/" + byAuthor + "/note", Map.of("expectedVersion", 1, "markdown", "삭제 뒤"), 410);
		// 삭제 응답을 받지 못해 다시 보내면 이미 삭제됐다고 알린다
		author.request("DELETE", "/materials/" + byAuthor, Map.of("expectedVersion", 1), 410);
		// 개정과 팀 메모는 지우지 않는다
		assertThat(count("material_revisions", byAuthor)).isEqualTo(1);
		assertThat(count("material_notes", byAuthor)).isEqualTo(1);
	}

	@Test
	void otherProjectsAndRemovedMembersCannotReachMaterials() throws Exception {
		Browser owner = signup("권한 소유자");
		Browser member = signup("권한 팀원");
		Browser stranger = signup("권한 외부인");
		String projectId = createProject(owner, "권한 " + UUID.randomUUID());
		String otherProjectId = createProject(stranger, "다른 프로젝트 " + UUID.randomUUID());
		join(owner, member, projectId);
		JsonNode material = memo(member, projectId, "팀 자료", "팀 본문");
		String id = material.get("id").asString();
		String revisionId = material.get("currentRevision").get("id").asString();
		String otherId = memo(stranger, otherProjectId, "다른 팀 자료", "다른 본문").get("id").asString();

		// 식별자를 알아도 다른 프로젝트의 자료는 없는 것으로 응답한다
		stranger.request("GET", "/projects/" + projectId + "/materials", null, 404);
		stranger.request("GET", "/materials/" + id, null, 404);
		stranger.request("GET", "/materials/" + id + "/source", null, 404);
		stranger.request("GET", "/materials/" + id + "/note", null, 404);
		stranger.request("PATCH", "/materials/" + id, Map.of("expectedVersion", 1, "title", "무단 수정"), 404);
		stranger.request("DELETE", "/materials/" + id, Map.of("expectedVersion", 1), 404);
		stranger.request("PUT", "/materials/" + id + "/note", Map.of("expectedVersion", 0, "markdown", "무단 메모"), 404);
		stranger.post("/projects/" + projectId + "/materials/memo", Map.of("title", "무단 등록", "markdown", ""), 404);
		// 내 프로젝트 자료에 다른 프로젝트의 개정 식별자를 넣어도 읽을 수 없다
		stranger.request("GET", "/materials/" + otherId + "/source?revisionId=" + revisionId, null, 404);
		new Browser().request("GET", "/materials/" + id, null, 401);
		assertThat(member.get("/materials/" + id).get("title").asString()).isEqualTo("팀 자료");

		// 제거된 팀원은 기존 주소로 다시 접근할 수 없다
		removeMember(owner, projectId, member.userId);
		member.request("GET", "/projects/" + projectId + "/materials", null, 403);
		member.request("GET", "/materials/" + id, null, 403);
		member.request("GET", "/materials/" + id + "/source", null, 403);
		member.request("GET", "/materials/" + id + "/note", null, 403);
		member.request("PATCH", "/materials/" + id, Map.of("expectedVersion", 1, "title", "제거 뒤 수정"), 403);
		member.request("DELETE", "/materials/" + id, Map.of("expectedVersion", 1), 403);
		member.request("PUT", "/materials/" + id + "/note", Map.of("expectedVersion", 0, "markdown", "제거 뒤 메모"), 403);
		member.post("/projects/" + projectId + "/materials/memo", Map.of("title", "제거 뒤 등록", "markdown", ""), 403);
		member.bootstrap();
		member.request("GET", "/materials/" + id, null, 403);

		// 작성자가 제거돼도 자료와 작성자 표시는 남는다
		JsonNode kept = owner.get("/materials/" + id);
		assertThat(kept.get("createdBy").get("name").asString()).isEqualTo("권한 팀원");
		assertThat(kept.get("version").asInt()).isEqualTo(1);
	}

	@Test
	void teamNoteSavesWithVersionCheckAndLeavesTheMaterialUnchanged() throws Exception {
		Browser owner = signup("메모 소유자");
		Browser member = signup("메모 팀원");
		String projectId = createProject(owner, "팀 메모 " + UUID.randomUUID());
		join(owner, member, projectId);
		String id = memo(owner, projectId, "메모 대상", "원문").get("id").asString();
		String path = "/materials/" + id + "/note";

		JsonNode empty = member.get(path);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MaterialNote", json.writeValueAsString(empty))).isEmpty();
		assertThat(empty.get("version").asInt()).isZero();
		assertThat(empty.get("markdown").asString()).isEmpty();

		JsonNode saved = member.request("PUT", path, Map.of("expectedVersion", 0, "markdown", "반납함 동선을 함께 확인"), 200);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MaterialNoteSaveResponse", json.writeValueAsString(saved)))
			.isEmpty();
		assertThat(saved.get("version").asInt()).isEqualTo(1);
		JsonNode note = owner.get(path);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MaterialNote", json.writeValueAsString(note))).isEmpty();
		assertThat(note.get("markdown").asString()).isEqualTo("반납함 동선을 함께 확인");
		assertThat(note.get("updatedByName").asString()).isEqualTo("메모 팀원");

		// 그사이 다른 팀원이 저장했으면 덮어쓰지 않는다
		JsonNode conflict = owner.request("PUT", path, Map.of("expectedVersion", 0, "markdown", "늦은 저장"), 409);
		assertThat(conflict.get("error").get("currentVersions").get("noteVersion").asInt()).isEqualTo(1);
		assertThat(owner.get(path).get("markdown").asString()).isEqualTo("반납함 동선을 함께 확인");
		assertThat(owner.request("PUT", path, Map.of("expectedVersion", 1, "markdown", "소유자가 고침"), 200).get("version").asInt())
			.isEqualTo(2);
		owner.request("PUT", path, Map.of("expectedVersion", 2, "markdown", "가".repeat(20_001)), 422);
		owner.request("PUT", path, Map.of("markdown", "버전 없음"), 422);

		// 팀 메모는 원문과 다른 자원이라 자료의 버전과 개정을 바꾸지 않는다
		JsonNode material = owner.get("/materials/" + id);
		assertThat(material.get("version").asInt()).isEqualTo(1);
		assertThat(material.get("currentRevision").get("sourceVersion").asInt()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM project_events WHERE type = 'material.note.updated' AND resource_id = UUID_TO_BIN(?)")
			.param(id)
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void commentsAreListedOldestFirstAndOnlyTheAuthorDeletesThem() throws Exception {
		Browser owner = signup("댓글 소유자");
		Browser member = signup("댓글 팀원");
		Browser stranger = signup("댓글 외부인");
		String projectId = createProject(owner, "자료 댓글 " + UUID.randomUUID());
		join(owner, member, projectId);
		String id = memo(owner, projectId, "댓글 대상", "원문").get("id").asString();
		String path = "/materials/" + id + "/comments";
		assertMatches("api.schema.json#/$defs/MaterialCommentList", member.get(path));

		String key = UUID.randomUUID().toString();
		JsonNode first = member.request("POST", path, Map.of("body", "  12쪽 표를 2장에 넣으면 좋겠어요  "), 201, key);
		assertMatches("api.schema.json#/$defs/MaterialComment", first);
		assertThat(first.get("body").asString()).isEqualTo("12쪽 표를 2장에 넣으면 좋겠어요");
		assertThat(first.get("author").get("name").asString()).isEqualTo("댓글 팀원");
		// 응답을 받지 못해 같은 키로 다시 보내도 댓글은 하나다
		assertThat(member.request("POST", path, Map.of("body", "  12쪽 표를 2장에 넣으면 좋겠어요  "), 201, key)).isEqualTo(first);
		member.request("POST", path, Map.of("body", "다른 본문"), 409, key);
		member.request("POST", path, Map.of("body", "키 없음"), 400, null);
		member.request("POST", path, Map.of("body", "   "), 422, UUID.randomUUID().toString());
		member.request("POST", path, Map.of("body", "가".repeat(2001)), 422, UUID.randomUUID().toString());
		owner.request("POST", path, Map.of("body", "좋아요, 그렇게 할게요"), 201, UUID.randomUUID().toString());
		owner.request("POST", path, Map.of("body", "출처도 같이 적을게요"), 201, UUID.randomUUID().toString());

		JsonNode page = owner.get(path + "?limit=2");
		assertMatches("api.schema.json#/$defs/MaterialCommentList", page);
		assertThat(page.get("items")).hasSize(2);
		assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(first.get("id").asString());
		JsonNode rest = owner.get(path + "?limit=2&cursor=" + page.get("nextCursor").asString());
		assertThat(rest.get("items")).hasSize(1);
		assertThat(rest.get("items").get(0).get("body").asString()).isEqualTo("출처도 같이 적을게요");
		assertThat(rest.get("nextCursor").isNull()).isTrue();

		String comment = path + "/" + first.get("id").asString();
		owner.request("DELETE", comment, null, 403);
		JsonNode deleted = member.request("DELETE", comment, null, 200);
		assertMatches("api.schema.json#/$defs/MaterialCommentDeleted", deleted);
		assertThat(member.request("DELETE", comment, null, 200)).isEqualTo(deleted);
		member.request("DELETE", path + "/" + UUID.randomUUID(), null, 404);
		assertThat(owner.get(path).get("items")).hasSize(2);
		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM project_events
				WHERE type IN ('material.comment.created', 'material.comment.deleted') AND resource_id = UUID_TO_BIN(?)
				""").param(id).query(Integer.class).single()).isEqualTo(4);

		stranger.request("GET", path, null, 404);
		stranger.request("POST", path, Map.of("body", "외부 댓글"), 404, UUID.randomUUID().toString());
		removeMember(owner, projectId, member.userId);
		member.request("GET", path, null, 403);
		member.request("POST", path, Map.of("body", "제거 뒤 댓글"), 403, UUID.randomUUID().toString());
		owner.request("DELETE", "/materials/" + id, Map.of("expectedVersion", owner.get("/materials/" + id).get("version").asInt()), 200);
		owner.request("GET", path, null, 410);
	}

	@Test
	void concurrentNoteSavesWithTheSameVersionLetOnlyOneThrough() throws Exception {
		Browser owner = signup("동시 저장 소유자");
		Browser member = signup("동시 저장 팀원");
		String projectId = createProject(owner, "동시 저장 " + UUID.randomUUID());
		join(owner, member, projectId);
		String id = memo(owner, projectId, "동시 저장 대상", "원문").get("id").asString();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (Browser browser : List.of(owner, member)) {
			results.add(pool.submit(() -> {
				start.await();
				return browser
					.raw("PUT", "/v1/materials/" + id + "/note",
							json.writeValueAsBytes(Map.of("expectedVersion", 0, "markdown", "저장 " + browser.userId)),
							Map.of("Content-Type", "application/json", "Origin", "http://localhost:5174", "X-CSRF-Token",
									browser.bootstrap().get("csrfToken").asString()))
					.statusCode();
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get(15, TimeUnit.SECONDS));
		}
		pool.shutdown();
		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(owner.get("/materials/" + id + "/note").get("version").asInt()).isEqualTo(1);
	}

	@Test
	void materialChangesAreRecordedAsProjectEvents() throws Exception {
		Browser owner = signup("이벤트 소유자");
		String projectId = createProject(owner, "이벤트 " + UUID.randomUUID());
		String id = memo(owner, projectId, "이벤트 대상", "본문").get("id").asString();
		owner.request("PATCH", "/materials/" + id, Map.of("expectedVersion", 1, "title", "바꾼 이름"), 200);
		JsonNode deleted = owner.request("DELETE", "/materials/" + id, Map.of("expectedVersion", 2), 200);
		List<String> types = jdbc
			.sql("SELECT CONCAT(type, ':', payload ->> '$.materialVersion') FROM project_events WHERE project_id = UUID_TO_BIN(?) ORDER BY seq")
			.param(projectId)
			.query(String.class)
			.list();
		assertThat(types).containsExactly("material.updated:1", "material.updated:2", "material.deleted:3");
		assertThat(deleted.get("eventId").asString()).isEqualTo("3");
	}

}
