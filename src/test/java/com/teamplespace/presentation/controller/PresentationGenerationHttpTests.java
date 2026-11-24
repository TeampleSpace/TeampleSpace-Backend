package com.teamplespace.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import com.teamplespace.composition.service.ProjectFontTests;
import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.presentation.service.PresentationGeneration;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.support.DocumentFixture;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PresentationGenerationHttpTests extends HttpIntegrationSupport {

	private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
	private static final AtomicInteger calls = new AtomicInteger();
	private static final AtomicInteger remainingFailures = new AtomicInteger();
	private static final AtomicInteger slideCount = new AtomicInteger(2);
	private static final AtomicReference<JsonNode> providerRequest = new AtomicReference<>();
	private static final HttpServer provider = startProvider();

	@Autowired
	private JobQueue jobs;

	@Autowired
	private PresentationGeneration generation;

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry properties) {
		properties.add("teamplespace.presentation.ai.enabled", () -> true);
		properties.add("teamplespace.presentation.ai.key", () -> "local-provider-test");
		properties.add("teamplespace.presentation.ai.model", () -> "presentation-test-model");
		properties.add("teamplespace.presentation.ai.endpoint",
				() -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/responses");
	}

	@BeforeEach
	void reset() {
		new DocumentFixture(jdbc).reset();
		calls.set(0);
		remainingFailures.set(0);
		slideCount.set(2);
		providerRequest.set(null);
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void generatesOneDocumentFromPinnedOutlineDesignAndSources() throws Exception {
		Browser owner = signup("발표 생성 소유자");
		Browser member = signup("발표 생성 팀원");
		Browser outsider = signup("발표 생성 외부인");
		String project = createProject(owner, "발표 생성");
		join(owner, member, project);
		assertThat(member.get("/capabilities").get("featureFlags").get("presentationGeneration").asBoolean()).isTrue();
		String linked = memo(owner, project, "선택 원문");
		String unselected = memo(owner, project, "다른 원문");
		saveOutline(owner, project, 1, linked);
		JsonNode template = owner.get("/templates").get("items").get(3);
		saveTemplate(owner, project, 1, template);

		String url = "/projects/" + project + "/presentation-jobs";
		var input = Map.of("outlineVersion", 2, "designVersion", 2, "sourceIds", List.of(linked));
		assertThat(ContractSchemas.validate("stage-3-generation.schema.json#/$defs/PresentationGenerationRequest",
				json.writeValueAsString(input))).isEmpty();
		outsider.request("POST", url, input, 404, UUID.randomUUID().toString());
		JsonNode conflict = member.request("POST", url, Map.of("outlineVersion", 1, "designVersion", 2,
				"sourceIds", List.of(linked)), 409, UUID.randomUUID().toString()).get("error").get("currentVersions");
		assertThat(conflict.get("outlineVersion").asLong()).isEqualTo(2);
		assertThat(conflict.get("designVersion").asLong()).isEqualTo(2);
		member.request("POST", url, Map.of("outlineVersion", 2, "designVersion", 2, "sourceIds", List.of(unselected)),
				422, UUID.randomUUID().toString());

		String key = UUID.randomUUID().toString();
		JsonNode queued = member.request("POST", url, input, 202, key);
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Job", queued.toString())).isEmpty();
		assertThat(queued.get("type").asString()).isEqualTo("presentation_generation");
		assertThat(queued.get("inputVersions").get("outlineVersion").asLong()).isEqualTo(2);
		assertThat(queued.get("inputVersions").get("designVersion").asLong()).isEqualTo(2);
		assertThat(member.request("POST", url, input, 202, key)).isEqualTo(queued);
		assertThat(owner.request("POST", url, input, 409, UUID.randomUUID().toString()).get("error").get("code")
			.asString()).isEqualTo("REQUEST_IN_PROGRESS");
		String pinned = jdbc.sql("SELECT CAST(input_snapshot AS CHAR) FROM jobs WHERE id = UUID_TO_BIN(?)")
			.param(queued.get("id").asString()).query(String.class).single();
		assertThat(ContractSchemas.validate("stage-3-generation.schema.json#/$defs/PresentationJobInput",
				json.readTree(pinned).get("input").toString())).isEmpty();

		remainingFailures.set(1);
		jobs.run(claim(queued));
		assertThat(owner.get(path(queued)).get("status").asString()).isEqualTo("queued");
		jobs.run(claim(queued));
		assertThat(calls.get()).isEqualTo(2);
		JsonNode sent = providerRequest.get();
		assertThat(sent.get("store").asBoolean()).isFalse();
		assertThat(sent.get("model").asString()).isEqualTo("presentation-test-model");
		JsonNode prompt = json.readTree(sent.get("input").asString());
		assertThat(prompt.get("items")).hasSize(2);
		assertThat(prompt.get("items").get(0).get("sourceNumbers").get(0).asInt()).isEqualTo(1);
		assertThat(prompt.get("items").get(1).get("sourceNumbers")).isEmpty();
		assertThat(prompt.get("sources")).hasSize(1);
		assertThat(prompt.get("sources").get(0).get("text").asString()).isEqualTo("선택 원문");

		JsonNode done = member.get(path(queued));
		assertThat(done.get("status").asString()).isEqualTo("succeeded");
		assertThat(ContractSchemas.validate("stage-3-generation.schema.json#/$defs/PresentationJobResult",
				done.get("resultRef").toString())).isEmpty();
		JsonNode snapshot = member.get("/projects/" + project + "/sync-snapshot?scope=editor");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/SyncSnapshot", snapshot.toString())).isEmpty();
		JsonNode document = snapshot.get("presentation");
		assertThat(ContractSchemas.validate("document.schema.json#/$defs/Presentation", document.toString())).isEmpty();
		assertThat(document.get("id").asString()).isEqualTo(done.get("resultRef").get("presentationId").asString());
		List<String> layouts = new ArrayList<>();
		document.get("slides").forEach(slide -> layouts.add(slide.get("layoutId").asString()));
		assertThat(layouts).containsExactly("title", "cards", "compare", "summary");
		JsonNode cover = document.get("slides").get(0).get("elements");
		assertThat(cover.get(0).get("style").get("fill").asString())
			.isEqualTo(template.get("preview").get("backgroundColor").asString());
		// 미리보기의 표지 장식은 생성한 발표와 같은 자리와 색이다
		JsonNode previewCover = owner.get("/templates/" + template.get("id").asString() + "/preview").get("slides").get(0)
			.get("elements");
		assertThat(shapes(previewCover)).isNotEmpty().isEqualTo(shapes(cover));
		assertThat(texts(document.get("slides").get(0))).contains("생성한 발표 제목");
		assertThat(texts(document.get("slides").get(1))).contains("항목 1", "핵심 문장", "첫째 요점", "첫째 문장", "출처: 선택 원문");
		assertThat(texts(document.get("slides").get(2))).doesNotContain("출처: 선택 원문");
		assertThat(texts(document.get("slides").get(3))).contains("정리");

		JsonNode event = json.readTree(jdbc.sql("""
				SELECT CAST(payload AS CHAR) FROM project_events
				WHERE project_id = UUID_TO_BIN(?) AND type = 'presentation.replaced'
				""").param(project).query(String.class).single());
		assertThat(event.get("presentationVersion").asLong()).isEqualTo(1);
		assertThat(event.get("generation").asLong()).isEqualTo(1);
		assertThat(owner.request("POST", url, input, 409, UUID.randomUUID().toString()).get("error").get("code")
			.asString()).isEqualTo("REGENERATION_NOT_ENABLED");
	}

	@Test
	void keepsNothingWhenTheResultIsInvalidOrInputsChange() throws Exception {
		Browser owner = signup("생성 경합 소유자");
		Browser member = signup("생성 경합 팀원");
		String project = createProject(owner, "생성 경합");
		join(owner, member, project);
		String source = memo(owner, project, "참고 원문");
		saveOutline(owner, project, 1, source);
		JsonNode templates = owner.get("/templates").get("items");
		saveTemplate(owner, project, 1, templates.get(0));
		String url = "/projects/" + project + "/presentation-jobs";

		slideCount.set(3);
		JsonNode invalid = owner.request("POST", url, request(2, 2, source), 202, UUID.randomUUID().toString());
		jobs.run(claim(invalid));
		assertFailure(owner, invalid, "JOB_PRESENTATION_INVALID_RESULT");
		assertThat(presentations(project)).isZero();
		slideCount.set(2);

		JsonNode designChanged = member.request("POST", url, request(2, 2, source), 202, UUID.randomUUID().toString());
		var claim = claim(designChanged);
		Object result = generation.execute(claim.job());
		saveTemplate(owner, project, 2, templates.get(1));
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertFailure(owner, designChanged, "JOB_INPUT_STALE");

		JsonNode revoked = member.request("POST", url, request(2, 3, source), 202, UUID.randomUUID().toString());
		claim = claim(revoked);
		result = generation.execute(claim.job());
		removeMember(owner, project, member.userId);
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertFailure(owner, revoked, "JOB_PRESENTATION_ACCESS_REVOKED");

		JsonNode raced = owner.request("POST", url, request(2, 3, source), 202, UUID.randomUUID().toString());
		claim = claim(raced);
		result = generation.execute(claim.job());
		jdbc.sql("""
				INSERT INTO presentations (id, project_id, schema_version, canvas_width, canvas_height, canvas_unit)
				VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 1, 1280, 720, 'px')
				""").params(UUID.randomUUID().toString(), project).update();
		assertThat(jobs.succeed(claim, result)).isTrue();
		assertFailure(owner, raced, "JOB_INPUT_STALE");
		assertThat(presentations(project)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM slides WHERE project_id = UUID_TO_BIN(?)").param(project)
			.query(Integer.class).single()).isZero();
	}

	@Test
	void customDesignKeepsProjectFontAndReadableHeadings() throws Exception {
		Browser owner = signup("직접 디자인 소유자");
		String project = createProject(owner, "직접 디자인 생성");
		String source = memo(owner, project, "첫 줄\n둘\n셋\n넷");
		saveOutline(owner, project, 1, source);
		String csrf = owner.bootstrap().get("csrfToken").asString();
		var uploaded = owner.raw("POST", "/v1/projects/" + project + "/design-fonts?fileName=team.ttf",
				ProjectFontTests.directory(false), Map.of("Origin", "http://localhost:5174", "X-CSRF-Token", csrf,
						"Content-Type", "application/octet-stream"));
		assertThat(uploaded.statusCode()).isEqualTo(200);
		String fontId = json.readTree(uploaded.body()).get("id").asString();
		owner.request("PUT", "/projects/" + project + "/design-settings", Map.of("mode", "custom", "expectedVersion", 1,
				"custom", Map.of("name", "흰 대표색", "font", "Pretendard", "fontAssetId", fontId,
						"primaryColor", "#FFFFFF")), 200, UUID.randomUUID().toString());

		JsonNode job = owner.request("POST", "/projects/" + project + "/presentation-jobs", request(2, 2, source), 202,
				UUID.randomUUID().toString());
		jobs.run(claim(job));
		JsonNode slides = owner.get("/projects/" + project + "/sync-snapshot?scope=editor").get("presentation")
			.get("slides");
		assertThat(texts(slides.get(1))).contains("출처: 첫 줄 둘 셋 넷");
		// 템플릿 없이 만들면 제공자가 고른 스위스 계열에 프로젝트 글꼴을 쓰고, 흰 대표색은 보이게 진하게 바꾼다
		JsonNode cover = slides.get(0).get("elements");
		assertThat(cover.get(0).get("style").get("fill").asString()).isEqualTo("#FFFFFF");
		assertThat(cover.get(1).get("style").get("fill").asString()).isNotEqualTo("#FFFFFF");
		slides.forEach(slide -> slide.get("elements").forEach(element -> {
			if (element.get("type").asString().equals("text")) {
				assertThat(element.get("style").get("fontFamily").asString()).isEqualTo("ProjectFont-" + fontId);
			}
		}));
	}

	private Map<String, Object> request(long outline, long design, String source) {
		return Map.of("outlineVersion", outline, "designVersion", design, "sourceIds", List.of(source));
	}

	private void saveOutline(Browser owner, String project, long version, String source) throws Exception {
		owner.request("PUT", "/projects/" + project + "/outline", Map.of("expectedVersion", version, "items", List.of(
				Map.of("title", "항목 1", "summary", "근거가 있는 항목", "sourceIds", List.of(source)),
				Map.of("title", "항목 2", "sourceIds", List.of()))), 200, UUID.randomUUID().toString());
	}

	private static List<String> shapes(JsonNode elements) {
		List<String> shapes = new ArrayList<>();
		elements.forEach(element -> {
			if (element.get("type").asString().equals("shape")) {
				shapes.add(element.get("geometry").toString() + element.get("style"));
			}
		});
		return shapes;
	}

	private void saveTemplate(Browser owner, String project, long version, JsonNode choice) throws Exception {
		owner.request("PUT", "/projects/" + project + "/design-settings", Map.of("mode", "template",
				"expectedVersion", version, "templateId", choice.get("id").asString(),
				"templateVersion", choice.get("version").asLong()), 200, UUID.randomUUID().toString());
	}

	private String memo(Browser browser, String project, String text) throws Exception {
		return browser.post("/projects/" + project + "/materials/memo", Map.of("title", text, "markdown", text), 201)
			.get("material").get("id").asString();
	}

	private List<String> texts(JsonNode slide) {
		List<String> texts = new ArrayList<>();
		slide.get("elements").forEach(element -> {
			if (element.get("type").asString().equals("text")) {
				texts.add(element.get("content").get("text").asString());
			}
		});
		return texts;
	}

	private int presentations(String project) {
		return jdbc.sql("SELECT COUNT(*) FROM presentations WHERE project_id = UUID_TO_BIN(?)").param(project)
			.query(Integer.class).single();
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
		var claim = jobs.claim("presentation-test").orElseThrow();
		assertThat(claim.job().id().toString()).isEqualTo(job.get("id").asString());
		return claim;
	}

	private static HttpServer startProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/responses", PresentationGenerationHttpTests::reply);
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
			var deck = PROVIDER_JSON.createObjectNode().put("title", "생성한 발표 제목").put("subtitle", "생성한 소개 문장")
				.put("style", "swiss");
			var slides = deck.putArray("slides");
			for (int index = 1; index <= slideCount.get(); index++) {
				var slide = slides.addObject().put("layout", index % 2 == 1 ? "cards" : "compare")
					.put("title", "항목 " + index).put("message", "핵심 문장").put("figure", "");
				var points = slide.putArray("points");
				points.addObject().put("head", "첫째 요점").put("body", "첫째 문장").put("figure", "312명");
				points.addObject().put("head", "둘째 요점").put("body", "둘째 문장").put("figure", "");
			}
			deck.putObject("closing").put("layout", "list").put("title", "정리").put("message", "").put("figure", "")
				.putArray("points").addObject().put("head", "마무리").put("body", "마무리 문장").put("figure", "");
			var response = PROVIDER_JSON.createObjectNode().put("status", "completed");
			response.putArray("output").addObject().put("type", "message").putArray("content")
				.addObject().put("type", "output_text").put("text", PROVIDER_JSON.writeValueAsString(deck));
			byte[] encoded = PROVIDER_JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
			http.getResponseHeaders().add("Content-Type", "application/json");
			http.sendResponseHeaders(200, encoded.length);
			http.getResponseBody().write(encoded);
		}
	}
}
