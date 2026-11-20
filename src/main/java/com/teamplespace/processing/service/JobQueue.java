package com.teamplespace.processing.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// MySQL을 큐로 쓰는 작업 처리. 상태 전이는 모두 여기서 하고 잠금은 프로젝트, 작업 순서로 잡는다
@Component
public class JobQueue {

	public record Job(UUID id, UUID projectId, UUID requesterId, String type, String targetType, UUID targetId,
			String status, JsonNode input, int attempt, int maxAttempts, boolean cancelRequested) {
	}

	// 실행자가 쥔 임대. 결과를 낼 때 시도 번호와 토큰이 지금 임대와 같아야 반영된다
	public record Claim(Job job, int attempt, byte[] token) {
	}

	public enum Lease {
		HELD, CANCEL_REQUESTED, LOST
	}

	private record Locked(Job job, boolean projectActive, byte[] leaseToken, LocalDateTime leaseExpiresAt) {
	}

	private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

	private static final Set<String> STATUSES = Set.of("queued", "running", "succeeded", "failed", "canceled");

	private static final String COLUMNS = """
			BIN_TO_UUID(j.id) AS id, BIN_TO_UUID(j.project_id) AS project_id, BIN_TO_UUID(j.requester_id) AS requester_id,
			j.type, j.target_type, BIN_TO_UUID(j.target_id) AS target_id, j.status, CAST(j.input_snapshot AS CHAR) AS input,
			CAST(j.result_ref AS CHAR) AS result_ref, CAST(j.error AS CHAR) AS error, j.progress, j.attempt_no, j.max_attempts,
			j.lease_token, j.lease_expires_at, j.cancel_requested_at, j.finished_at, j.created_at, j.updated_at
			""";

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final Clock clock;

	private final ProjectAccess access;

	private final ProjectEvents events;

	private final TransactionTemplate transaction;

	// 처리기가 이 큐에 작업을 등록하는 서비스를 쓰기도 해서 처음 쓸 때 모은다
	private final ObjectProvider<JobHandler> handlerBeans;

	private volatile Map<String, JobHandler> handlers;

	private final Duration lease;

	private final Duration retryDelay;

	private final int maxAttempts;

