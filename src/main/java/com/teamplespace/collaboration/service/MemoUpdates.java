package com.teamplespace.collaboration.service;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.workspace.service.ProjectAccess;

// 메모 공유 편집의 변경 조각 보관. 서버는 조각을 합치지 않고 순번대로 쌓아 돌려준다
// 편집기의 조각은 순서와 중복에 상관없이 같은 결과로 합쳐지므로 받는 쪽이 모두 적용하면 된다
@Component
public class MemoUpdates {

	public record Update(long seq, String data) {
	}

	public record Appended(UUID projectId, long seq) {
	}

	// 한 조각과 압축한 덩어리의 최대 크기
	static final int UPDATE_MAX = 512 * 1024;

	static final int COMPACT_MAX = 8 * 1024 * 1024;

	// 커서 상태 한 번의 최대 크기
	static final int AWARENESS_MAX = 16 * 1024;

	private static final int PAGE = 500;

	private final JdbcClient jdbc;

	private final ProjectAccess access;

	public MemoUpdates(JdbcClient jdbc, ProjectAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	// 순번 다음의 조각을 돌려준다. 다음 쪽이 있으면 마지막 순번으로 이어 읽는다
	@Transactional(readOnly = true)
	public Map<String, Object> after(Actor actor, UUID materialId, long after) {
		memo(actor, materialId, false);
		List<Update> updates = jdbc.sql("""
				SELECT seq, data FROM memo_updates WHERE material_id = UUID_TO_BIN(:material) AND seq > :after
				ORDER BY seq LIMIT :limit
				""")
			.param("material", materialId.toString())
			.param("after", after)
			.param("limit", PAGE + 1)
			.query((rs, row) -> new Update(rs.getLong("seq"), Base64.getEncoder().encodeToString(rs.getBytes("data"))))
			.list();
		boolean more = updates.size() > PAGE;
		return Map.of("updates", more ? updates.subList(0, PAGE) : updates, "hasMore", more);
	}

	// 처음 조각은 아직 아무 조각도 없을 때만 받는다. 여러 명이 동시에 처음 열어도 한 사람의 초기 내용만 남는다
	@Transactional
	public Appended append(Actor actor, UUID materialId, String data, boolean initial) {
		UUID projectId = memo(actor, materialId, true);
		byte[] bytes = decode(data, UPDATE_MAX);
		long latest = latest(materialId);
		if (initial && latest > 0) {
			throw new ApiException(ErrorCode.MATERIAL_STATE_CONFLICT);
		}
		long seq = latest + 1;
		jdbc.sql("""
				INSERT INTO memo_updates (material_id, seq, data, created_by)
				VALUES (UUID_TO_BIN(:material), :seq, :data, UUID_TO_BIN(:actor))
				""")
			.param("material", materialId.toString())
			.param("seq", seq)
			.param("data", bytes)
			.param("actor", actor.userId().toString())
			.update();
		return new Appended(projectId, seq);
	}

	// upTo까지의 조각을 그 내용을 모두 담은 한 덩어리로 바꾼다
	@Transactional
	public void compact(Actor actor, UUID materialId, long upTo, String data) {
		memo(actor, materialId, true);
		byte[] bytes = decode(data, COMPACT_MAX);
		if (upTo < 1 || upTo > latest(materialId)) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "upTo", "INVALID", "압축할 순번을 확인해 주세요.");
		}
		jdbc.sql("DELETE FROM memo_updates WHERE material_id = UUID_TO_BIN(:material) AND seq <= :upTo")
			.param("material", materialId.toString())
			.param("upTo", upTo)
			.update();
		jdbc.sql("""
				INSERT INTO memo_updates (material_id, seq, data, created_by)
				VALUES (UUID_TO_BIN(:material), :seq, :data, UUID_TO_BIN(:actor))
				""")
			.param("material", materialId.toString())
			.param("seq", upTo)
			.param("data", bytes)
			.param("actor", actor.userId().toString())
			.update();
	}

	// 커서 상태는 저장하지 않는다. 보낼 수 있는지만 확인하고 프로젝트를 돌려준다
	@Transactional(readOnly = true)
	public UUID awareness(Actor actor, UUID materialId, String data) {
		UUID projectId = memo(actor, materialId, false);
		decode(data, AWARENESS_MAX);
		return projectId;
	}

	// 지운 자료나 메모가 아닌 자료, 팀원이 아닌 사람에게는 없는 경로처럼 응답한다
	// 쓸 때는 자료 행을 잠가 같은 메모의 순번을 차례로 매긴다
	UUID memo(Actor actor, UUID materialId, boolean lock) {
		UUID projectId = jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM materials WHERE id = UUID_TO_BIN(:material)"
				+ " AND kind = 'memo' AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""))
			.param("material", materialId.toString())
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		access.requireMember(projectId, actor.userId());
		return projectId;
	}

	private long latest(UUID materialId) {
		return jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM memo_updates WHERE material_id = UUID_TO_BIN(:material)")
			.param("material", materialId.toString())
			.query(Long.class)
			.single();
	}

	private static byte[] decode(String data, int max) {
		// 디코딩 전에 문자열 길이로 먼저 거른다
		if (data != null && data.length() > (max / 3 + 1) * 4) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		byte[] bytes;
		try {
			bytes = data == null ? new byte[0] : Base64.getDecoder().decode(data);
		}
		catch (IllegalArgumentException ex) {
			bytes = new byte[0];
		}
		if (bytes.length == 0) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "data", "INVALID", "변경 내용을 확인해 주세요.");
		}
		if (bytes.length > max) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		return bytes;
	}

}
