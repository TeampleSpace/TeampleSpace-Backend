package com.teamplespace.platform.idempotency;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.json.CanonicalJson;

// 멱등 기록. 모든 메서드는 호출자의 트랜잭션 안에서 실행해 문서 변경과 함께 커밋되거나 함께 취소된다
@Component
public class IdempotencyStore {

	public static final int RETRY_AFTER_SECONDS = 1;

	public record Key(String actorScope, String routeKey, byte[] keyHash) {

		public static Key of(UUID actorId, String method, String path, String idempotencyKey) {
			return new Key("user:" + actorId, method + " " + path, CanonicalJson.sha256(idempotencyKey));
		}

	}

	public record Stored(int status, String body) {
	}

	private record Row(String state, byte[] requestHash, Integer status, String body, boolean expired) {
	}

	private final JdbcClient jdbc;

	private final Duration retention;

	public IdempotencyStore(JdbcClient jdbc, @Value("${teamplespace.idempotency.retention:24h}") Duration retention) {
		this.jdbc = jdbc;
		this.retention = retention;
	}

	// 기존 결과가 있으면 돌려주고 없으면 이 요청이 처리 중임을 기록한다
	public Optional<Stored> findOrClaim(Key key, byte[] requestHash) {
		Optional<Row> row = lock(key);
		if (row.isPresent() && row.get().expired()) {
			delete(key);
			row = Optional.empty();
		}
		if (row.isEmpty()) {
			try {
				insert(key, requestHash);
				return Optional.empty();
			}
			catch (DuplicateKeyException ex) {
				// 같은 키의 다른 요청이 방금 끝났다
				row = lock(key);
			}
			catch (DataAccessException ex) {
				throw lockUnavailable(ex) ? inProgress() : ex;
			}
		}
		Row existing = row.orElseThrow(IdempotencyStore::inProgress);
		if (!Arrays.equals(existing.requestHash(), requestHash)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
		}
		if (existing.status() == null) {
			throw inProgress();
		}
		return Optional.of(new Stored(existing.status(), existing.body()));
	}

	public void complete(Key key, boolean succeeded, int status, String body, UUID resourceId) {
		jdbc.sql("""
				UPDATE idempotency_records
				SET state = :state, response_status = :status, response_body = CAST(:body AS JSON),
				    resource_id = UUID_TO_BIN(:resourceId)
				WHERE actor_scope = :scope AND route_key = :route AND key_hash = :keyHash
				""")
			.param("state", succeeded ? "succeeded" : "failed")
			.param("status", status)
			.param("body", body)
			.param("resourceId", resourceId.toString())
			.param("scope", key.actorScope())
			.param("route", key.routeKey())
			.param("keyHash", key.keyHash())
			.update();
	}

	// 하위 자원이 없어진 경우 재전송의 접근 권한을 확인할 범위만 찾는다
	public Optional<UUID> findResource(Key key) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(resource_id) FROM idempotency_records
				WHERE actor_scope = :scope AND route_key = :route AND key_hash = :keyHash
				  AND resource_id IS NOT NULL AND expires_at > NOW(6)
				""")
			.param("scope", key.actorScope())
			.param("route", key.routeKey())
			.param("keyHash", key.keyHash())
			.query(String.class)
			.optional().map(UUID::fromString);
	}

	// 다른 트랜잭션이 같은 키를 잡고 있으면 기다리지 않고 처리 중으로 알린다
	private Optional<Row> lock(Key key) {
		try {
			return jdbc.sql("""
					SELECT state, request_hash, response_status, CAST(response_body AS CHAR) AS response_body,
					       expires_at <= NOW(6) AS expired
					FROM idempotency_records
					WHERE actor_scope = :scope AND route_key = :route AND key_hash = :keyHash
					FOR UPDATE NOWAIT
					""")
				.param("scope", key.actorScope())
				.param("route", key.routeKey())
				.param("keyHash", key.keyHash())
				.query((rs, rowNum) -> new Row(rs.getString("state"), rs.getBytes("request_hash"),
						(Integer) rs.getObject("response_status"), rs.getString("response_body"),
						rs.getBoolean("expired")))
				.optional();
		}
		catch (DataAccessException ex) {
			throw lockUnavailable(ex) ? inProgress() : ex;
		}
	}

	private void insert(Key key, byte[] requestHash) {
		jdbc.sql("""
				INSERT INTO idempotency_records (id, actor_scope, route_key, key_hash, request_hash, state, expires_at)
				VALUES (UUID_TO_BIN(:id), :scope, :route, :keyHash, :requestHash, 'processing',
				        DATE_ADD(NOW(6), INTERVAL :retention SECOND))
				""")
			.param("id", UUID.randomUUID().toString())
			.param("scope", key.actorScope())
			.param("route", key.routeKey())
			.param("keyHash", key.keyHash())
			.param("requestHash", requestHash)
			.param("retention", retention.toSeconds())
			.update();
	}

	private void delete(Key key) {
		jdbc.sql("DELETE FROM idempotency_records WHERE actor_scope = :scope AND route_key = :route AND key_hash = :keyHash")
			.param("scope", key.actorScope())
			.param("route", key.routeKey())
			.param("keyHash", key.keyHash())
			.update();
	}

	// MySQL은 NOWAIT 잠금 실패를 3572 오류로 알린다
	private static boolean lockUnavailable(DataAccessException ex) {
		return ex instanceof PessimisticLockingFailureException
				|| (ex.getRootCause() instanceof SQLException sql && sql.getErrorCode() == 3572);
	}

	private static ApiException inProgress() {
		return new ApiException(ErrorCode.REQUEST_IN_PROGRESS, RETRY_AFTER_SECONDS);
	}

}
