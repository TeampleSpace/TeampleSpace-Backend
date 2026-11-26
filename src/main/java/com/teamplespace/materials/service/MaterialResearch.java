package com.teamplespace.materials.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.identity.service.AuthService;
import com.teamplespace.materials.infra.OpenAiResearchClient;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.processing.service.JobFailure;
import com.teamplespace.processing.service.JobHandler;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 주제를 웹에서 조사해 정리한 글과 출처를 작업 결과로 돌려준다. 자료로 저장은 팀원이 결과를 보고 메모로 한다
@Service
public class MaterialResearch implements JobHandler {

	static final String TYPE = "material_research";
	private static final String PROMPT_VERSION = "material-research-v1";
	static final int TOPIC_MAX = 200;
	// 사용자 한 명이 한 시간에 요청할 수 있는 조사 수
	private static final int HOURLY_LIMIT = 20;

	record Input(String topic, String model, String promptVersion) {
	}

	private final ObjectMapper json;
	private final ProjectAccess access;
	private final JobQueue jobs;
	private final IdempotencyStore idempotency;
	private final AuthService auth;
	private final OpenAiResearchClient provider;

	public MaterialResearch(ObjectMapper json, ProjectAccess access, JobQueue jobs, IdempotencyStore idempotency,
			AuthService auth, OpenAiResearchClient provider) {
		this.json = json;
		this.access = access;
		this.jobs = jobs;
		this.idempotency = idempotency;
		this.auth = auth;
		this.provider = provider;
	}

	@Transactional
	public JsonNode create(UUID actor, UUID projectId, String keyValue, JsonNode body) {
		access.lock(projectId);
		access.requireMember(projectId, actor);
		if (keyValue == null || keyValue.isBlank() || keyValue.length() > 128) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		var key = IdempotencyStore.Key.of(actor, "POST", "/v1/projects/" + projectId + "/research-jobs", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
		if (stored.isPresent()) {
			return json.readTree(stored.get().body());
		}
		if (!provider.available()) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		String topic = topic(body);
		auth.rateLimit("research:" + actor, HOURLY_LIMIT, 3600);
		var snapshot = json.createObjectNode();
		snapshot.set("input", json.valueToTree(new Input(topic, provider.model(), PROMPT_VERSION)));
		UUID jobId = jobs.register(projectId, actor, TYPE, "project", projectId, snapshot);
		var response = json.valueToTree(jobs.get(actor, jobId));
		idempotency.complete(key, true, 202, json.writeValueAsString(response), jobId);
		return response;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Object execute(JobQueue.Job job) {
		Input input = input(job);
		requireMember(job);
		return provider.research(input.model(), input.topic());
	}

	@Override
	public ObjectNode publish(JobQueue.Job job, Object output) {
		Input input = input(job);
		requireMember(job);
		var result = (OpenAiResearchClient.Result) output;
		var node = json.createObjectNode().put("type", TYPE).put("topic", input.topic()).put("markdown", result.markdown());
		node.set("sources", json.valueToTree(result.sources()));
		return node;
	}

	@Override
	public void settle(JobQueue.Job job, String status) {
		// 조사 결과는 작업에만 남고 다른 대상의 상태를 바꾸지 않는다
	}

	private Input input(JobQueue.Job job) {
		Input input = json.treeToValue(job.input().get("input"), Input.class);
		if (input == null || !PROMPT_VERSION.equals(input.promptVersion())) {
			throw new JobFailure("JOB_INPUT_STALE", "조사 방식이 바뀌었어요. 다시 요청해 주세요.", false);
		}
		return input;
	}

	private void requireMember(JobQueue.Job job) {
		try {
			access.requireMember(job.projectId(), job.requesterId());
		}
		catch (ApiException ex) {
			throw new JobFailure("JOB_RESEARCH_ACCESS_REVOKED", "프로젝트 접근 권한이 없어 조사하지 않았어요.", false);
		}
	}

	private static String topic(JsonNode body) {
		if (body != null && body.isObject() && body.size() == 1 && body.path("topic").isString()) {
			String topic = body.path("topic").asString().strip();
			if (!topic.isEmpty() && topic.codePointCount(0, topic.length()) <= TOPIC_MAX) {
				return topic;
			}
		}
		throw ApiException.field(ErrorCode.VALIDATION_FAILED, "topic", "INVALID_TOPIC",
				"조사할 주제를 " + TOPIC_MAX + "자 안으로 입력해 주세요.");
	}
}
