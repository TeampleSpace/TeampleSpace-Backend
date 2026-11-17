package com.teamplespace.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.presentation.application.ElementPatchService;
import com.teamplespace.presentation.application.ElementPatchService.Result;
import com.teamplespace.review.application.ChangeGroups;
import com.teamplespace.review.application.ChangeGroups.View;
import com.teamplespace.support.DocumentFixture;
import com.teamplespace.support.MutableClock;
import com.teamplespace.support.TestClockConfig;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 연속 저장을 묶는 규칙과 묶음 종료 규칙을 실제 MySQL에서 확인
// 서버 시각은 테스트용 시계로 옮긴다
@SpringBootTest
@Import(TestClockConfig.class)
class EditGroupTests {

	static final Duration SAVE_INTERVAL = Duration.ofMillis(600);

	@Autowired
	ElementPatchService service;

	@Autowired
	ChangeGroups groups;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	DocumentFixture fixture;

	Actor userA;

	Actor userB;

	UUID elementA;

	UUID elementB;

	int requests;

	@BeforeEach
	void setUp() {
		fixture = new DocumentFixture(jdbc);
		fixture.reset();
		userA = fixture.userA;
		userB = fixture.userB;
		elementA = fixture.elementA;
		elementB = fixture.elementB;
	}

	@Test
	void savesEvery600msBecomeOneChange() {
		UUID group = UUID.randomUUID();
		UUID changeId = null;
		for (int i = 1; i <= 10; i++) {
			Result saved = save(userA, elementA, group, i, "입력 " + i);
			assertThat(saved.status()).isEqualTo(200);
			changeId = changeOf(saved);
			clock.advance(SAVE_INTERVAL);
		}

		// 저장은 열 번이지만 검토할 변경은 하나다
		assertThat(fixture.count("document_changes")).isEqualTo(1);
		assertThat(fixture.count("change_targets")).isEqualTo(1);
		assertThat(fixture.events("change.created")).isEqualTo(1);
		assertThat(fixture.events("element.updated")).isEqualTo(10);
		assertThat(fixture.events("change.finalized")).isZero();
		// 처음 이전 내용과 마지막 이후 내용을 가진다
		View view = groups.read(userB, changeId);
		assertThat(view.groupState()).isEqualTo("open");
		assertThat(view.beforeVersion()).isEqualTo(1);
		assertThat(textOf(view.beforeState())).isEqualTo("처음 내용");
		assertThat(view.afterVersion()).isEqualTo(11);
		assertThat(textOf(view.afterState())).isEqualTo("입력 10");
	}

	@Test
	void openGroupCannotBeApproved() {
		UUID changeId = changeOf(save(userA, elementA, UUID.randomUUID(), 1, "입력"));

		ApiException notFinalized = catchThrowableOfType(ApiException.class,
				() -> groups.requireFinalized(userB, changeId));

		assertThat(notFinalized.code()).isEqualTo(ErrorCode.CHANGE_NOT_FINALIZED);
	}

	@Test
	void twoSecondsWithoutInputClosesTheGroup() {
		UUID group = UUID.randomUUID();
		UUID changeId = changeOf(save(userA, elementA, group, 1, "입력 1"));
		clock.advance(Duration.ofMillis(1999));
		// 2초가 되기 전의 저장은 같은 묶음에 이어진다
		assertThat(save(userA, elementA, group, 2, "입력 2").status()).isEqualTo(200);
		LocalDateTime lastSaved = LocalDateTime.now(clock);
		clock.advance(Duration.ofSeconds(2));

		// 브라우저가 닫는 요청을 보내지 못해도 변경을 조회할 때 닫힌다
		View view = groups.read(userB, changeId);
		groups.read(userB, changeId);

		assertThat(view.groupState()).isEqualTo("closed");
		assertThat(closedAt(changeId)).isEqualTo(lastSaved.plusSeconds(2));
		assertThat(fixture.events("change.finalized")).isEqualTo(1);
		groups.requireFinalized(userB, changeId);
		// 닫힌 묶음을 다시 쓰면 거절하고 문서는 그대로다
		Result late = save(userA, elementA, group, 3, "늦은 입력");
		assertThat(codeOf(late)).isEqualTo("EDIT_GROUP_CLOSED");
		assertThat(late.body().get("error").get("currentVersions").get("elementVersion").asLong()).isEqualTo(3);
		assertThat(fixture.text(elementA)).isEqualTo("입력 2");
		// 새 묶음으로 보내면 새 변경이 된다
		assertThat(save(userA, elementA, UUID.randomUUID(), 3, "새 묶음").status()).isEqualTo(200);
		assertThat(fixture.count("document_changes")).isEqualTo(2);
	}

