package com.teamplespace.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.processing.JobQueue.Claim;
import com.teamplespace.processing.JobQueue.Job;
import com.teamplespace.processing.JobQueue.Lease;
import com.teamplespace.support.DocumentFixture;
import com.teamplespace.support.MutableClock;
import com.teamplespace.support.TestClockConfig;
import com.teamplespace.workspace.application.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 작업 선점, 임대 만료, 재시도, 취소 경합과 접근 권한을 실제 MySQL에서 확인
@SpringBootTest
@Import({ TestClockConfig.class, JobQueueTests.Handlers.class })
class JobQueueTests {

	static final String TYPE = "queue_test";

	// 입력의 mode에 따라 성공, 다시 시도할 수 있는 실패, 다시 시도할 수 없는 실패, 예상하지 못한 오류를 낸다
	static class RecordingHandler implements JobHandler {

		final List<String> published = new CopyOnWriteArrayList<>();

		final List<String> settled = new CopyOnWriteArrayList<>();

		CountDownLatch started = new CountDownLatch(1);

		CountDownLatch release = new CountDownLatch(1);

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public Object execute(Job job) {
			switch (job.input().path("mode").asString("ok")) {
				case "retryable" -> throw new JobFailure("ANALYSIS_FAILED", "자료를 분석하지 못했어요.", true);
				case "fatal" -> throw new JobFailure("MATERIAL_UNREADABLE", "파일을 읽을 수 없어요.", false);
				case "crash" -> throw new IllegalStateException("예상하지 못한 오류");
				case "blocked" -> {
					started.countDown();
					boolean waiting = true;
					while (waiting) {
						try {
							release.await();
							waiting = false;
						}
						catch (InterruptedException ignored) {
							// 중단 신호에 응답하지 않는 처리기도 취소 상태가 되어야 한다
						}
					}
				}
				default -> {
				}
			}
			return "결과 " + job.attempt();
		}

		@Override
		public ObjectNode publish(Job job, Object output) {
			published.add(job.id() + ":" + output);
			return new ObjectMapper().createObjectNode().put("output", output.toString());
		}

		@Override
		public void settle(Job job, String status) {
			settled.add(job.id() + ":" + status);
		}

	}

	@TestConfiguration
	static class Handlers {

		@Bean
		RecordingHandler recordingHandler() {
			return new RecordingHandler();
		}

	}

	@Autowired
	JobQueue queue;

	@Autowired
	RecordingHandler handler;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@Autowired
	MutableClock clock;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	ProjectAccess access;

	DocumentFixture fixture;

	@BeforeEach
	void setUp() {
		fixture = new DocumentFixture(jdbc);
		fixture.reset();
		jdbc.sql("UPDATE projects SET owner_user_id = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)")
			.params(fixture.userA.userId().toString(), fixture.projectId.toString())
			.update();
		handler.published.clear();
		handler.settled.clear();
		handler.started = new CountDownLatch(1);
		handler.release = new CountDownLatch(1);
	}

	private UUID register(String mode) {
		return register(mode, fixture.userB.userId());
	}

	private UUID register(String mode, UUID requester) {
		return transaction.execute(status -> {
			access.lock(fixture.projectId);
			ObjectNode input = json.createObjectNode().put("mode", mode);
			input.putObject("inputVersions").put("materialVersion", 1);
			return queue.register(fixture.projectId, requester, TYPE, "material", UUID.randomUUID(), input);
		});
	}

	private JsonNode view(UUID id) {
		return json.valueToTree(queue.get(fixture.userA.userId(), id));
	}

	private String status(UUID id) {
		return view(id).get("status").asString();
	}

	private String attemptState(UUID id, int attempt) {
		return jdbc.sql("SELECT state FROM job_attempts WHERE job_id = UUID_TO_BIN(?) AND attempt_no = ?")
			.params(id.toString(), attempt)
			.query(String.class)
			.single();
	}