	public JobQueue(JdbcClient jdbc, ObjectMapper json, Clock clock, ProjectAccess access, ProjectEvents events,
			TransactionTemplate transaction, ObjectProvider<JobHandler> handlers,
			@Value("${teamplespace.jobs.lease:60s}") Duration lease,
			@Value("${teamplespace.jobs.retry-delay:5s}") Duration retryDelay,
			@Value("${teamplespace.jobs.max-attempts:3}") int maxAttempts) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.access = access;
		this.events = events;
		this.transaction = transaction;
		this.handlerBeans = handlers;
		this.lease = lease;
		this.retryDelay = retryDelay;
		this.maxAttempts = maxAttempts;
	}

	// 프로젝트 잠금을 잡은 트랜잭션 안에서 호출한다. 대상 변경과 함께 커밋된다
	public UUID register(UUID projectId, UUID requesterId, String type, String targetType, UUID targetId,
			ObjectNode input) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO jobs (id, project_id, requester_id, type, target_type, target_id, input_snapshot, input_hash,
				                  available_at, max_attempts)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:requester), :type, :targetType,
				        UUID_TO_BIN(:target), CAST(:input AS JSON), :hash, :now, :maxAttempts)
				""")
			.param("id", id.toString())
			.param("project", projectId.toString())
			.param("requester", requesterId.toString())
			.param("type", type)
			.param("targetType", targetType)
			.param("target", targetId.toString())
			.param("input", json.writeValueAsString(input))
			.param("hash", CanonicalJson.sha256(input))
			.param("now", now())
			.param("maxAttempts", maxAttempts)
			.update();
		event(load(id), requesterId);
		return id;
	}

	private Map<String, JobHandler> handlers() {
		if (handlers == null) {
			handlers = handlerBeans.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
		}
		return handlers;
	}

	// 준비된 작업 하나를 선점한다. 다른 실행자가 잡은 행은 건너뛰고 바로 커밋한다
	@Transactional
	public Optional<Claim> claim(String workerId) {
		if (handlers().isEmpty()) {
			return Optional.empty();
		}
		LocalDateTime now = now();
		Optional<String> next = jdbc.sql("""
				SELECT BIN_TO_UUID(id) FROM jobs
				WHERE status = 'queued' AND available_at <= :now AND type IN (:types)
				ORDER BY priority DESC, available_at, id
				LIMIT 1
				FOR UPDATE SKIP LOCKED
				""").param("now", now).param("types", handlers().keySet()).query(String.class).optional();
		if (next.isEmpty()) {
			return Optional.empty();
		}
		UUID id = UUID.fromString(next.get());
		byte[] token = CanonicalJson.sha256(UUID.randomUUID().toString());
		jdbc.sql("""
				UPDATE jobs SET status = 'running', attempt_no = attempt_no + 1, lease_token = :token,
				       lease_expires_at = :expires
				WHERE id = UUID_TO_BIN(:id)
				""").param("token", token).param("expires", now.plus(lease)).param("id", id.toString()).update();
		Job job = load(id);
		jdbc.sql("""
				INSERT INTO job_attempts (job_id, attempt_no, worker_id, lease_token_hash, state, started_at, heartbeat_at)
				VALUES (UUID_TO_BIN(:id), :attempt, :worker, :token, 'running', :now, :now)
				""")
			.param("id", id.toString())
			.param("attempt", job.attempt())
			.param("worker", workerId)
			.param("token", token)
			.param("now", now)
			.update();
		return Optional.of(new Claim(job, job.attempt(), token));
	}

	// 임대를 연장한다. 임대를 잃었으면 이 실행의 결과는 반영되지 않는다
	public Lease heartbeat(Claim claim) {
		LocalDateTime now = now();
		int held = jdbc.sql("""
				UPDATE jobs SET lease_expires_at = :expires
				WHERE id = UUID_TO_BIN(:id) AND status = 'running' AND attempt_no = :attempt AND lease_token = :token
				""")
			.param("expires", now.plus(lease))
			.param("id", claim.job().id().toString())
			.param("attempt", claim.attempt())
			.param("token", claim.token())
			.update();
		if (held == 0) {
			return Lease.LOST;
		}
		jdbc.sql("UPDATE job_attempts SET heartbeat_at = :now WHERE job_id = UUID_TO_BIN(:id) AND attempt_no = :attempt")
			.param("now", now)
			.param("id", claim.job().id().toString())
			.param("attempt", claim.attempt())
			.update();
		return load(claim.job().id()).cancelRequested() ? Lease.CANCEL_REQUESTED : Lease.HELD;
	}

	// 처리기가 응답하지 않아도 취소 요청을 확정한다
	public boolean acknowledgeCancellation(Claim claim) {
		return Boolean.TRUE.equals(transaction.execute(status -> {
			Locked locked = lockHeld(claim);
			if (locked == null || !locked.job().cancelRequested()) {
				return false;
			}
			close(locked, "canceled", null, null, null);
			return true;
		}));
	}

	// 선점한 작업 하나를 끝까지 처리한다
	public void run(Claim claim) {
		try {
			Object output = handlers().get(claim.job().type()).execute(claim.job());
			succeed(claim, output);
		}
		catch (JobFailure failure) {
			fail(claim, failure);
		}
		catch (Exception ex) {
			log.warn("작업 처리 중 오류 jobId={} attempt={}", claim.job().id(), claim.attempt(), ex);
			fail(claim, JobFailure.attemptsExhausted());
		}
	}

	// 결과를 반영한다. 임대를 잃었으면 아무것도 바꾸지 않고 false를 돌려준다
	public boolean succeed(Claim claim, Object output) {
		return Boolean.TRUE.equals(transaction.execute(status -> {
			Locked locked = lockHeld(claim);
			if (locked == null) {
				return false;
			}
			Job job = locked.job();
			if (!locked.projectActive()) {
				close(locked, "failed", JobFailure.targetGone(), null, null);
			}
			else if (job.cancelRequested()) {
				close(locked, "canceled", null, null, null);
			}
			else {
				ObjectNode result;
				try {
					// 처리기는 저장을 시작하기 전에만 JobFailure를 던진다
					result = handlers().get(job.type()).publish(job, output);
				}
				catch (JobFailure failure) {
					close(locked, "failed", failure, null, null);
					return true;
				}
				close(locked, "succeeded", null, result, null);
			}
			return true;
		}));
	}

	// 실패를 기록한다. 다시 시도할 수 있고 시도 횟수가 남았으면 같은 작업을 대기로 돌린다
	public boolean fail(Claim claim, JobFailure failure) {
		return Boolean.TRUE.equals(transaction.execute(status -> {
			Locked locked = lockHeld(claim);
			if (locked == null) {
				return false;
			}
			Job job = locked.job();
			if (!locked.projectActive()) {
				close(locked, "failed", JobFailure.targetGone(), null, null);
			}
			else if (job.cancelRequested()) {
				close(locked, "canceled", null, null, null);
			}
			else if (failure.retryable() && job.attempt() < job.maxAttempts()) {
				requeue(job, "failed", failure.code(), now().plus(retryDelay.multipliedBy(job.attempt())));
			}
			else {
				close(locked, "failed", failure, null, null);
			}
			return true;
		}));
	}

	// 실행자가 멈춰 임대가 만료된 작업을 대기로 돌리거나 시도 횟수를 다 썼으면 끝낸다
	public int reapExpired() {
		List<String> expired = jdbc.sql("""
				SELECT BIN_TO_UUID(id) FROM jobs
				WHERE status = 'running' AND lease_expires_at < :now
				ORDER BY lease_expires_at
				LIMIT 20
				""").param("now", now()).query(String.class).list();
		int reaped = 0;
		for (String id : expired) {
			reaped += transaction.execute(status -> {
				Locked locked = lock(UUID.fromString(id));
				LocalDateTime now = now();
				if (locked == null || !locked.job().status().equals("running") || !locked.leaseExpiresAt().isBefore(now)) {
					return 0;
				}
				Job job = locked.job();
				if (!locked.projectActive()) {
					close(locked, "failed", JobFailure.targetGone(), null, null);
				}
				else if (job.cancelRequested()) {
					close(locked, "canceled", null, null, null);
				}
				else if (job.attempt() < job.maxAttempts()) {
					requeue(job, "expired", null, now);
				}
				else {
					close(locked, "failed", JobFailure.attemptsExhausted(), null, null);
				}
				return 1;
			});
		}
		return reaped;
	}

	// 대기 중이면 바로 취소하고 실행 중이면 취소 요청만 남긴다. 끝난 작업은 그대로 돌려준다
	@Transactional
	public Map<String, Object> cancel(UUID actor, UUID jobId) {
		UUID projectId = projectOf(jobId);
		access.lock(projectId);
		access.requireMember(projectId, actor);
		Locked locked = lock(jobId);
		Job job = locked.job();
		if (!actor.equals(job.requesterId()) && !access.isOwner(projectId, actor)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		requestCancel(locked, actor);
		return view(jobId);
	}

	// 대상의 대기 중이거나 실행 중인 작업을 취소한다. 대상을 지우는 트랜잭션이 프로젝트 잠금을 잡고 호출한다
	public void cancelActive(String targetType, UUID targetId, UUID actor) {
		List<String> active = jdbc.sql("""
				SELECT BIN_TO_UUID(id) FROM jobs
				WHERE target_type = :targetType AND target_id = UUID_TO_BIN(:target) AND status IN ('queued', 'running')
				""").param("targetType", targetType).param("target", targetId.toString()).query(String.class).list();
		for (String id : active) {
			requestCancel(lock(UUID.fromString(id)), actor);
		}
	}

	private void requestCancel(Locked locked, UUID actor) {
		Job job = locked.job();
		if (job.status().equals("queued")) {
			close(locked, "canceled", null, null, actor);
		}
		else if (job.status().equals("running") && !job.cancelRequested()) {
			jdbc.sql("UPDATE jobs SET cancel_requested_at = :now WHERE id = UUID_TO_BIN(:id)")
				.param("now", now())
				.param("id", job.id().toString())
				.update();
			event(load(job.id()), actor);
		}
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UUID actor, UUID jobId) {
		access.requireMember(projectOf(jobId), actor);
		return view(jobId);
	}

	// 대상의 가장 최근 작업. 없으면 null
	public Map<String, Object> latest(String targetType, UUID targetId) {
		return jdbc.sql("SELECT " + COLUMNS + """
				FROM jobs j
				WHERE j.target_type = :targetType AND j.target_id = UUID_TO_BIN(:target)
				ORDER BY j.created_at DESC, j.id DESC
				LIMIT 1
				""")
			.param("targetType", targetType)
			.param("target", targetId.toString())
			.query((rs, row) -> view(rs))
			.optional()
			.orElse(null);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(UUID actor, UUID projectId, String type, String statuses, String targetType,
			UUID targetId, String cursor, Integer limit) {
		access.requireMember(projectId, actor);
		int size = limit == null ? 30 : limit;
		if (size < 1 || size > 100) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		List<String> statusList = statuses == null || statuses.isBlank() ? List.of() : List.of(statuses.split(","));
		if (!STATUSES.containsAll(statusList)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM jobs j WHERE j.project_id = UUID_TO_BIN(:project)");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("project", projectId.toString());
		if (type != null && !type.isBlank()) {
			sql.append(" AND j.type = :type");
			params.put("type", type);
		}
		if (!statusList.isEmpty()) {
			sql.append(" AND j.status IN (:statuses)");
			params.put("statuses", statusList);
		}
		if (targetType != null && !targetType.isBlank()) {
			sql.append(" AND j.target_type = :targetType");
			params.put("targetType", targetType);
		}
		if (targetId != null) {
			sql.append(" AND j.target_id = UUID_TO_BIN(:target)");
			params.put("target", targetId.toString());
		}
		if (cursor != null && !cursor.isEmpty()) {
			// 커서는 앞 페이지 마지막 작업의 식별자다
			LocalDateTime after = jdbc
				.sql("SELECT created_at FROM jobs WHERE id = UUID_TO_BIN(:id) AND project_id = UUID_TO_BIN(:project)")
				.param("id", uuid(cursor).toString())
				.param("project", projectId.toString())
				.query(LocalDateTime.class)
				.optional()
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_CURSOR));
			sql.append(" AND (j.created_at < :after OR (j.created_at = :after AND j.id < UUID_TO_BIN(:cursor)))");
			params.put("after", after);
			params.put("cursor", uuid(cursor).toString());
		}
		sql.append(" ORDER BY j.created_at DESC, j.id DESC LIMIT :limit");
		params.put("limit", size + 1);
		List<Map<String, Object>> rows = jdbc.sql(sql.toString()).params(params).query((rs, row) -> view(rs)).list();
		Map<String, Object> page = new LinkedHashMap<>();
		page.put("items", new ArrayList<>(rows.subList(0, Math.min(size, rows.size()))));
		page.put("nextCursor", rows.size() > size ? rows.get(size - 1).get("id") : null);
		return page;
	}

	// 사용자 요청으로 볼 수 있는 작업의 프로젝트. 내부 작업과 없는 작업은 찾을 수 없다고 알린다
	private UUID projectOf(UUID jobId) {
		return jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM jobs WHERE id = UUID_TO_BIN(:id)")
			.param("id", jobId.toString())
			.query(String.class)
			.optional()
			.map(UUID::fromString)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private Locked lockHeld(Claim claim) {
		Locked locked = lock(claim.job().id());
		if (locked == null || !locked.job().status().equals("running") || locked.job().attempt() != claim.attempt()
				|| !Arrays.equals(locked.leaseToken(), claim.token())) {
			return null;
		}
		return locked;
	}

	// 프로젝트를 먼저 잠근 뒤 작업을 잠근다. 삭제된 프로젝트도 잠그고 활성 여부를 함께 돌려준다
	private Locked lock(UUID jobId) {
		Optional<String> project = jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM jobs WHERE id = UUID_TO_BIN(:id)")
			.param("id", jobId.toString())
			.query(String.class)
			.optional();
		boolean projectActive = project
			.map(id -> jdbc.sql("SELECT status FROM projects WHERE id = UUID_TO_BIN(:id) FOR UPDATE")
				.param("id", id)
				.query(String.class)
				.single()
				.equals("active"))
			.orElse(false);
		return jdbc.sql("SELECT " + COLUMNS + " FROM jobs j WHERE j.id = UUID_TO_BIN(:id) FOR UPDATE")
			.param("id", jobId.toString())
			.query((rs, row) -> new Locked(job(rs), projectActive, rs.getBytes("lease_token"),
					rs.getObject("lease_expires_at", LocalDateTime.class)))
			.optional()
			.orElse(null);
	}

	private void close(Locked locked, String status, JobFailure failure, ObjectNode result, UUID actor) {
		Job job = locked.job();
		LocalDateTime now = now();
		String error = null;
		if (failure != null) {
			ObjectNode node = json.createObjectNode();
			node.put("code", failure.code());
			node.put("message", failure.userMessage());
			node.put("retryable", failure.retryable());
			error = json.writeValueAsString(node);
		}
		jdbc.sql("""
				UPDATE jobs SET status = :status, result_ref = CAST(:result AS JSON), error = CAST(:error AS JSON),
				       progress = IF(:status = 'succeeded', 100, progress), lease_token = NULL, lease_expires_at = NULL,
				       finished_at = :now
				WHERE id = UUID_TO_BIN(:id)
				""")
			.param("status", status)
			.param("result", result == null ? null : json.writeValueAsString(result))
			.param("error", error)
			.param("now", now)
			.param("id", job.id().toString())
			.update();
		closeAttempt(job, status, failure == null ? null : failure.code(), now);
		if (!locked.projectActive()) {
			return;
		}
		JobHandler handler = handlers().get(job.type());
		if (handler != null && !status.equals("succeeded")) {
			handler.settle(job, status);
		}
		event(load(job.id()), actor);
	}

	private void requeue(Job job, String attemptState, String errorCode, LocalDateTime availableAt) {
		jdbc.sql("""
				UPDATE jobs SET status = 'queued', available_at = :available, lease_token = NULL, lease_expires_at = NULL
				WHERE id = UUID_TO_BIN(:id)
				""").param("available", availableAt).param("id", job.id().toString()).update();
		closeAttempt(job, attemptState, errorCode, now());
	}

	private void closeAttempt(Job job, String state, String errorCode, LocalDateTime now) {
		jdbc.sql("""
				UPDATE job_attempts SET state = :state, finished_at = :now, error_code = :code
				WHERE job_id = UUID_TO_BIN(:id) AND attempt_no = :attempt AND state = 'running'
				""")
			.param("state", state)
			.param("now", now)
			.param("code", errorCode)
			.param("id", job.id().toString())
			.param("attempt", job.attempt())
			.update();
	}

	private void event(Job job, UUID actor) {
		ObjectNode payload = json.createObjectNode();
		payload.put("jobId", job.id().toString());
		payload.put("type", job.type());
		payload.put("status", job.status());
		payload.put("targetType", job.targetType());
		if (job.targetId() != null) {
			payload.put("targetId", job.targetId().toString());
		}
		if (job.cancelRequested()) {
			payload.put("cancelRequested", true);
		}
		events.append(job.projectId(), "job.updated", actor, null, job.id(), payload);
	}

	private Job load(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM jobs j WHERE j.id = UUID_TO_BIN(:id)")
			.param("id", id.toString())
			.query((rs, row) -> job(rs))
			.single();
	}

	private Map<String, Object> view(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM jobs j WHERE j.id = UUID_TO_BIN(:id)")
			.param("id", id.toString())
			.query((rs, row) -> view(rs))
			.single();
	}

	private Job job(ResultSet rs) throws SQLException {
		return new Job(UUID.fromString(rs.getString("id")), uuid(rs.getString("project_id")),
				uuid(rs.getString("requester_id")), rs.getString("type"), rs.getString("target_type"),
				uuid(rs.getString("target_id")), rs.getString("status"), json.readTree(rs.getString("input")),
				rs.getInt("attempt_no"), rs.getInt("max_attempts"), rs.getObject("cancel_requested_at") != null);
	}

	// contracts/schemas/api.schema.json의 Job
	private Map<String, Object> view(ResultSet rs) throws SQLException {
		Map<String, Object> job = new LinkedHashMap<>();
		job.put("id", rs.getString("id"));
		job.put("projectId", rs.getString("project_id"));
		job.put("type", rs.getString("type"));
		job.put("status", rs.getString("status"));
		job.put("progress", rs.getInt("progress"));
		job.put("targetType", rs.getString("target_type"));
		if (rs.getString("target_id") != null) {
			job.put("targetId", rs.getString("target_id"));
		}
		job.put("attempt", rs.getInt("attempt_no"));
		JsonNode inputVersions = json.readTree(rs.getString("input")).get("inputVersions");
		if (inputVersions != null) {
			job.put("inputVersions", inputVersions);
		}
		job.put("resultRef", rs.getString("result_ref") == null ? null : json.readTree(rs.getString("result_ref")));
		job.put("error", rs.getString("error") == null ? null : json.readTree(rs.getString("error")));
		job.put("cancelRequestedAt", instant(rs, "cancel_requested_at"));
		job.put("createdAt", instant(rs, "created_at"));
		job.put("updatedAt", instant(rs, "updated_at"));
		job.put("finishedAt", instant(rs, "finished_at"));
		return job;
	}

	private static Object instant(ResultSet rs, String column) throws SQLException {
		LocalDateTime value = rs.getObject(column, LocalDateTime.class);
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}

	private static UUID uuid(String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(ErrorCode.INVALID_CURSOR);
		}
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

}
