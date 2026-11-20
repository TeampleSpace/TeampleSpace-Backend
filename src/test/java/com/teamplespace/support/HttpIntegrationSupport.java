package com.teamplespace.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.teamplespace.identity.service.ChallengeMail;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 실제 HTTP 서버와 MySQL로 여러 계정의 요청을 보내는 테스트의 공통 준비
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"teamplespace.auth.secret=http-integration-test-only-not-production-2026",
		"teamplespace.auth.frontend-origin=http://localhost:5174",
		"teamplespace.auth.backend-origin=http://localhost:8081",
		"teamplespace.auth.secure-cookie=false",
		"teamplespace.auth.mail-from=noreply@example.test",
		"spring.mail.host=127.0.0.1",
		"teamplespace.auth.terms-version=http-test-v1",
		"teamplespace.auth.terms-url=http://localhost:5174/legal/terms",
		"teamplespace.auth.privacy-version=http-test-v1",
		"teamplespace.auth.privacy-url=http://localhost:5174/legal/privacy",
		// 내부 재시도를 기다리지 않고 바로 다시 선점한다
		"teamplespace.jobs.retry-delay=0s" })
public abstract class HttpIntegrationSupport {

	@LocalServerPort
	protected int port;

	@Autowired
	protected JdbcClient jdbc;

	@MockitoBean
	ChallengeMail mail;

	protected final ObjectMapper json = new ObjectMapper();

	private final Map<String, String> codes = new ConcurrentHashMap<>();

	@BeforeEach
	void captureTestMail() {
		doAnswer(call -> {
			codes.put(call.getArgument(0), call.getArgument(1));
			return null;
		}).when(mail).send(anyString(), anyString());
	}

	// 쿠키와 CSRF 토큰을 따로 가진 브라우저 하나
	public class Browser {

		private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

		private final HttpClient client = HttpClient.newBuilder()
			.cookieHandler(cookies)
			.connectTimeout(Duration.ofSeconds(5))
			.build();

		private String csrf;

		public String userId;

		public JsonNode request(String method, String path, Object body, int expectedStatus, String key)
				throws Exception {
			var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1" + path))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", "application/json")
				.header("Origin", "http://localhost:5174");
			if (csrf != null) {
				builder.header("X-CSRF-Token", csrf);
			}
			if (key != null) {
				builder.header("Idempotency-Key", key);
			}
			if (body != null) {
				builder.header("Content-Type", "application/json");
			}
			builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
			var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(expectedStatus);
			if (response.body().isBlank()) {
				return null;
			}
			JsonNode result = json.readTree(response.body());
			if (expectedStatus >= 400) {
				String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
				assertThat(result.get("requestId").asString()).isEqualTo(requestId);
			}
			return result;
		}

		public JsonNode request(String method, String path, Object body, int expectedStatus) throws Exception {
			return request(method, path, body, expectedStatus, null);
		}

		public JsonNode get(String path) throws Exception {
			return request("GET", path, null, 200);
		}

		// 쓰기 요청마다 새 멱등키를 쓴다
		public JsonNode post(String path, Object body, int expectedStatus) throws Exception {
			return request("POST", path, body, expectedStatus, UUID.randomUUID().toString());
		}

		// JSON이 아닌 요청과 응답. 파일 전송과 원문 받기에 쓴다
		public HttpResponse<byte[]> raw(String method, String url, byte[] body, Map<String, String> headers)
				throws Exception {
			var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + url))
				.timeout(Duration.ofSeconds(15));
			headers.forEach(builder::header);
			builder.method(method,
					body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
			return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
		}

		// 끝나지 않는 응답을 줄 단위로 읽는다. 이벤트 구독에 쓰고 다 읽으면 본문을 닫는다
		public HttpResponse<Stream<String>> lines(String path, Map<String, String> headers) throws Exception {
			var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1" + path))
				.timeout(Duration.ofSeconds(15));
			headers.forEach(builder::header);
			return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofLines());
		}

		public JsonNode bootstrap() throws Exception {
			JsonNode session = request("GET", "/auth/bootstrap", null, 200);
			csrf = session.get("csrfToken").asString();
			return session;
		}

		// 이메일 코드로 가입하고 약관에 동의한 계정을 만든다
		public Browser signup(String name) throws Exception {
			bootstrap();
			String email = UUID.randomUUID() + "@example.test";
			JsonNode challenge = request("POST", "/auth/challenges", Map.of("email", email, "purpose", "login"), 202);
			request("POST", "/auth/sessions",
					Map.of("challengeId", challenge.get("challengeId").asString(), "code", codes.get(email)), 201);
			bootstrap();
			JsonNode user = request("GET", "/me", null, 200);
			userId = request("PATCH", "/me",
					Map.of("name", name, "version", user.get("version").asLong(), "consentVersions",
							Map.of("terms", "http-test-v1", "privacy", "http-test-v1")),
					200)
				.get("id")
				.asString();
			return this;
		}

	}

	protected Browser signup(String name) throws Exception {
		return new Browser().signup(name);
	}

	protected String createProject(Browser owner, String name) throws Exception {
		return owner.post("/projects", Map.of("name", name), 201).get("id").asString();
	}

	// 소유자가 초대 링크를 만들고 팀원이 수락한다
	protected void join(Browser owner, Browser member, String projectId) throws Exception {
		String url = owner.request("POST", "/projects/" + projectId + "/invitations", Map.of("rotate", false), 200)
			.get("url")
			.asString();
		member.request("POST", "/invitations/" + url.substring(url.lastIndexOf('/') + 1) + "/accept", null, 200);
	}

	protected void removeMember(Browser owner, String projectId, String userId) throws Exception {
		long version = 0;
		for (JsonNode member : owner.get("/projects/" + projectId + "/members").get("items")) {
			if (member.get("userId").asString().equals(userId)) {
				version = member.get("membershipVersion").asLong();
			}
		}
		owner.request("DELETE", "/projects/" + projectId + "/members/" + userId,
				Map.of("expectedMembershipVersion", version, "clientMutationId", UUID.randomUUID().toString()), 200,
				UUID.randomUUID().toString());
	}

}