	@Test
	void idleGroupIsClosedByTheNextWrite() {
		UUID group = UUID.randomUUID();
		UUID changeId = changeOf(save(userA, elementA, group, 1, "입력 1"));
		clock.advance(Duration.ofSeconds(2));

		Result late = save(userA, elementA, group, 2, "2초 뒤 입력");

		assertThat(codeOf(late)).isEqualTo("EDIT_GROUP_CLOSED");
		assertThat(state(changeId)).isEqualTo("closed");
		assertThat(fixture.events("change.finalized")).isEqualTo(1);
		assertThat(fixture.text(elementA)).isEqualTo("입력 1");
	}

	@Test
	void groupClosesThirtySecondsAfterItOpened() {
		UUID group = UUID.randomUUID();
		LocalDateTime opened = LocalDateTime.now(clock);
		UUID changeId = null;
		// 600ms 간격으로 쉬지 않고 입력해 29.4초까지 같은 묶음에 쌓는다
		for (int i = 1; i <= 50; i++) {
			Result saved = save(userA, elementA, group, i, "입력 " + i);
			assertThat(saved.status()).as("저장 " + i).isEqualTo(200);
			changeId = changeOf(saved);
			clock.advance(SAVE_INTERVAL);
		}

		// 30초가 된 시점의 저장은 거절되고 묶음은 30초 지점에서 닫힌다
		Result late = save(userA, elementA, group, 51, "30초 뒤 입력");

		assertThat(codeOf(late)).isEqualTo("EDIT_GROUP_CLOSED");
		assertThat(state(changeId)).isEqualTo("closed");
		assertThat(closedAt(changeId)).isEqualTo(opened.plusSeconds(30));
		assertThat(fixture.count("document_changes")).isEqualTo(1);
		assertThat(groups.read(userB, changeId).afterVersion()).isEqualTo(51);
		assertThat(fixture.text(elementA)).isEqualTo("입력 50");
	}

	@Test
	void finalizeRequestClosesTheGroupWithoutChangingContent() {
		UUID group = UUID.randomUUID();
		UUID changeId = changeOf(save(userA, elementA, group, 1, "입력"));
		// 입력란을 벗어나거나 사용자가 편집을 끝내면 patch 없이 묶음만 닫는다
		ObjectNode request = request(group, 2, "mut-close");
		request.put("finalizeGroup", true);

		Result closed = service.patch(userA, fixture.slideId, elementA, "key-close", request);
		Result retry = service.patch(userA, fixture.slideId, elementA, "key-close", request);

		assertThat(closed.status()).isEqualTo(200);
		assertThat(changeOf(closed)).isEqualTo(changeId);
		assertThat(closed.body().get("elementVersion").asLong()).isEqualTo(2);
		assertThat(fixture.version("slide_elements", elementA)).isEqualTo(2);
		assertThat(fixture.version("presentations", fixture.presentationId)).isEqualTo(2);
		assertThat(state(changeId)).isEqualTo("closed");
		assertThat(fixture.events("element.updated")).isEqualTo(1);
		assertThat(fixture.events("change.finalized")).isEqualTo(1);
		assertThat(retry.replayed()).isTrue();
		assertThat(CanonicalJson.write(retry.body())).isEqualTo(CanonicalJson.write(closed.body()));
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ElementPatchRequest", request.toString()))
			.isEmpty();
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MutationResponse", closed.body().toString()))
			.isEmpty();
		assertThat(codeOf(save(userA, elementA, group, 2, "닫힌 뒤 입력"))).isEqualTo("EDIT_GROUP_CLOSED");
	}

	@Test
	void lastSaveCanCloseTheGroupInTheSameRequest() {
		UUID group = UUID.randomUUID();
		ObjectNode request = request(group, 1, "mut-last");
		request.putObject("patch").putObject("content").put("text", "마지막 입력");
		request.put("finalizeGroup", true);

		Result saved = service.patch(userA, fixture.slideId, elementA, "key-last", request);

		assertThat(saved.status()).isEqualTo(200);
		assertThat(fixture.text(elementA)).isEqualTo("마지막 입력");
		assertThat(state(changeOf(saved))).isEqualTo("closed");
		assertThat(fixture.events("change.created")).isEqualTo(1);
		assertThat(fixture.events("element.updated")).isEqualTo(1);
		assertThat(fixture.events("change.finalized")).isEqualTo(1);
	}

	@Test
	void movingToAnotherElementClosesThePreviousGroup() {
		UUID first = changeOf(save(userA, elementA, UUID.randomUUID(), 1, "A 요소 입력"));

		UUID second = changeOf(save(userA, elementB, UUID.randomUUID(), 1, "B 요소 입력"));

		assertThat(state(first)).isEqualTo("closed");
		assertThat(state(second)).isEqualTo("open");
		// 같은 사용자라도 다른 편집 세션의 묶음은 닫지 않는다
		Actor otherTab = new Actor(userA.userId(), UUID.randomUUID());
		UUID third = changeOf(save(otherTab, elementA, UUID.randomUUID(), 2, "다른 탭 입력"));
		assertThat(state(second)).isEqualTo("open");
		assertThat(state(third)).isEqualTo("open");
	}

