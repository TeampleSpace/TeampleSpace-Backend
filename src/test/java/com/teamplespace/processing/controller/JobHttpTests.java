package com.teamplespace.processing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.support.HttpIntegrationSupport;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

// 작업 조회, 취소, 목록 경로의 세션과 프로젝트 권한을 실제 HTTP 요청으로 확인
class JobHttpTests extends HttpIntegrationSupport {

	@Autowired
	JobQueue queue;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	ProjectAccess access;

	private UUID register(String projectId, String requesterId) {
		return transaction.execute(status -> {
			access.lock(UUID.fromString(projectId));
			ObjectNode input = json.createObjectNode();
			input.putObject("inputVersions").put("materialVersion", 1);
			return queue.register(UUID.fromString(projectId), UUID.fromString(requesterId), "http_test", "material",
					UUID.randomUUID(), input);
		});
	}

	@Test
	void jobRoutesRequireSessionAndProjectMembership() throws Exception {
		Browser owner = signup("작업 소유자");
		Browser member = signup("작업 팀원");
		Browser stranger = signup("작업 외부인");
		String projectId = createProject(owner, "작업 권한 " + UUID.randomUUID());
		String otherProjectId = createProject(stranger, "다른 프로젝트 " + UUID.randomUUID());
		join(owner, member, projectId);
		UUID jobId = register(projectId, member.userId);
		UUID otherJobId = register(otherProjectId, stranger.userId);

		JsonNode job = member.get("/jobs/" + jobId);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Job", json.writeValueAsString(job))).isEmpty();
		assertThat(job.get("status").asString()).isEqualTo("queued");
		assertThat(owner.get("/jobs/" + jobId).get("id").asString()).isEqualTo(jobId.toString());

		// 다른 프로젝트의 식별자를 넣어도 볼 수 없다
		stranger.request("GET", "/jobs/" + jobId, null, 404);
		stranger.request("POST", "/jobs/" + jobId + "/cancel", null, 404);
		stranger.request("GET", "/projects/" + projectId + "/jobs", null, 404);
		member.request("GET", "/jobs/" + otherJobId, null, 404);
		new Browser().request("GET", "/jobs/" + jobId, null, 401);
		owner.request("GET", "/jobs/not-a-uuid", null, 400);

		JsonNode list = member.get("/projects/" + projectId + "/jobs?status=queued,running&targetType=material");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/JobList", json.writeValueAsString(list))).isEmpty();
		assertThat(list.get("items").size()).isEqualTo(1);
		assertThat(member.get("/projects/" + projectId + "/jobs?status=succeeded").get("items").isEmpty()).isTrue();
		member.request("GET", "/projects/" + projectId + "/jobs?status=unknown", null, 422);

		// 제거된 팀원은 기존 주소로 다시 접근할 수 없다
		removeMember(owner, projectId, member.userId);
		member.request("GET", "/jobs/" + jobId, null, 403);
		member.request("POST", "/jobs/" + jobId + "/cancel", null, 403);
		member.request("GET", "/projects/" + projectId + "/jobs", null, 403);

		JsonNode canceled = owner.request("POST", "/jobs/" + jobId + "/cancel", null, 200);
		assertThat(canceled.get("status").asString()).isEqualTo("canceled");
		assertThat(owner.request("POST", "/jobs/" + jobId + "/cancel", null, 200).get("status").asString())
			.isEqualTo("canceled");
	}

}