	@Test
	void workerConfirmsCancellationBeforeTheHandlerReturns() throws Exception {
		UUID id = register("blocked");
		JobWorker worker = new JobWorker(queue, 1);
		try {
			worker.poll();
			assertThat(handler.started.await(5, TimeUnit.SECONDS)).isTrue();
			queue.cancel(fixture.userA.userId(), id);
			worker.heartbeat();
			assertThat(status(id)).isEqualTo("canceled");
			assertThat(attemptState(id, 1)).isEqualTo("canceled");
			worker.heartbeat();
			UUID next = register("ok");
			handler.release.countDown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!status(next).equals("succeeded") && System.nanoTime() < deadline) {
				worker.poll();
				Thread.sleep(10);
			}
			assertThat(status(next)).isEqualTo("succeeded");
			assertThat(status(id)).isEqualTo("canceled");
			assertThat(handler.published).noneMatch(value -> value.startsWith(id.toString()));
			assertThat(handler.settled.stream().filter(value -> value.startsWith(id.toString())))
				.containsExactly(id + ":canceled");
		}
		finally {
			handler.release.countDown();
			worker.stop();
		}
	}

	@Test
	void cancellationAcknowledgementRequiresACurrentCanceledClaim() {
		UUID id = register("ok");
		Claim claim = queue.claim("worker").orElseThrow();
		assertThat(queue.acknowledgeCancellation(claim)).isFalse();
		assertThat(status(id)).isEqualTo("running");
		queue.cancel(fixture.userA.userId(), id);
		assertThat(queue.acknowledgeCancellation(claim)).isTrue();
		assertThat(queue.acknowledgeCancellation(claim)).isFalse();
		assertThat(handler.settled).containsExactly(id + ":canceled");
	}

	@Test
	void registeredJobMatchesTheContract() {
		UUID id = register("ok");
		JsonNode job = view(id);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Job", json.writeValueAsString(job))).isEmpty();
		assertThat(job.get("status").asString()).isEqualTo("queued");
		assertThat(job.get("attempt").asInt()).isZero();
		assertThat(job.get("inputVersions").get("materialVersion").asInt()).isEqualTo(1);
		assertThat(fixture.events("job.updated")).isEqualTo(1);
	}

	@Test
	void concurrentWorkersNeverClaimTheSameJob() throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ids.add(register("ok"));
		}
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Optional<Claim>>> attempts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			String worker = "worker-" + i;
			attempts.add(pool.submit(() -> {
				start.await();
				return queue.claim(worker);
			}));
		}
		start.countDown();
		List<UUID> claimed = new ArrayList<>();
		for (Future<Optional<Claim>> attempt : attempts) {
			attempt.get(10, TimeUnit.SECONDS).ifPresent(claim -> claimed.add(claim.job().id()));
		}
		pool.shutdown();
		assertThat(claimed).doesNotHaveDuplicates();
		assertThat(claimed).isSubsetOf(ids);
		// 잠긴 행을 건너뛰느라 못 잡은 작업도 다음 선점에서 한 번씩만 잡힌다
		Optional<Claim> more;
		while ((more = queue.claim("worker-late")).isPresent()) {
			claimed.add(more.get().job().id());
		}
		assertThat(claimed).containsExactlyInAnyOrderElementsOf(ids);
		assertThat(fixture.count("job_attempts")).isEqualTo(3);
	}

	@Test
	void successPublishesOnceAndStoresTheResult() {
		UUID id = register("ok");
		queue.run(queue.claim("w").orElseThrow());
		JsonNode job = view(id);
		assertThat(job.get("status").asString()).isEqualTo("succeeded");
		assertThat(job.get("progress").asInt()).isEqualTo(100);
		assertThat(job.get("resultRef").get("output").asString()).isEqualTo("결과 1");
		assertThat(job.get("finishedAt").isNull()).isFalse();
		assertThat(handler.published).hasSize(1);
		assertThat(handler.settled).isEmpty();
		assertThat(attemptState(id, 1)).isEqualTo("succeeded");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Job", json.writeValueAsString(job))).isEmpty();
	}

	@Test
	void expiredLeaseIsRequeuedAndTheStaleWorkerCannotPublish() {
		UUID id = register("ok");
		Claim stale = queue.claim("stale").orElseThrow();
		assertThat(queue.reapExpired()).isZero();
		clock.advance(Duration.ofSeconds(61));
		assertThat(queue.heartbeat(stale)).isEqualTo(Lease.HELD);
		clock.advance(Duration.ofSeconds(61));
		assertThat(queue.reapExpired()).isEqualTo(1);
		assertThat(status(id)).isEqualTo("queued");
		assertThat(attemptState(id, 1)).isEqualTo("expired");

		Claim fresh = queue.claim("fresh").orElseThrow();
		assertThat(fresh.attempt()).isEqualTo(2);
		assertThat(queue.heartbeat(stale)).isEqualTo(Lease.LOST);
		assertThat(queue.succeed(stale, "늦은 결과")).isFalse();
		assertThat(queue.fail(stale, JobFailure.attemptsExhausted())).isFalse();
		assertThat(handler.published).isEmpty();
		assertThat(status(id)).isEqualTo("running");

		assertThat(queue.succeed(fresh, "새 결과")).isTrue();
		assertThat(view(id).get("resultRef").get("output").asString()).isEqualTo("새 결과");
		assertThat(handler.published).containsExactly(id + ":새 결과");
	}

	@Test
	void jobFailsAfterTheLeaseExpiresOnEveryAttempt() {
		UUID id = register("ok");
		for (int attempt = 1; attempt <= 3; attempt++) {
			assertThat(queue.claim("w").orElseThrow().attempt()).isEqualTo(attempt);
			clock.advance(Duration.ofSeconds(61));
			queue.reapExpired();
		}
		JsonNode job = view(id);
		assertThat(job.get("status").asString()).isEqualTo("failed");
		assertThat(job.get("error").get("code").asString()).isEqualTo(JobFailure.ATTEMPTS_EXHAUSTED);
		assertThat(job.get("attempt").asInt()).isEqualTo(3);
		assertThat(queue.claim("w")).isEmpty();
		assertThat(handler.settled).containsExactly(id + ":failed");
	}

	@Test
	void retryableFailureWaitsThenRetriesAndKeepsItsCodeWhenExhausted() {
		UUID id = register("retryable");
		queue.run(queue.claim("w").orElseThrow());
		assertThat(status(id)).isEqualTo("queued");
		assertThat(attemptState(id, 1)).isEqualTo("failed");
		// 다시 시도하기 전에 기다린다
		assertThat(queue.claim("w")).isEmpty();
		clock.advance(Duration.ofSeconds(6));
		queue.run(queue.claim("w").orElseThrow());
		clock.advance(Duration.ofSeconds(11));
		queue.run(queue.claim("w").orElseThrow());
		JsonNode job = view(id);
		assertThat(job.get("status").asString()).isEqualTo("failed");
		assertThat(job.get("error").get("code").asString()).isEqualTo("ANALYSIS_FAILED");
		assertThat(job.get("error").get("retryable").asBoolean()).isTrue();
		assertThat(handler.settled).containsExactly(id + ":failed");
		assertThat(handler.published).isEmpty();
	}

	@Test
	void fatalFailureEndsWithoutRetryAndUnexpectedErrorsAreRetried() {
		UUID fatal = register("fatal");
		queue.run(queue.claim("w").orElseThrow());
		JsonNode failed = view(fatal);
		assertThat(failed.get("status").asString()).isEqualTo("failed");
		assertThat(failed.get("attempt").asInt()).isEqualTo(1);
		assertThat(failed.get("error").get("retryable").asBoolean()).isFalse();

		UUID crash = register("crash");
		queue.run(queue.claim("w").orElseThrow());
		assertThat(status(crash)).isEqualTo("queued");
	}

	@Test
	void cancelingAQueuedJobEndsItImmediately() {
		UUID id = register("ok");
		JsonNode canceled = json.valueToTree(queue.cancel(fixture.userB.userId(), id));
		assertThat(canceled.get("status").asString()).isEqualTo("canceled");
		assertThat(queue.claim("w")).isEmpty();
		assertThat(handler.settled).containsExactly(id + ":canceled");
		// 끝난 작업을 다시 취소해도 그대로다
		assertThat(json.valueToTree(queue.cancel(fixture.userB.userId(), id)).get("status").asString())
			.isEqualTo("canceled");
		assertThat(handler.settled).hasSize(1);
	}

	@Test
	void cancelingARunningJobDiscardsTheResult() {
		UUID id = register("ok");
		Claim claim = queue.claim("w").orElseThrow();
		JsonNode requested = json.valueToTree(queue.cancel(fixture.userA.userId(), id));
		assertThat(requested.get("status").asString()).isEqualTo("running");
		assertThat(requested.get("cancelRequestedAt").isNull()).isFalse();
		assertThat(queue.heartbeat(claim)).isEqualTo(Lease.CANCEL_REQUESTED);

		assertThat(queue.succeed(claim, "취소된 결과")).isTrue();
		assertThat(status(id)).isEqualTo("canceled");
		assertThat(handler.published).isEmpty();
		assertThat(handler.settled).containsExactly(id + ":canceled");
		assertThat(attemptState(id, 1)).isEqualTo("canceled");
	}

	@Test
	void finishedJobIsNotChangedByALaterCancelOrResult() {
		UUID id = register("ok");
		Claim claim = queue.claim("w").orElseThrow();
		assertThat(queue.succeed(claim, "먼저 끝남")).isTrue();
		queue.cancel(fixture.userB.userId(), id);
		assertThat(queue.succeed(claim, "두 번째")).isFalse();
		assertThat(queue.fail(claim, JobFailure.attemptsExhausted())).isFalse();
		JsonNode job = view(id);
		assertThat(job.get("status").asString()).isEqualTo("succeeded");
		assertThat(job.get("cancelRequestedAt").isNull()).isTrue();
		assertThat(handler.published).hasSize(1);
	}

	@Test
	void cancelAndCompletionRaceLeavesOneTerminalState() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID id = register("ok");
			Claim claim = queue.claim("w").orElseThrow();
			ExecutorService pool = Executors.newFixedThreadPool(2);
			CountDownLatch start = new CountDownLatch(1);
			Future<?> cancel = pool.submit(() -> {
				start.await();
				return queue.cancel(fixture.userB.userId(), id);
			});
			Future<?> finish = pool.submit(() -> {
				start.await();
				return queue.succeed(claim, "경합");
			});
			start.countDown();
			cancel.get(10, TimeUnit.SECONDS);
			finish.get(10, TimeUnit.SECONDS);
			pool.shutdown();
			String status = status(id);
			boolean published = handler.published.contains(id + ":경합");
			// 취소가 먼저면 결과를 버리고 완료가 먼저면 취소가 아무것도 바꾸지 않는다
			assertThat(status).isIn("succeeded", "canceled");
			assertThat(published).isEqualTo(status.equals("succeeded"));
			assertThat(handler.settled.contains(id + ":canceled")).isEqualTo(status.equals("canceled"));
		}
	}

	@Test
	void onlyMembersSeeJobsAndOnlyRequesterOrOwnerCancels() {
		UUID stranger = UUID.randomUUID();
		UUID teammate = UUID.randomUUID();
		for (UUID user : List.of(stranger, teammate)) {
			jdbc.sql("INSERT INTO users(id,onboarding_status) VALUES(UUID_TO_BIN(?),'completed')")
				.param(user.toString())
				.update();
		}
		jdbc.sql("INSERT INTO project_memberships (project_id, user_id, state) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'active')")
			.params(fixture.projectId.toString(), teammate.toString())
			.update();
		UUID id = register("ok");

		assertThat(code(() -> queue.get(stranger, id))).isEqualTo(ErrorCode.NOT_FOUND);
		assertThat(code(() -> queue.cancel(stranger, id))).isEqualTo(ErrorCode.NOT_FOUND);
		assertThat(code(() -> queue.list(stranger, fixture.projectId, null, null, null, null, null, null)))
			.isEqualTo(ErrorCode.NOT_FOUND);
		assertThat(code(() -> queue.get(fixture.userA.userId(), UUID.randomUUID()))).isEqualTo(ErrorCode.NOT_FOUND);

		// 요청자도 소유자도 아닌 팀원은 볼 수만 있다
		assertThat(queue.get(teammate, id).get("status")).isEqualTo("queued");
		assertThat(code(() -> queue.cancel(teammate, id))).isEqualTo(ErrorCode.FORBIDDEN);

		jdbc.sql("UPDATE project_memberships SET state = 'removed' WHERE user_id = UUID_TO_BIN(?)")
			.param(teammate.toString())
			.update();
		assertThat(code(() -> queue.get(teammate, id))).isEqualTo(ErrorCode.ACCESS_DENIED);
		assertThat(code(() -> queue.cancel(teammate, id))).isEqualTo(ErrorCode.ACCESS_DENIED);

		// 소유자는 다른 팀원이 요청한 작업도 취소할 수 있다
		assertThat(queue.cancel(fixture.userA.userId(), id).get("status")).isEqualTo("canceled");
	}

	@Test
	void listFiltersByStatusAndTargetAndPagesNewestFirst() {
		UUID first = register("ok");
		UUID second = register("ok");
		UUID third = register("ok");
		// 만든 순서가 조회 순서를 정하도록 시각을 벌린다
		int offset = 3;
		for (UUID id : List.of(first, second, third)) {
			jdbc.sql("UPDATE jobs SET created_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = UUID_TO_BIN(?)")
				.params(offset--, id.toString())
				.update();
		}
		queue.cancel(fixture.userB.userId(), first);
		UUID actor = fixture.userA.userId();

		Map<String, Object> page = queue.list(actor, fixture.projectId, TYPE, null, null, null, null, 2);
		JsonNode items = json.valueToTree(page).get("items");
		assertThat(items.get(0).get("id").asString()).isEqualTo(third.toString());
		assertThat(items.get(1).get("id").asString()).isEqualTo(second.toString());
		assertThat(page.get("nextCursor")).isEqualTo(second.toString());
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/JobList", json.writeValueAsString(page))).isEmpty();

		Map<String, Object> rest = queue.list(actor, fixture.projectId, null, null, null, null, second.toString(), 2);
		assertThat(json.valueToTree(rest).get("items").get(0).get("id").asString()).isEqualTo(first.toString());
		assertThat(rest.get("nextCursor")).isNull();

		JsonNode active = json.valueToTree(queue.list(actor, fixture.projectId, null, "queued,running", null, null, null, null));
		assertThat(active.get("items").size()).isEqualTo(2);
		String target = view(second).get("targetId").asString();
		JsonNode byTarget = json.valueToTree(
				queue.list(actor, fixture.projectId, null, null, "material", UUID.fromString(target), null, null));
		assertThat(byTarget.get("items").size()).isEqualTo(1);

		assertThat(code(() -> queue.list(actor, fixture.projectId, null, "done", null, null, null, null)))
			.isEqualTo(ErrorCode.VALIDATION_FAILED);
		assertThat(code(() -> queue.list(actor, fixture.projectId, null, null, null, null, "not-a-cursor", null)))
			.isEqualTo(ErrorCode.INVALID_CURSOR);
		assertThat(code(() -> queue.list(actor, fixture.projectId, null, null, null, null, null, 101)))
			.isEqualTo(ErrorCode.VALIDATION_FAILED);
	}

	@Test
	void deletedProjectJobEndsWithoutPublishing() {
		UUID id = register("ok");
		Claim claim = queue.claim("w").orElseThrow();
		jdbc.sql("UPDATE projects SET status = 'deleted' WHERE id = UUID_TO_BIN(?)")
			.param(fixture.projectId.toString())
			.update();
		assertThat(queue.succeed(claim, "삭제 뒤 결과")).isTrue();
		assertThat(handler.published).isEmpty();
		assertThat(jdbc.sql("SELECT CONCAT(status, ':', error ->> '$.code') FROM jobs WHERE id = UUID_TO_BIN(?)")
			.param(id.toString())
			.query(String.class)
			.single()).isEqualTo("failed:" + JobFailure.TARGET_GONE);
	}

	private static ErrorCode code(Runnable call) {
		return catchThrowableOfType(ApiException.class, call::run).code();
	}

}
