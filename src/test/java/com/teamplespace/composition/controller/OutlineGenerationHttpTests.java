package com.teamplespace.composition.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.teamplespace.composition.service.OutlineGeneration;
import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.support.DocumentFixture;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OutlineGenerationHttpTests extends HttpIntegrationSupport {

	private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
	private static final AtomicInteger calls = new AtomicInteger();
	private static final AtomicInteger remainingFailures = new AtomicInteger();
	private static final AtomicInteger sourceNumber = new AtomicInteger(1);
	private static final AtomicReference<JsonNode> providerRequest = new AtomicReference<>();
	private static final HttpServer provider = startProvider();

	@Autowired
	private JobQueue jobs;

	@Autowired
	private OutlineGeneration generation;

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry properties) {
		properties.add("teamplespace.outline.ai.enabled", () -> true);
		properties.add("teamplespace.outline.ai.key", () -> "local-provider-test");
		properties.add("teamplespace.outline.ai.model", () -> "outline-test-model");
		properties.add("teamplespace.outline.ai.endpoint", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/responses");
	}

	@BeforeEach
	void resetQueue() {
		new DocumentFixture(jdbc).reset();
		calls.set(0);
		remainingFailures.set(0);
		sourceNumber.set(1);
		providerRequest.set(null);
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void registersOnceRetriesAndPublishesOnlyPinnedEvidence() throws Exception {
		Browser owner = signup("구성 생성 소유자");
		Browser member = signup("구성 생성 팀원");
		Browser outsider = signup("구성 생성 외부인");
		String project = createProject(owner, "구성 생성");
		join(owner, member, project);
		assertThat(member.get("/capabilities").get("featureFlags").get("outlineGeneration").asBoolean()).isTrue();
		JsonNode material = memo(owner, project, "선택 원문");
		String id = material.get("id").asString();
		memo(owner, project, "선택하지 않은 원문");
		String another = createProject(owner, "다른 프로젝트");
		String otherMaterial = memo(owner, another, "다른 원문").get("id").asString();
		String path = "/projects/" + project + "/outline-jobs";
		String outline = "/projects/" + project + "/outline";
		var input = Map.of("expectedVersion", 1, "sourceIds", List.of(id), "instructions", "검증 지침");
		assertThat(ContractSchemas.validate("stage-3-generation.schema.json#/$defs/OutlineJobRequest",
				json.writeValueAsString(input))).isEmpty();
		member.post(path, Map.of("expectedVersion", 1, "sourceIds", List.of(id, otherMaterial)), 422);
		outsider.post(path, input, 404);

		String key = UUID.randomUUID().toString();
		JsonNode queued = member.request("POST", path, input, 202, key);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Job", json.writeValueAsString(queued))).isEmpty();
		assertThat(queued.get("inputVersions").get("outlineVersion").asLong()).isEqualTo(1);
		assertThat(queued.get("status").asString()).isEqualTo("queued");
		String job = "/jobs/" + queued.get("id").asString();
		outsider.request("GET", job, null, 404);
		assertThat(member.request("POST", path, input, 202, key)).isEqualTo(queued);
		assertThat(member.post(path, input, 409).get("error").get("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
		assertThat(member.request("POST", path, Map.of("expectedVersion", 1, "sourceIds", List.of(id)), 409, key)
				.get("error").get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

		remainingFailures.set(1);
		jobs.run(claim(queued));
		assertThat(owner.get(job).get("status").asString()).isEqualTo("queued");
		jobs.run(claim(queued));
		assertThat(calls.get()).isEqualTo(2);
		JsonNode sent = providerRequest.get();
		assertThat(sent.get("store").asBoolean()).isFalse();
		assertThat(sent.get("text").get("format").get("strict").asBoolean()).isTrue();
		assertThat(sent.get("model").asString()).isEqualTo("outline-test-model");
		JsonNode sentInput = json.readTree(sent.get("input").asString());
		assertThat(sentInput.get("instructions").asString()).isEqualTo("검증 지침");
		assertThat(sentInput.get("sources").size()).isEqualTo(1);
		assertThat(sentInput.get("sources").get(0).get("text").asString()).isEqualTo("선택 원문");

		JsonNode succeeded = member.get(job);
		assertThat(succeeded.get("status").asString()).isEqualTo("succeeded");
		assertThat(succeeded.get("attempt").asInt()).isEqualTo(2);
		assertThat(succeeded.get("resultRef").get("outlineVersion").asLong()).isEqualTo(2);
		JsonNode saved = owner.get(outline);
		assertThat(saved.get("version").asLong()).isEqualTo(2);
		assertThat(saved.get("items").get(0).get("sourceRefs").get(0).get("revisionId"))
			.isEqualTo(material.get("currentRevision").get("id"));
		assertThat(member.request("POST", path, input, 202, key)).isEqualTo(queued);
		assertThat(member.post(path, input, 409).get("error").get("currentVersions").get("outlineVersion").asLong())
			.isEqualTo(2);
		assertThat(owner.get(outline)).isEqualTo(saved);
	}

	@Test
	void invalidResultChangesNothingAndCanBeRequestedAgain() throws Exception {
		Browser owner = signup("잘못된 결과 검증");
		String project = createProject(owner, "생성 실패");
		String url = "/projects/" + project + "/outline-jobs";
		String source = memo(owner, project, "참고 원문").get("id").asString();
		var request = Map.of("expectedVersion", 1, "sourceIds", List.of(source));
		JsonNode queued = owner.post(url, request, 202);
		sourceNumber.set(2);
		jobs.run(claim(queued));
		assertFailure(owner, queued, "JOB_OUTLINE_INVALID_RESULT");
		assertThat(owner.get("/projects/" + project + "/outline").get("version").asLong()).isEqualTo(1);

		sourceNumber.set(1);
		JsonNode next = owner.post(url, request, 202);
		assertThat(next.get("id")).isNotEqualTo(queued.get("id"));
		assertThat(owner.request("POST", path(next) + "/cancel", null, 200).get("status").asString()).isEqualTo("canceled");
		assertThat(jobs.claim("outline-test")).isEmpty();
	}

	@Test
	void neverPublishesAfterTheOutlineOrSourceChangesOrRequesterLosesAccess() throws Exception {
		Browser owner = signup("생성 경합 소유자");
		Browser member = signup("생성 경합 팀원");
		String project = createProject(owner, "생성 경합");
		join(owner, member, project);
		String projectUrl = "/projects/" + project;
		String outline = projectUrl + "/outline";
		String url = projectUrl + "/outline-jobs";
		String material = memo(owner, project, "예전 원문").get("id").asString();

		JsonNode first = member.post(url, Map.of("expectedVersion", 1, "sourceIds", List.of(material)), 202);
		var claim = claim(first);
		Object result = generation.execute(claim.job());
		JsonNode manual = owner.request("PUT", outline, Map.of("expectedVersion", 1,
			"items", List.of(Map.of("title", "수동 변경", "sourceIds", List.of()))), 200, UUID.randomUUID().toString());
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertFailure(owner, first, "JOB_INPUT_STALE");
		assertThat(owner.get(outline)).isEqualTo(manual);

		var input = Map.of("expectedVersion", 2, "sourceIds", List.of(material));
		JsonNode second = member.post(url, input, 202);
		claim = claim(second);
		result = generation.execute(claim.job());
		owner.request("PATCH", "/materials/" + material, Map.of("expectedVersion", 1, "markdown", "바뀐 원문"), 200);
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertFailure(owner, second, "JOB_INPUT_STALE");

		JsonNode third = member.post(url, input, 202);
		claim = claim(third);
		result = generation.execute(claim.job());
		removeMember(owner, project, member.userId);
		member.request("GET", path(third), null, 403);
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertFailure(owner, third, "JOB_OUTLINE_ACCESS_REVOKED");

		JsonNode fourth = owner.post(url, input, 202);
		claim = claim(fourth);
		result = generation.execute(claim.job());
		owner.request("POST", path(fourth) + "/cancel", null, 200);
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertThat(owner.get(path(fourth)).get("status").asString()).isEqualTo("canceled");
		assertThat(owner.get(outline)).isEqualTo(manual);
	}

	private void assertFailure(Browser actor, JsonNode job, String code) throws Exception {
		JsonNode failed = actor.get(path(job));
		assertThat(failed.get("status").asString()).isEqualTo("failed");
		assertThat(failed.get("error").get("code").asString()).isEqualTo(code);
	}

	private String path(JsonNode job) {
		return "/jobs/" + job.get("id").asString();
	}

	private JobQueue.Claim claim(JsonNode job) {
		var claim = jobs.claim("outline-test").orElseThrow();
		assertThat(claim.job().id().toString()).isEqualTo(job.get("id").asString());
		return claim;
	}

	private JsonNode memo(Browser browser, String project, String text) throws Exception {
		return browser.post("/projects/" + project + "/materials/memo", Map.of("title", text, "markdown", text), 201)
			.get("material");
	}

	private static HttpServer startProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/responses", OutlineGenerationHttpTests::reply);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void reply(HttpExchange http) throws IOException {
		try (http) {
			calls.incrementAndGet();
			providerRequest.set(PROVIDER_JSON.readTree(http.getRequestBody()));
			if (remainingFailures.getAndUpdate(value -> Math.max(value - 1, 0)) > 0) {
				http.sendResponseHeaders(503, -1);
				return;
			}
			var output = PROVIDER_JSON.createObjectNode();
			output.putArray("items").addObject().put("title", "생성한 제목").put("summary", "생성한 발표 내용")
				.putArray("sourceNumbers").add(sourceNumber.get());
			var response = PROVIDER_JSON.createObjectNode().put("status", "completed");
			response.putArray("output").addObject().put("type", "message").putArray("content")
				.addObject().put("type", "output_text").put("text", PROVIDER_JSON.writeValueAsString(output));
			byte[] encoded = PROVIDER_JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
			http.getResponseHeaders().add("Content-Type", "application/json");
			http.sendResponseHeaders(200, encoded.length);
			http.getResponseBody().write(encoded);
		}
	}
}
