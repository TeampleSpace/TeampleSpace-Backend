package com.teamplespace.workspace.service;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

// 프로젝트 참여 확인과 프로젝트 잠금
@Component
public class ProjectAccess {

	private final JdbcClient jdbc;

	public ProjectAccess(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void requireActiveMember(UUID projectId, UUID userId) {
		int active = jdbc
			.sql("SELECT COUNT(*) FROM project_memberships m JOIN projects p ON p.id=m.project_id JOIN users u ON u.id=m.user_id WHERE u.status='active' AND p.status='active' AND project_id = UUID_TO_BIN(:project)"
					+ " AND m.user_id = UUID_TO_BIN(:user) AND m.state = 'active'")
			.param("project", projectId.toString())
			.param("user", userId.toString())
			.query(Integer.class)
			.single();
		if (active == 0) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}

	// 참여한 적 없거나 없는 프로젝트는 찾을 수 없음으로, 나갔거나 제거된 팀원은 접근 거절로 알린다
	public void requireMember(UUID projectId, UUID userId) {
		String state = jdbc.sql("""
				SELECT m.state FROM projects p
				JOIN project_memberships m ON m.project_id = p.id AND m.user_id = UUID_TO_BIN(:user)
				JOIN users u ON u.id = m.user_id AND u.status = 'active'
				WHERE p.id = UUID_TO_BIN(:project) AND p.status = 'active' AND p.owner_user_id IS NOT NULL
				""")
			.param("project", projectId.toString())
			.param("user", userId.toString())
			.query(String.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!state.equals("active")) {
			throw new ApiException(ErrorCode.ACCESS_DENIED);
		}
	}

	public boolean isOwner(UUID projectId, UUID userId) {
		return jdbc.sql("SELECT COUNT(*) FROM projects WHERE id = UUID_TO_BIN(:project) AND owner_user_id = UUID_TO_BIN(:user)")
			.param("project", projectId.toString())
			.param("user", userId.toString())
			.query(Integer.class)
			.single() == 1;
	}

	// 같은 프로젝트의 쓰기를 한 줄로 세워 이벤트 순서와 권한 확인을 맞춘다
	public void lock(UUID projectId) {
		jdbc.sql("SELECT 1 FROM projects WHERE id = UUID_TO_BIN(:project) AND status = 'active' FOR UPDATE")
			.param("project", projectId.toString())
			.query(Integer.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