	@Test
	void anotherAuthorEditingTheSameElementClosesTheGroup() {
		UUID mineGroup = UUID.randomUUID();
		UUID mine = changeOf(save(userA, elementA, mineGroup, 1, "A의 입력"));

		Result theirs = save(userB, elementA, UUID.randomUUID(), 2, "B의 입력");

		assertThat(theirs.status()).isEqualTo(200);
		assertThat(state(mine)).isEqualTo("closed");
		assertThat(state(changeOf(theirs))).isEqualTo("open");
		// 예전 버전으로 이어 쓰면 충돌이고 최신 버전에 맞춰도 닫힌 묶음은 다시 쓸 수 없다
		assertThat(codeOf(save(userA, elementA, mineGroup, 2, "A의 다음 입력"))).isEqualTo("VERSION_CONFLICT");
		assertThat(codeOf(save(userA, elementA, mineGroup, 3, "A의 다음 입력"))).isEqualTo("EDIT_GROUP_CLOSED");
		assertThat(fixture.text(elementA)).isEqualTo("B의 입력");
	}

	@Test
	void closedGroupKeepsItsBeforeAndAfter() {
		UUID firstGroup = UUID.randomUUID();
		UUID first = changeOf(save(userA, elementA, firstGroup, 1, "첫 묶음 1"));
		save(userA, elementA, firstGroup, 2, "첫 묶음 2");
		clock.advance(Duration.ofSeconds(2));
		View closed = groups.read(userB, first);

		UUID second = changeOf(save(userA, elementA, UUID.randomUUID(), 3, "둘째 묶음"));

		View unchanged = groups.read(userB, first);
		assertThat(unchanged.groupState()).isEqualTo("closed");
		assertThat(unchanged.beforeVersion()).isEqualTo(1);
		assertThat(unchanged.afterVersion()).isEqualTo(3);
		assertThat(textOf(unchanged.beforeState())).isEqualTo("처음 내용");
		assertThat(textOf(unchanged.afterState())).isEqualTo("첫 묶음 2");
		assertThat(CanonicalJson.write(unchanged.afterState())).isEqualTo(CanonicalJson.write(closed.afterState()));
		// 이후 수정은 새 묶음이고 앞 묶음의 마지막 내용에서 시작한다
		View next = groups.read(userB, second);
		assertThat(next.beforeVersion()).isEqualTo(3);
		assertThat(textOf(next.beforeState())).isEqualTo("첫 묶음 2");
		assertThat(next.afterVersion()).isEqualTo(4);
	}

	@Test
	void groupBelongsToItsAuthorSessionAndElement() {
		UUID group = UUID.randomUUID();
		save(userA, elementA, group, 1, "입력");

		// 다른 요소나 다른 편집 세션이 같은 묶음 식별자를 쓸 수 없다
		assertThat(errorOf(() -> save(userA, elementB, group, 1, "다른 요소"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
		Actor otherTab = new Actor(userA.userId(), UUID.randomUUID());
		assertThat(errorOf(() -> save(otherTab, elementA, group, 2, "다른 탭"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
		// 없는 묶음은 닫을 수 없다
		ObjectNode request = request(UUID.randomUUID(), 2, "mut-close");
		request.put("finalizeGroup", true);
		assertThat(errorOf(() -> service.patch(userA, fixture.slideId, elementA, "key-close", request)))
			.isEqualTo(ErrorCode.VALIDATION_FAILED);
		assertThat(fixture.text(elementA)).isEqualTo("입력");
	}

	// 실제 편집처럼 저장마다 새 멱등키와 변경 식별자를 쓴다
	Result save(Actor actor, UUID element, UUID group, long baseVersion, String text) {
		requests++;
		ObjectNode request = request(group, baseVersion, "mut-" + requests);
		request.putObject("patch").putObject("content").put("text", text);
		return service.patch(actor, fixture.slideId, element, "key-" + requests, request);
	}

	ObjectNode request(UUID group, long baseVersion, String mutationId) {
		ObjectNode request = json.createObjectNode();
		request.put("generation", 1);
		request.put("baseVersion", baseVersion);
		request.put("editGroupId", group.toString());
		request.put("clientMutationId", mutationId);
		return request;
	}

	UUID changeOf(Result result) {
		return UUID.fromString(result.body().get("changeId").asString());
	}

	String codeOf(Result result) {
		return result.body().get("error").get("code").asString();
	}

	String textOf(tools.jackson.databind.JsonNode state) {
		return state.get("content").get("text").asString();
	}

	ErrorCode errorOf(Runnable call) {
		return catchThrowableOfType(ApiException.class, call::run).code();
	}

	String state(UUID changeId) {
		return jdbc.sql("SELECT group_state FROM document_changes WHERE id = UUID_TO_BIN(?)")
			.param(changeId.toString())
			.query(String.class)
			.single();
	}

	LocalDateTime closedAt(UUID changeId) {
		return jdbc.sql("SELECT closed_at FROM document_changes WHERE id = UUID_TO_BIN(?)")
			.param(changeId.toString())
			.query(LocalDateTime.class)
			.single();
	}

}
