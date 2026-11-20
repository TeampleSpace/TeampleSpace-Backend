package com.teamplespace.materials;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.processing.JobQueue;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MaterialSummaryHttpTests extends HttpIntegrationSupport {

	private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
	private static final AtomicInteger remainingFailures = new AtomicInteger();
	private static final AtomicInteger calls = new AtomicInteger();
	private static final AtomicReference<String> answer = new AtomicReference<>();
	private static final AtomicReference<JsonNode> providerRequest = new AtomicReference<>();
	private static final HttpServer provider = startProvider();

	@Autowired
	private JobQueue jobs;

	@Autowired
	private MaterialSummary summaries;

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry properties) {
		properties.add("teamplespace.materials.ai-summary.enabled", () -> true);
		properties.add("teamplespace.materials.ai-summary.key", () -> "local-summary-test");
		properties.add("teamplespace.materials.ai-summary.model", () -> "summary-test-model");
		properties.add("teamplespace.materials.ai-summary.endpoint", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/responses");
	}

	@BeforeEach
	void resetProvider() {
		remainingFailures.set(0);
		calls.set(0);
		answer.set("정리한 핵심 내용입니다.");
		providerRequest.set(null);
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void requestRetriesOnceAndPublishesOnlySummaryForTheSelectedRevision() throws Exception {
		Browser owner = signup("요약 소유자");
		Browser member = signup("요약 팀원");
		Browser outsider = signup("요약 외부인");
		String project = createProject(owner, "자료 요약");
		join(owner, member, project);
		assertThat(owner.get("/capabilities").path("featureFlags").path("materialSummary").asBoolean()).isTrue();

		String markdown = "선택한 본문 " + "가".repeat(22_000) + "생략된 부분";
		JsonNode material = memo(owner, project, "선택 자료", markdown);
		memo(owner, project, "다른 자료", "보내지 않는 본문");
		String path = materialPath(material);
		String requestPath = path + "/summary-jobs";
		String revision = revision(material);
		var input = Map.of("expectedRevisionId", revision);
		assertThat(ContractSchemas.validate("stage-2.schema.json#/$defs/MaterialSummaryJobRequest",
				json.writeValueAsString(input))).isEmpty();
		assertThat(material.path("currentRevision").path("summary").isNull()).isTrue();
		assertThat(material.has("summaryJob")).isFalse();
		outsider.post(requestPath, input, 404);
		member.request("POST", requestPath, input, 400);
		assertThat(calls.get()).isZero();

		String key = UUID.randomUUID().toString();
		JsonNode queued = member.request("POST", requestPath, input, 202, key);
		assertThat(queued.path("targetType").asString()).isEqualTo("material_summary");
		assertThat(queued.path("targetId").asString()).isEqualTo(revision);
		assertThat(queued.path("status").asString()).isEqualTo("queued");
		assertThat(owner.get(path).path("summaryJob")).isEqualTo(queued);
		assertThat(member.request("POST", requestPath, input, 202, key)).isEqualTo(queued);
		assertThat(member.post(requestPath, input, 409).path("error").path("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
		assertThat(member.request("POST", requestPath, Map.of("expectedRevisionId", UUID.randomUUID()), 409, key)
				.path("error").path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

		remainingFailures.set(1);
		jobs.run(claim(queued));
		assertThat(owner.get(jobPath(queued)).path("status").asString()).isEqualTo("queued");
		jobs.run(claim(queued));
		assertThat(calls.get()).isEqualTo(2);
		JsonNode sent = providerRequest.get();
		assertThat(sent.path("store").asBoolean()).isFalse();
		assertThat(sent.path("model").asString()).isEqualTo("summary-test-model");
		JsonNode sentInput = json.readTree(sent.path("input").asString());
		assertThat(sentInput.size()).isEqualTo(2);
		assertThat(sentInput.path("title").asString()).isEqualTo("선택 자료");
		assertThat(sentInput.path("text").asString()).hasSize(20_000).startsWith("선택한 본문")
			.doesNotContain("생략된 부분", "보내지 않는 본문");

		JsonNode saved = owner.get(path);
		assertThat(ContractSchemas.validate("stage-2.schema.json#/$defs/Material", json.writeValueAsString(saved))).isEmpty();
		assertThat(saved.path("currentRevision").path("summary").asString()).isEqualTo(answer.get());
		assertThat(saved.path("currentRevision").path("excerpt")).isEqualTo(material.path("currentRevision").path("excerpt"));
		assertThat(saved.path("version")).isEqualTo(material.path("version"));
		assertThat(saved.path("summaryJob").path("status").asString()).isEqualTo("succeeded");
		assertThat(saved.path("summaryJob").path("resultRef").path("revisionId").asString()).isEqualTo(revision);
		assertThat(owner.get(path + "/source?revisionId=" + revision).path("markdown").asString()).isEqualTo(markdown);
		assertThat(member.request("POST", requestPath, input, 202, key)).isEqualTo(queued);
		member.post(requestPath, input, 409);
	}

	@Test
	void changingTheSourceOrRevokingAccessDoesNotPublishAnOldResult() throws Exception {
		Browser owner = signup("원문 검증 소유자");
		Browser member = signup("원문 검증 팀원");
		String project = createProject(owner, "개정별 요약");
		join(owner, member, project);
		JsonNode material = memo(owner, project, "메모", "기존 원문");
		String path = materialPath(material);
		String requestPath = path + "/summary-jobs";
		JsonNode first = member.post(requestPath, Map.of("expectedRevisionId", revision(material)), 202);
		var claim = claim(first);
		Object result = summaries.execute(claim.job());
		JsonNode edited = owner.request("PATCH", path, Map.of("expectedVersion", 1, "markdown", "수정한 원문"), 200);
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertThat(owner.get(jobPath(first)).path("status").asString()).isEqualTo("canceled");
		assertThat(owner.get(path).has("summaryJob")).isFalse();
		assertThat(owner.get(path).path("currentRevision").path("summary").isNull()).isTrue();
		assertThat(owner.get(path + "/source?revisionId=" + revision(material)).path("markdown").asString()).isEqualTo("기존 원문");
		owner.post(requestPath, Map.of("expectedRevisionId", revision(material)), 409);

		JsonNode second = member.post(requestPath, Map.of("expectedRevisionId", revision(edited)), 202);
		claim = claim(second);
		result = summaries.execute(claim.job());
		removeMember(owner, project, member.userId);
		assertThat(jobs.succeed(claim, result)).isTrue();
		JsonNode failed = owner.get(jobPath(second));
		assertThat(failed.path("status").asString()).isEqualTo("failed");
		assertThat(failed.path("error").path("code").asString()).isEqualTo("JOB_MATERIAL_SUMMARY_ACCESS_REVOKED");
		assertThat(owner.get(path).path("currentRevision").path("summary").isNull()).isTrue();
		assertThat(owner.get(path).path("status").asString()).isEqualTo("ready");
	}

	@Test
	void anInvalidSummaryCanBeRequestedAgainWithoutChangingTheSource() throws Exception {
		Browser owner = signup("요약 실패 검증");
		String project = createProject(owner, "요약 실패");
		JsonNode material = memo(owner, project, "자료", "본문");
		String path = materialPath(material);
		var input = Map.of("expectedRevisionId", revision(material));
		answer.set("   ");
		JsonNode first = owner.post(path + "/summary-jobs", input, 202);
		jobs.run(claim(first));
		JsonNode failed = owner.get(jobPath(first));
		assertThat(failed.path("status").asString()).isEqualTo("failed");
		assertThat(failed.path("error").path("code").asString()).isEqualTo("JOB_MATERIAL_SUMMARY_INVALID_RESULT");
		assertThat(owner.get(path).path("currentRevision").path("summary").isNull()).isTrue();

		answer.set("다시 생성한 요약입니다.");
		JsonNode retried = owner.post(path + "/summary-jobs", input, 202);
		jobs.run(claim(retried));
		assertThat(owner.get(path).path("currentRevision").path("summary").asString()).isEqualTo(answer.get());
		assertThat(owner.get(path + "/source").path("markdown").asString()).isEqualTo("본문");
	}

	private JsonNode memo(Browser browser, String project, String title, String markdown) throws Exception {
		return browser.post("/projects/" + project + "/materials/memo", Map.of("title", title, "markdown", markdown), 201)
			.path("material");
	}

	private JobQueue.Claim claim(JsonNode job) {
		jdbc.sql("UPDATE jobs SET priority = 1 WHERE id = UUID_TO_BIN(?)")
			.param(job.path("id").asString()).update();
		var claim = jobs.claim("summary-test").orElseThrow();
		assertThat(claim.job().id().toString()).isEqualTo(job.path("id").asString());
		return claim;
	}

	private static String materialPath(JsonNode material) {
		return "/materials/" + material.path("id").asString();
	}

	private static String revision(JsonNode material) {
		return material.path("currentRevision").path("id").asString();
	}

	private static String jobPath(JsonNode job) {
		return "/jobs/" + job.path("id").asString();
	}

	private static HttpServer startProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/responses", MaterialSummaryHttpTests::reply);
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
			var output = PROVIDER_JSON.createObjectNode().put("summary", answer.get());
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
