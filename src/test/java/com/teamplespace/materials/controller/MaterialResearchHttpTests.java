package com.teamplespace.materials.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MaterialResearchHttpTests extends HttpIntegrationSupport {

	private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
	private static final AtomicInteger remainingFailures = new AtomicInteger();
	private static final AtomicInteger calls = new AtomicInteger();
	private static final AtomicBoolean searched = new AtomicBoolean();
	private static final AtomicReference<JsonNode> providerRequest = new AtomicReference<>();
	private static final HttpServer provider = startProvider();

	@Autowired
	private JobQueue jobs;

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry properties) {
		properties.add("teamplespace.materials.research.enabled", () -> true);
		properties.add("teamplespace.materials.research.key", () -> "local-research-test");
		properties.add("teamplespace.materials.research.model", () -> "research-test-model");
		properties.add("teamplespace.materials.research.endpoint", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/responses");
	}

	@BeforeEach
	void resetProvider() {
		remainingFailures.set(0);
		calls.set(0);
		searched.set(true);
		providerRequest.set(null);
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void researchKeepsOnlySourcesTheSearchActuallyOpened() throws Exception {
		Browser owner = signup("조사 소유자");
		Browser member = signup("조사 팀원");
		Browser outsider = signup("조사 외부인");
		String project = createProject(owner, "자료 조사");
		join(owner, member, project);
		assertThat(owner.get("/capabilities").path("featureFlags").path("materialResearch").asBoolean()).isTrue();

		String path = "/projects/" + project + "/research-jobs";
		var input = Map.of("topic", "  대학생 AI 활용  ");
		assertThat(ContractSchemas.validate("stage-2.schema.json#/$defs/MaterialResearchJobRequest",
				json.writeValueAsString(input))).isEmpty();
		outsider.request("POST", path, input, 404, UUID.randomUUID().toString());
		member.request("POST", path, input, 400);
		assertThat(member.request("POST", path, Map.of("topic", " "), 422, UUID.randomUUID().toString())
				.path("error").path("fieldErrors").get(0).path("field").asString()).isEqualTo("topic");
		member.request("POST", path, Map.of("topic", "가".repeat(201)), 422, UUID.randomUUID().toString());
		assertThat(calls.get()).isZero();

		String key = UUID.randomUUID().toString();
		JsonNode queued = member.request("POST", path, input, 202, key);
		assertThat(queued.path("type").asString()).isEqualTo("material_research");
		assertThat(queued.path("targetType").asString()).isEqualTo("project");
		assertThat(queued.path("targetId").asString()).isEqualTo(project);
		assertThat(member.request("POST", path, input, 202, key)).isEqualTo(queued);

		remainingFailures.set(1);
		jobs.run(claim(queued));
		assertThat(owner.get(jobPath(queued)).path("status").asString()).isEqualTo("queued");
		jobs.run(claim(queued));
		assertThat(calls.get()).isEqualTo(2);
		JsonNode sent = providerRequest.get();
		assertThat(sent.path("store").asBoolean()).isFalse();
		assertThat(sent.path("model").asString()).isEqualTo("research-test-model");
		assertThat(sent.path("tools").get(0).path("type").asString()).isEqualTo("web_search");
		assertThat(json.readTree(sent.path("input").asString()).path("topic").asString()).isEqualTo("대학생 AI 활용");

		JsonNode done = member.get(jobPath(queued));
		assertThat(done.path("status").asString()).isEqualTo("succeeded");
		JsonNode result = done.path("resultRef");
		assertThat(ContractSchemas.validate("stage-2.schema.json#/$defs/MaterialResearchResult",
				json.writeValueAsString(result))).isEmpty();
		assertThat(result.path("topic").asString()).isEqualTo("대학생 AI 활용");
		// 버린 출처의 인용은 지우고 남은 출처는 새 번호로 바꾼다
		assertThat(result.path("markdown").asString()).isEqualTo("## 현황\n- 과제 조사에 많이 쓴다 [1]\n- 가이드가 있다 [2]");
		assertThat(result.path("sources")).hasSize(2);
		assertThat(result.path("sources").get(0).path("url").asString()).isEqualTo("https://example.com/survey");
		assertThat(result.path("sources").get(1).path("url").asString()).isEqualTo("https://example.org/guide/");
		// 자료로 저장하지 않는다
		assertThat(owner.get("/projects/" + project + "/materials").path("items")).isEmpty();
	}

	@Test
	void resultWithoutSearchedSourcesFailsAndRevokedRequesterGetsNothing() throws Exception {
		Browser owner = signup("출처 확인 소유자");
		Browser member = signup("출처 확인 팀원");
		String project = createProject(owner, "출처 확인");
		join(owner, member, project);
		String path = "/projects/" + project + "/research-jobs";

		searched.set(false);
		JsonNode unverified = member.request("POST", path, Map.of("topic", "주제"), 202, UUID.randomUUID().toString());
		jobs.run(claim(unverified));
		JsonNode failed = member.get(jobPath(unverified));
		assertThat(failed.path("status").asString()).isEqualTo("failed");
		assertThat(failed.path("error").path("code").asString()).isEqualTo("JOB_RESEARCH_INVALID_RESULT");

		searched.set(true);
		JsonNode revoked = member.request("POST", path, Map.of("topic", "주제"), 202, UUID.randomUUID().toString());
		var claim = claim(revoked);
		removeMember(owner, project, member.userId);
		jobs.run(claim);
		assertThat(owner.get(jobPath(revoked)).path("error").path("code").asString()).isEqualTo("JOB_RESEARCH_ACCESS_REVOKED");
		assertThat(calls.get()).isEqualTo(1);
	}

	private JobQueue.Claim claim(JsonNode job) {
		jdbc.sql("UPDATE jobs SET priority = 1 WHERE id = UUID_TO_BIN(?)")
			.param(job.path("id").asString()).update();
		var claim = jobs.claim("research-test").orElseThrow();
		assertThat(claim.job().id().toString()).isEqualTo(job.path("id").asString());
		return claim;
	}

	private static String jobPath(JsonNode job) {
		return "/jobs/" + job.path("id").asString();
	}

	private static HttpServer startProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/responses", MaterialResearchHttpTests::reply);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// 검색 도구가 연 주소와 인용 주소만 출처로 남는지 보려고 확인하지 않은 주소를 섞어 돌려준다
	private static void reply(HttpExchange http) throws IOException {
		try (http) {
			calls.incrementAndGet();
			providerRequest.set(PROVIDER_JSON.readTree(http.getRequestBody()));
			if (remainingFailures.getAndUpdate(value -> Math.max(value - 1, 0)) > 0) {
				http.sendResponseHeaders(503, -1);
				return;
			}
			var output = PROVIDER_JSON.createObjectNode().put("markdown", "## 현황\n- 과제 조사에 많이 쓴다 [1] [2]\n- 가이드가 있다 [4] [6]");
			var sources = output.putArray("sources");
			sources.addObject().put("title", "대학생 AI 이용 조사").put("url", "https://example.com/survey");
			sources.addObject().put("title", "만든 주소").put("url", "https://invented.example/page");
			sources.addObject().put("title", "중복").put("url", "https://example.com/survey/");
			sources.addObject().put("title", "가이드").put("url", "https://example.org/guide/");
			sources.addObject().put("title", "스크립트").put("url", "javascript:alert(1)");
			sources.addObject().put("title", "다른 포트").put("url", "https://example.com:8443/survey");
			sources.addObject().put("title", "다른 프로토콜").put("url", "http://example.org/guide");
			var response = PROVIDER_JSON.createObjectNode().put("status", "completed");
			var items = response.putArray("output");
			if (searched.get()) {
				items.addObject().put("type", "web_search_call").putObject("action").putArray("sources")
					.addObject().put("type", "url").put("url", "https://example.com/survey?utm_source=openai");
			}
			var content = items.addObject().put("type", "message").putArray("content").addObject()
				.put("type", "output_text").put("text", PROVIDER_JSON.writeValueAsString(output));
			if (searched.get()) {
				content.putArray("annotations").addObject().put("type", "url_citation").put("url", "https://example.org/guide");
			}
			byte[] encoded = PROVIDER_JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
			http.getResponseHeaders().add("Content-Type", "application/json");
			http.sendResponseHeaders(200, encoded.length);
			http.getResponseBody().write(encoded);
		}
	}
}
