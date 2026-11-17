package com.teamplespace.workspace.application;

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
			.sql("SELECT COUNT(*) FROM project_memberships WHERE project_id = UUID_TO_BIN(:project)"
					+ " AND user_id = UUID_TO_BIN(:user) AND state = 'active'")
			.param("project", projectId.toString())
			.param("user", userId.toString())
			.query(Integer.class)
			.single();
		if (active == 0) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
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
