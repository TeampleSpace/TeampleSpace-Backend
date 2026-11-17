package com.teamplespace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.presentation.application.ElementPatchService;
import com.teamplespace.presentation.application.ElementPatchService.Result;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 멱등 재시도, 버전 충돌, 원자적 저장을 실제 MySQL에서 확인
// 운영 인증이 없으므로 사용자는 테스트가 만든 식별자다
@SpringBootTest
class ElementPatchServiceTests {

	@Autowired
	ElementPatchService service;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	ObjectMapper json;

	final UUID projectId = UUID.randomUUID();

	final UUID presentationId = UUID.randomUUID();

	final UUID slideId = UUID.randomUUID();

	final UUID elementA = UUID.randomUUID();

	final UUID elementB = UUID.randomUUID();

	final UUID userA = UUID.randomUUID();

	final UUID userB = UUID.randomUUID();

	final UUID editGroup = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		for (String table : List.of("idempotency_records", "project_events", "slide_elements", "slides",
				"presentations", "project_memberships", "projects")) {
			jdbc.sql("DELETE FROM " + table).update();
		}
		jdbc.sql("INSERT INTO projects (id, name) VALUES (UUID_TO_BIN(?), '검증용 프로젝트')")
			.param(projectId.toString())
			.update();
		for (UUID user : List.of(userA, userB)) {
			jdbc.sql("INSERT INTO project_memberships (project_id, user_id, state)"
					+ " VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'active')")
				.params(projectId.toString(), user.toString())
				.update();
		}
		jdbc.sql("INSERT INTO presentations (id, project_id, schema_version, canvas_width, canvas_height, canvas_unit)"
				+ " VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 1, 1280, 720, 'px')")
			.params(presentationId.toString(), projectId.toString())
			.update();
		jdbc.sql("INSERT INTO slides (id, project_id, presentation_id, generation, position)"
				+ " VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 1, 0)")
			.params(slideId.toString(), projectId.toString(), presentationId.toString())
			.update();
		for (UUID element : List.of(elementA, elementB)) {
			jdbc.sql("""
					INSERT INTO slide_elements (id, project_id, slide_id, type, x, y, width, height, content, style)
					VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 'text', 80, 64, 620, 236.25,
					        JSON_OBJECT('text', '처음 내용'),
					        JSON_OBJECT('fontFamily', 'Pretendard', 'fontSize', 22, 'fontWeight', 400,
					                    'color', '#1F2933', 'align', 'left', 'lineHeight', 1.6))
					""").params(element.toString(), projectId.toString(), slideId.toString()).update();
		}
	}

	@Test
	void sameKeyAndBodyReturnsFirstResultWithoutConflict() {
		Result first = patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정"));
		// 응답을 받지 못한 클라이언트가 기준 버전 1 그대로 다시 보낸다
		Result retry = patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정"));

		assertThat(first.status()).isEqualTo(200);
		assertThat(first.replayed()).isFalse();
		assertThat(retry.status()).isEqualTo(200);
		assertThat(retry.replayed()).isTrue();
		assertThat(CanonicalJson.write(retry.body())).isEqualTo(CanonicalJson.write(first.body()));
		assertThat(elementVersion(elementA)).isEqualTo(2);
		assertThat(count("project_events")).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentBodyIsRejected() {
		patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정"));

		assertThat(errorOf(() -> patch(userA, elementA, "key-1", body(1, "mut-1", "다른 내용"))))
			.isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(elementVersion(elementA)).isEqualTo(2);
		assertThat(text(elementA)).isEqualTo("첫 수정");
		assertThat(count("project_events")).isEqualTo(1);
	}

	@Test
	void staleBaseVersionConflictsAndSameKeyReturnsTheSameConflict() {
		patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정"));

		Result conflict = patch(userB, elementA, "key-2", body(1, "mut-2", "늦은 수정"));
		Result retry = patch(userB, elementA, "key-2", body(1, "mut-2", "늦은 수정"));

		assertThat(conflict.status()).isEqualTo(409);
		JsonNode error = conflict.body().get("error");
		assertThat(error.get("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(error.get("currentVersions").get("elementVersion").asLong()).isEqualTo(2);
		assertThat(error.get("currentVersions").get("slideVersion").asLong()).isEqualTo(2);
		assertThat(error.get("currentVersions").get("presentationVersion").asLong()).isEqualTo(2);
		assertThat(error.get("currentVersions").get("generation").asLong()).isEqualTo(1);
		assertThat(retry.status()).isEqualTo(409);
		assertThat(retry.replayed()).isTrue();
		assertThat(CanonicalJson.write(retry.body())).isEqualTo(CanonicalJson.write(conflict.body()));
		assertThat(text(elementA)).isEqualTo("첫 수정");
		assertThat(count("project_events")).isEqualTo(1);
	}

	@Test
	void otherGenerationConflicts() {
		ObjectNode body = body(1, "mut-1", "첫 수정");
		body.put("generation", 2);

		Result conflict = patch(userA, elementA, "key-1", body);

		assertThat(conflict.status()).isEqualTo(409);
		assertThat(conflict.body().get("error").get("currentVersions").get("generation").asLong()).isEqualTo(1);
		assertThat(elementVersion(elementA)).isEqualTo(1);
	}

	@Test
	void sameKeyStillBeingProcessedIsReportedAsInProgress() throws Exception {
		IdempotencyStore.Key key = IdempotencyStore.Key.of(userA, "PATCH",
				"/v1/slides/" + slideId + "/elements/" + elementA, "key-1");
		ObjectNode body = body(1, "mut-1", "첫 수정");
		// 같은 키의 요청이 아직 커밋하지 않은 상태를 다른 연결로 만든다
		try (Connection other = dataSource.getConnection()) {
			other.setAutoCommit(false);
			try (PreparedStatement insert = other.prepareStatement("""
					INSERT INTO idempotency_records (id, actor_scope, route_key, key_hash, request_hash, state, expires_at)
					VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, 'processing', DATE_ADD(NOW(6), INTERVAL 1 DAY))
					""")) {
				insert.setString(1, key.actorScope());
				insert.setString(2, key.routeKey());
				insert.setBytes(3, key.keyHash());
				insert.setBytes(4, CanonicalJson.sha256(body));
				insert.executeUpdate();
			}

			ApiException inProgress = catchThrowableOfType(ApiException.class,
					() -> patch(userA, elementA, "key-1", body));

			assertThat(inProgress.code()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS);
			assertThat(inProgress.retryAfterSeconds()).isEqualTo(IdempotencyStore.RETRY_AFTER_SECONDS);
			assertThat(elementVersion(elementA)).isEqualTo(1);
			other.rollback();
		}

		// 앞선 요청이 끝난 뒤 같은 키로 다시 보내면 처리된다
		assertThat(patch(userA, elementA, "key-1", body).status()).isEqualTo(200);
	}

	@Test
	void sameRequestSentTwiceAtOnceAppliesOnce() throws Exception {
		ObjectNode body = body(1, "mut-1", "첫 수정");
		Callable<Object> send = () -> {
			try {
				return patch(userA, elementA, "key-1", body).status();
			}
			catch (ApiException ex) {
				return ex.code();
			}
		};

		List<Object> outcomes = together(send, send);

		// 늦은 쪽은 처리 중 응답을 받거나 먼저 끝난 결과를 그대로 받는다
		assertThat(outcomes).contains(200);
		assertThat(outcomes).allMatch(outcome -> outcome.equals(200) || outcome == ErrorCode.REQUEST_IN_PROGRESS);
		assertThat(elementVersion(elementA)).isEqualTo(2);
		assertThat(count("project_events")).isEqualTo(1);
	}

	@Test
	void failureWhileSavingLeavesNothingBehind() {
		// 이벤트 기록 단계가 실패하도록 다음 순번을 미리 차지한다
		jdbc.sql("INSERT INTO project_events (project_id, seq, type, payload, occurred_at)"
				+ " VALUES (UUID_TO_BIN(?), 1, 'test.blocker', JSON_OBJECT(), NOW(6))")
			.param(projectId.toString())
			.update();

		assertThatThrownBy(() -> patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정")))
			.isInstanceOf(DuplicateKeyException.class);

		assertThat(elementVersion(elementA)).isEqualTo(1);
		assertThat(text(elementA)).isEqualTo("처음 내용");
		assertThat(version("slides", slideId)).isEqualTo(1);
		assertThat(version("presentations", presentationId)).isEqualTo(1);
		assertThat(eventSeq()).isZero();
		assertThat(count("idempotency_records")).isZero();

		// 같은 키로 다시 보내면 한 번만 반영된다
		jdbc.sql("DELETE FROM project_events").update();
		assertThat(patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정")).status()).isEqualTo(200);
		assertThat(elementVersion(elementA)).isEqualTo(2);
		assertThat(count("project_events")).isEqualTo(1);
		assertThat(count("idempotency_records")).isEqualTo(1);
	}

	@Test
	void sameElementFromTwoSessionsAppliesOnlyOne() throws Exception {
		List<Result> results = together(() -> patch(userA, elementA, "key-a", body(1, "mut-a", "A의 수정")),
				() -> patch(userB, elementA, "key-b", body(1, "mut-b", "B의 수정")));

		assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
		Result winner = results.stream().filter(result -> result.status() == 200).findFirst().orElseThrow();
		assertThat(elementVersion(elementA)).isEqualTo(2);
		assertThat(text(elementA)).isEqualTo(winner.body().get("resource").get("content").get("text").asString());
		assertThat(count("project_events")).isEqualTo(1);
	}

	@Test
	void differentElementsFromTwoSessionsBothApply() throws Exception {
		List<Result> results = together(() -> patch(userA, elementA, "key-a", body(1, "mut-a", "A의 수정")),
				() -> patch(userB, elementB, "key-b", body(1, "mut-b", "B의 수정")));

		assertThat(results).extracting(Result::status).containsExactly(200, 200);
		assertThat(elementVersion(elementA)).isEqualTo(2);
		assertThat(elementVersion(elementB)).isEqualTo(2);
		assertThat(version("slides", slideId)).isEqualTo(3);
		assertThat(version("presentations", presentationId)).isEqualTo(3);
		assertThat(eventSeq()).isEqualTo(2);
		// 이벤트 순번과 문서 버전이 커밋 순서대로 함께 오른다
		assertThat(results).extracting(result -> result.body().get("eventId").asString())
			.containsExactlyInAnyOrder("1", "2");
		for (Result result : results) {
			long seq = Long.parseLong(result.body().get("eventId").asString());
			assertThat(result.body().get("elementVersion").asLong()).isEqualTo(2);
			assertThat(result.body().get("slideVersion").asLong()).isEqualTo(seq + 1);
			assertThat(result.body().get("presentationVersion").asLong()).isEqualTo(seq + 1);
		}
	}

	@Test
	void removedMemberCannotReadOldResultWithSameKey() {
		patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정"));
		jdbc.sql("UPDATE project_memberships SET state = 'removed' WHERE user_id = UUID_TO_BIN(?)")
			.param(userA.toString())
			.update();

		assertThat(errorOf(() -> patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정"))))
			.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	void clientMutationIdDoesNotReplaceIdempotencyKey() {
		Result first = patch(userA, elementA, "key-1", body(1, "mut-1", "첫 수정"));
		// 상관관계 값이 같아도 멱등키가 다르면 새 요청이다
		Result second = patch(userA, elementA, "key-2", body(2, "mut-1", "둘째 수정"));

		assertThat(first.status()).isEqualTo(200);
		assertThat(second.status()).isEqualTo(200);
		assertThat(second.replayed()).isFalse();
		assertThat(elementVersion(elementA)).isEqualTo(3);
		assertThat(count("project_events")).isEqualTo(2);
	}

	@Test
	void requestsWithoutKeyOrAccessAreRejected() {
		assertThat(errorOf(() -> patch(userA, elementA, " ", body(1, "mut-1", "첫 수정"))))
			.isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		assertThat(errorOf(() -> patch(UUID.randomUUID(), elementA, "key-1", body(1, "mut-1", "첫 수정"))))
			.isEqualTo(ErrorCode.FORBIDDEN);
		assertThat(errorOf(() -> patch(userA, UUID.randomUUID(), "key-1", body(1, "mut-1", "첫 수정"))))
			.isEqualTo(ErrorCode.NOT_FOUND);
		ObjectNode unknownField = body(1, "mut-1", "첫 수정");
		unknownField.put("force", true);
		assertThat(errorOf(() -> patch(userA, elementA, "key-1", unknownField)))
			.isEqualTo(ErrorCode.VALIDATION_FAILED);
		assertThat(elementVersion(elementA)).isEqualTo(1);
		assertThat(count("idempotency_records")).isZero();
	}

	@Test
	void responsesFollowTheSharedContract() {
		ObjectNode body = body(1, "mut-1", "첫 수정");
		Result success = patch(userA, elementA, "key-1", body);
		ObjectNode conflict = (ObjectNode) patch(userB, elementA, "key-2", body(1, "mut-2", "늦은 수정")).body();
		// 요청 식별자는 HTTP 계층이 붙인다
		conflict.put("requestId", "req_test");

		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ElementPatchRequest", body.toString())).isEmpty();
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/MutationResponse", success.body().toString()))
			.isEmpty();
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ErrorResponse", conflict.toString())).isEmpty();
		String payload = jdbc.sql("SELECT CAST(payload AS CHAR) FROM project_events WHERE seq = 1")
			.query(String.class)
			.single();
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/DocumentChange", payload)).isEmpty();
	}

	Result patch(UUID actor, UUID element, String key, JsonNode body) {
		return service.patch(actor, slideId, element, key, body);
	}

	ObjectNode body(long baseVersion, String mutationId, String text) {
		ObjectNode body = json.createObjectNode();
		body.put("generation", 1);
		body.put("baseVersion", baseVersion);
		body.put("editGroupId", editGroup.toString());
		body.put("clientMutationId", mutationId);
		body.putObject("patch").putObject("content").put("text", text);
		return body;
	}

	ErrorCode errorOf(Runnable call) {
		return catchThrowableOfType(ApiException.class, call::run).code();
	}

	// 두 세션이 동시에 저장한다
	<T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
		CyclicBarrier start = new CyclicBarrier(2);
		ExecutorService sessions = Executors.newFixedThreadPool(2);
		try {
			Future<T> a = sessions.submit(() -> {
				start.await();
				return first.call();
			});
			Future<T> b = sessions.submit(() -> {
				start.await();
				return second.call();
			});
			return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
		}
		finally {
			sessions.shutdownNow();
		}
	}

	long elementVersion(UUID element) {
		return version("slide_elements", element);
	}

	long version(String table, UUID id) {
		return jdbc.sql("SELECT version FROM " + table + " WHERE id = UUID_TO_BIN(?)")
			.param(id.toString())
			.query(Long.class)
			.single();
	}

	String text(UUID element) {
		return jdbc.sql("SELECT content ->> '$.text' FROM slide_elements WHERE id = UUID_TO_BIN(?)")
			.param(element.toString())
			.query(String.class)
			.single();
	}

	long eventSeq() {
		return jdbc.sql("SELECT event_seq FROM projects WHERE id = UUID_TO_BIN(?)")
			.param(projectId.toString())
			.query(Long.class)
			.single();
	}

	int count(String table) {
		return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
	}

}
