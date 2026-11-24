package com.teamplespace.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.support.DocumentFixture;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 문장 요청으로 만든 수정 제안이 적용 전에는 문서를 바꾸지 않고, 적용은 모두 반영하거나 하나도 반영하지 않는지 확인한다
@TestPropertySource(properties = "teamplespace.editor.sample-presentation-enabled=true")
class SlideRevisionHttpTests extends HttpIntegrationSupport {

	private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
	// 제공자가 돌려줄 글상자 번호와 글
	private static final AtomicReference<Map<Integer, String>> answer = new AtomicReference<>();
	private static final AtomicReference<JsonNode> providerRequest = new AtomicReference<>();
	private static final HttpServer provider = startProvider();

	@Autowired
	private JobQueue jobs;

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry properties) {
		properties.add("teamplespace.presentation.ai.enabled", () -> true);
		properties.add("teamplespace.presentation.ai.key", () -> "local-provider-test");
		properties.add("teamplespace.presentation.ai.model", () -> "revision-test-model");
		properties.add("teamplespace.presentation.ai.endpoint",
				() -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/responses");
	}

	@BeforeEach
	void reset() {
		new DocumentFixture(jdbc).reset();
		providerRequest.set(null);
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void proposalChangesNothingUntilTheRequesterAppliesIt() throws Exception {
		Browser owner = signup("수정 소유자");
		Browser member = signup("수정 팀원");
		Browser outsider = signup("수정 외부인");
		String project = createProject(owner, "AI 수정");
		join(owner, member, project);
		assertThat(member.get("/capabilities").get("featureFlags").get("aiRevision").asBoolean()).isTrue();
		JsonNode slide = slide(owner, project, 1);
		String url = "/slides/" + slide.get("id").asString() + "/ai-revision-jobs";

		outsider.request("POST", url, request("짧게"), 404, UUID.randomUUID().toString());
		member.request("POST", url, request("  "), 422, UUID.randomUUID().toString());
		member.request("POST", url, Map.of("generation", 2, "prompt", "짧게"), 409, UUID.randomUUID().toString());
		member.request("POST", url, request("짧게"), 400, null);

		String key = UUID.randomUUID().toString();
		JsonNode queued = member.request("POST", url, request("제목을 더 힘 있게"), 202, key);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Job", queued.toString())).isEmpty();
		assertThat(queued.get("type").asString()).isEqualTo("slide_revision");
		assertThat(member.request("POST", url, request("제목을 더 힘 있게"), 202, key)).isEqualTo(queued);

		answer.set(Map.of(1, "역할을 나눠 끝까지", 2, "자료 조사\n발표 구성\n슬라이드 제작"));
		jobs.run(claim(queued));
		JsonNode prompt = json.readTree(providerRequest.get().get("input").asString());
		assertThat(prompt.get("request").asString()).isEqualTo("제목을 더 힘 있게");
		assertThat(prompt.get("elements")).hasSize(2);
		assertThat(providerRequest.get().get("model").asString()).isEqualTo("revision-test-model");

		JsonNode done = member.get("/jobs/" + queued.get("id").asString());
		assertThat(done.get("status").asString()).isEqualTo("succeeded");
		JsonNode proposal = done.get("resultRef");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/SlideRevisionResult", proposal.toString())).isEmpty();
		assertThat(proposal.get("targets")).hasSize(1);
		assertThat(proposal.get("targets").get(0).get("before").asString()).isEqualTo("역할 나누기");
		assertThat(proposal.get("targets").get(0).get("after").asString()).isEqualTo("역할을 나눠 끝까지");
		assertThat(texts(slide(owner, project, 1))).containsExactly("역할 나누기", "자료 조사\n발표 구성\n슬라이드 제작");

		String apply = "/ai-proposals/" + queued.get("id").asString() + "/apply";
		owner.request("POST", apply, null, 403, UUID.randomUUID().toString());
		outsider.request("POST", apply, null, 404, UUID.randomUUID().toString());
		String applyKey = UUID.randomUUID().toString();
		JsonNode applied = member.request("POST", apply, null, 200, applyKey);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/SlideRevisionApplied", applied.toString())).isEmpty();
		assertThat(applied.get("changes").get(0).get("resource").get("content").get("text").asString())
			.isEqualTo("역할을 나눠 끝까지");
		assertThat(member.request("POST", apply, null, 200, applyKey)).isEqualTo(applied);
		assertThat(texts(slide(owner, project, 1))).containsExactly("역할을 나눠 끝까지", "자료 조사\n발표 구성\n슬라이드 제작");
		assertThat(member.request("POST", apply, null, 409, UUID.randomUUID().toString()).get("error").get("code")
			.asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM project_events WHERE project_id = UUID_TO_BIN(?) AND type = 'element.updated'
				""").param(project).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void laterEditRejectsTheWholeProposal() throws Exception {
		Browser owner = signup("충돌 소유자");
		String project = createProject(owner, "AI 수정 충돌");
		JsonNode slide = slide(owner, project, 1);
		String url = "/slides/" + slide.get("id").asString() + "/ai-revision-jobs";

		JsonNode queued = owner.request("POST", url, request("모두 바꿔"), 202, UUID.randomUUID().toString());
		answer.set(Map.of(1, "새 제목", 2, "새 본문"));
		jobs.run(claim(queued));

		// 제안을 만든 뒤 본문을 직접 고친다
		JsonNode body = slide.get("elements").get(1);
		ObjectNode patch = json.createObjectNode().put("generation", 1).put("baseVersion", body.get("version").asLong())
			.put("editGroupId", UUID.randomUUID().toString()).put("clientMutationId", UUID.randomUUID().toString())
			.put("finalizeGroup", true);
		patch.putObject("patch").putObject("content").put("text", "직접 고친 본문");
		owner.request("PATCH", "/slides/" + slide.get("id").asString() + "/elements/" + body.get("id").asString(), patch,
				200, UUID.randomUUID().toString());

		owner.request("POST", "/ai-proposals/" + queued.get("id").asString() + "/apply", null, 409,
				UUID.randomUUID().toString());
		assertThat(texts(slide(owner, project, 1))).containsExactly("역할 나누기", "직접 고친 본문");
	}

	@Test
	void unchangedOrInvalidAnswersFailWithoutProposal() throws Exception {
		Browser owner = signup("실패 소유자");
		String project = createProject(owner, "AI 수정 실패");
		JsonNode slide = slide(owner, project, 1);
		String url = "/slides/" + slide.get("id").asString() + "/ai-revision-jobs";

		JsonNode same = owner.request("POST", url, request("그대로"), 202, UUID.randomUUID().toString());
		answer.set(Map.of(1, "역할 나누기"));
		jobs.run(claim(same));
		assertFailure(owner, same, "JOB_REVISION_NO_CHANGE");

		JsonNode invalid = owner.request("POST", url, request("이상하게"), 202, UUID.randomUUID().toString());
		answer.set(Map.of(9, "없는 글상자"));
		jobs.run(claim(invalid));
		assertFailure(owner, invalid, "JOB_REVISION_INVALID_RESULT");
		owner.request("POST", "/ai-proposals/" + invalid.get("id").asString() + "/apply", null, 422,
				UUID.randomUUID().toString());
	}

	private Map<String, Object> request(String prompt) {
		return Map.of("generation", 1, "prompt", prompt);
	}

	private JsonNode slide(Browser browser, String project, int index) throws Exception {
		browser.post("/projects/" + project + "/sample-presentation", null, 204);
		return browser.get("/projects/" + project + "/sync-snapshot?scope=editor").get("presentation").get("slides")
			.get(index);
	}

	private java.util.List<String> texts(JsonNode slide) {
		var texts = new java.util.ArrayList<String>();
		slide.get("elements").forEach(element -> {
			if (element.get("type").asString().equals("text")) {
				texts.add(element.get("content").get("text").asString());
			}
		});
		return texts;
	}

	private void assertFailure(Browser actor, JsonNode job, String code) throws Exception {
		JsonNode failed = actor.get("/jobs/" + job.get("id").asString());
		assertThat(failed.get("status").asString()).isEqualTo("failed");
		assertThat(failed.get("error").get("code").asString()).isEqualTo(code);
		assertThat(failed.get("resultRef").isNull()).isTrue();
	}

	private JobQueue.Claim claim(JsonNode job) {
		var claim = jobs.claim("revision-test").orElseThrow();
		assertThat(claim.job().id().toString()).isEqualTo(job.get("id").asString());
		return claim;
	}

	private static HttpServer startProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/responses", SlideRevisionHttpTests::reply);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void reply(HttpExchange http) throws IOException {
		try (http) {
			providerRequest.set(PROVIDER_JSON.readTree(http.getRequestBody()));
			var result = PROVIDER_JSON.createObjectNode();
			var elements = result.putArray("elements");
			answer.get().entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(entry -> elements.addObject().put("number", entry.getKey()).put("text", entry.getValue()));
			var response = PROVIDER_JSON.createObjectNode().put("status", "completed");
			response.putArray("output").addObject().put("type", "message").putArray("content")
				.addObject().put("type", "output_text").put("text", PROVIDER_JSON.writeValueAsString(result));
			byte[] encoded = PROVIDER_JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
			http.getResponseHeaders().add("Content-Type", "application/json");
			http.sendResponseHeaders(200, encoded.length);
			http.getResponseBody().write(encoded);
		}
	}

}
