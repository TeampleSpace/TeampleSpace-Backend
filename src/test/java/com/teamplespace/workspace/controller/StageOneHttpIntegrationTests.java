package com.teamplespace.workspace.controller;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.teamplespace.identity.service.ChallengeMail;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    "teamplespace.auth.privacy-url=http://localhost:5174/legal/privacy"
})
class StageOneHttpIntegrationTests {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @MockitoBean ChallengeMail mail;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @BeforeEach
    void captureTestMail() {
        doAnswer(call -> {
            codes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(mail).send(anyString(), anyString());
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
        private String csrf;
        private String email;

        JsonNode request(String method, String path, Object body, int expectedStatus, String key) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1" + path))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                    .header("Origin", "http://localhost:5174");
            if (csrf != null) builder.header("X-CSRF-Token", csrf);
            if (key != null) builder.header("Idempotency-Key", key);
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(expectedStatus);
            String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
            if (response.body().isBlank()) return null;
            JsonNode result = json.readTree(response.body());
            if (expectedStatus >= 400) assertThat(result.get("requestId").asString()).isEqualTo(requestId);
            return result;
        }

        JsonNode request(String method, String path, Object body, int status) throws Exception {
            return request(method, path, body, status, null);
        }

        JsonNode bootstrap() throws Exception {
            JsonNode session = request("GET", "/auth/bootstrap", null, 200);
            csrf = session.get("csrfToken").asString();
            return session;
        }

        JsonNode signup(String name) throws Exception {
            bootstrap();
            email = UUID.randomUUID() + "@example.test";
            JsonNode challenge = request("POST", "/auth/challenges", Map.of("email", email, "purpose", "login"), 202);
            String oldCookie = cookies.getCookieStore().getCookies().getFirst().getValue();
            JsonNode login = request("POST", "/auth/sessions", Map.of("challengeId", challenge.get("challengeId").asString(), "code", codes.get(email)), 201);
            assertThat(login.get("needsOnboarding").asBoolean()).isTrue();
            assertThat(cookies.getCookieStore().getCookies().getFirst().getValue()).isNotEqualTo(oldCookie);
            assertThat(bootstrap().get("status").asString()).isEqualTo("authenticated");
            request("GET", "/projects", null, 403);
            JsonNode user = request("GET", "/me", null, 200);
            request("PATCH", "/me", Map.of("name", name, "version", user.get("version").asLong()), 422);
            return request("PATCH", "/me", Map.of("name", name, "version", user.get("version").asLong(),
                    "consentVersions", Map.of("terms", "http-test-v1", "privacy", "http-test-v1")), 200);
        }
    }

    private String invitationToken(JsonNode invitation) {
        String path = URI.create(invitation.get("url").asString()).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private long memberVersion(Browser browser, String id, String userId) throws Exception {
        for (JsonNode member : browser.request("GET", "/projects/" + id + "/members", null, 200).get("items")) {
            if (member.get("userId").asString().equals(userId)) return member.get("membershipVersion").asLong();
        }
        throw new AssertionError("팀원을 찾을 수 없습니다");
    }

    @Test
    void projectSearchPaginationVersionsAndDeletionAreEnforced() throws Exception {
        Browser owner = new Browser();
        owner.signup("프로젝트 소유자");
        String prefix = "검색 " + UUID.randomUUID();
        JsonNode first = owner.request("POST", "/projects", Map.of("name", prefix + " 하나"), 201, UUID.randomUUID().toString());
        owner.request("POST", "/projects", Map.of("name", prefix + " 둘"), 201, UUID.randomUUID().toString());
        JsonNode page = owner.request("GET", "/projects?limit=1", null, 200);
        assertThat(page.get("items").size()).isEqualTo(1);
        JsonNode next = owner.request("GET", "/projects?limit=1&cursor=" + page.get("nextCursor").asString(), null, 200);
        assertThat(next.get("items").size()).isEqualTo(1);
        assertThat(next.get("items").get(0).get("id").asString()).isNotEqualTo(page.get("items").get(0).get("id").asString());
        assertThat(next.get("nextCursor").isNull()).isTrue();
        owner.request("GET", "/projects?cursor=invalid", null, 422);
        owner.request("GET", "/projects?limit=101", null, 422);
        String id = first.get("id").asString();
        JsonNode changed = owner.request("PATCH", "/projects/" + id, Map.of("expectedVersion", 1, "topic", "여러 줄\n한글 발표", "presentationConstraints", "5분 발표"), 200);
        assertThat(changed.get("topic").asString()).isEqualTo("여러 줄\n한글 발표");
        owner.request("PATCH", "/projects/" + id, Map.of("expectedVersion", 1, "name", "오래된 수정"), 409);
        owner.request("DELETE", "/me", Map.of("reauthProof", "invalid", "confirmationText", "탈퇴"), 409);
        String token = invitationToken(owner.request("POST", "/projects/" + id + "/invitations", Map.of("rotate", false), 200));
        owner.request("DELETE", "/projects/" + id, Map.of("expectedVersion", 2, "confirmationName", "틀린 이름"), 422);
        owner.request("DELETE", "/projects/" + id, Map.of("expectedVersion", 2, "confirmationName", first.get("name").asString()), 204);
        owner.request("GET", "/projects/" + id, null, 404);
        owner.request("GET", "/invitations/" + token, null, 404);
        String query = java.net.URLEncoder.encode(first.get("name").asString(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(owner.request("GET", "/projects?q=" + query, null, 200).get("items").isEmpty()).isTrue();
    }

    @Test
    void removedMemberNeedsRotatedInviteAndFormerOwnerCanLeaveIdempotently() throws Exception {
        Browser owner = new Browser();
        Browser member = new Browser();
        String ownerId = owner.signup("첫 소유자").get("id").asString();
        String memberId = member.signup("새 소유자").get("id").asString();
        String id = owner.request("POST", "/projects", Map.of("name", "소유권 검증"), 201, UUID.randomUUID().toString()).get("id").asString();
        String token = invitationToken(owner.request("POST", "/projects/" + id + "/invitations", Map.of("rotate", false), 200));
        member.request("POST", "/invitations/" + token + "/accept", null, 200);
        member.request("POST", "/projects/" + id + "/ownership", Map.of("targetUserId", ownerId, "expectedVersion", 1), 403);
        String removeKey = UUID.randomUUID().toString();
        Map<String, Object> remove = Map.of("expectedMembershipVersion", memberVersion(owner, id, memberId), "clientMutationId", UUID.randomUUID().toString());
        JsonNode removed = owner.request("DELETE", "/projects/" + id + "/members/" + memberId, remove, 200, removeKey);
        JsonNode replay = owner.request("DELETE", "/projects/" + id + "/members/" + memberId, remove, 200, removeKey);
        assertThat(replay.get("eventId").asString()).isEqualTo(removed.get("eventId").asString());
        member.request("POST", "/invitations/" + token + "/accept", null, 404);
        // 내보낸 뒤 링크를 다시 복사하면 새 초대가 나와 바로 다시 참여할 수 있다
        String fresh = invitationToken(owner.request("POST", "/projects/" + id + "/invitations", Map.of("rotate", false), 200));
        assertThat(fresh).isNotEqualTo(token);
        owner.request("GET", "/invitations/" + token, null, 404);
        member.request("POST", "/invitations/" + fresh + "/accept", null, 200);
        JsonNode transferred = owner.request("POST", "/projects/" + id + "/ownership", Map.of("targetUserId", memberId, "expectedVersion", 1), 200);
        assertThat(transferred.get("ownerId").asString()).isEqualTo(memberId);
        owner.request("PATCH", "/projects/" + id, Map.of("expectedVersion", 2, "name", "이전 소유자 수정"), 403);
        Map<String, Object> leave = Map.of("expectedMembershipVersion", memberVersion(owner, id, ownerId), "clientMutationId", UUID.randomUUID().toString());
        String leaveKey = UUID.randomUUID().toString();
        owner.request("DELETE", "/projects/" + id + "/members/" + ownerId, leave, 200, leaveKey);
        owner.request("DELETE", "/projects/" + id + "/members/" + ownerId, leave, 200, leaveKey);
        owner.bootstrap();
        owner.request("GET", "/projects/" + id, null, 403);
        assertThat(member.request("GET", "/projects/" + id, null, 200).get("memberCount").asInt()).isEqualTo(1);
    }

    @Test
    void removingDeletedMemberReplaysSuccessWithoutRequiringTargetAccount() throws Exception {
        Browser owner = new Browser();
        Browser member = new Browser();
        Browser stranger = new Browser();
        owner.signup("소유자");
        String target = member.signup("탈퇴할 팀원").get("id").asString();
        stranger.signup("외부 사용자");
        String id = owner.request("POST", "/projects", Map.of("name", "제거 재시도"), 201, UUID.randomUUID().toString()).get("id").asString();
        String token = invitationToken(owner.request("POST", "/projects/" + id + "/invitations", Map.of("rotate", false), 200));
        member.request("POST", "/invitations/" + token + "/accept", null, 200);
        String key = UUID.randomUUID().toString();
        Map<String, Object> command = Map.of("expectedMembershipVersion", memberVersion(owner, id, target), "clientMutationId", UUID.randomUUID().toString());
        String route = "/projects/" + id + "/members/" + target;
        JsonNode removed = owner.request("DELETE", route, command, 200, key);
        jdbc.sql("UPDATE auth_challenges SET created_at=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND WHERE email_normalized=?").param(member.email).update();
        JsonNode challenge = member.request("POST", "/auth/challenges", Map.of("email", member.email, "purpose", "reauth"), 202);
        JsonNode proof = member.request("POST", "/auth/reauthentications", Map.of("challengeId", challenge.get("challengeId").asString(), "code", codes.get(member.email)), 201);
        member.request("DELETE", "/me", Map.of("reauthProof", proof.get("reauthProof").asString(), "confirmationText", "탈퇴"), 202);
        JsonNode replay = owner.request("DELETE", route, command, 200, key);
        assertThat(replay).isEqualTo(removed);
        stranger.request("DELETE", route, command, 404, key);
    }

    @Test
    void twoAccountsCompleteProjectInvitationAndAccessRevocationOverHttp() throws Exception {
        Browser owner = new Browser();
        Browser member = new Browser();
        Browser stranger = new Browser();
        JsonNode ownerUser = owner.signup("통합 검증 소유자");
        JsonNode memberUser = member.signup("통합 검증 팀원");
        stranger.signup("통합 검증 외부인");
        String key = UUID.randomUUID().toString();
        Map<String, Object> create = Map.of("name", "HTTP 검증 " + UUID.randomUUID(), "topic", "한글 저장 확인");
        JsonNode project = owner.request("POST", "/projects", create, 201, key);
        JsonNode retry = owner.request("POST", "/projects", create, 201, key);
        String id = project.get("id").asString();
        assertThat(retry.get("id").asString()).isEqualTo(id);
        stranger.request("GET", "/projects/" + id, null, 404);
        JsonNode invitation = owner.request("POST", "/projects/" + id + "/invitations", Map.of("rotate", false), 200);
        String path = URI.create(invitation.get("url").asString()).getPath();
        String token = path.substring(path.lastIndexOf('/') + 1);
        JsonNode preview = member.request("GET", "/invitations/" + token, null, 200);
        assertThat(preview.get("projectName").asString()).isEqualTo(project.get("name").asString());
        JsonNode join = member.request("POST", "/invitations/" + token + "/accept", Map.of(), 200);
        assertThat(join.get("projectId").asString()).isEqualTo(id);
        assertThat(member.request("POST", "/invitations/" + token + "/accept", Map.of(), 200).get("alreadyMember").asBoolean()).isTrue();
        member.request("PATCH", "/projects/" + id, Map.of("expectedVersion", project.get("version").asLong(), "name", "무단 수정"), 403);
        member.request("DELETE", "/projects/" + id, Map.of("expectedVersion", project.get("version").asLong(), "confirmationName", project.get("name").asString()), 403);
        JsonNode members = owner.request("GET", "/projects/" + id + "/members", null, 200).get("items");
        long ownerVersion = 0;
        long memberVersion = 0;
        for (JsonNode row : members) {
            if (row.get("userId").asString().equals(ownerUser.get("id").asString())) ownerVersion = row.get("membershipVersion").asLong();
            if (row.get("userId").asString().equals(memberUser.get("id").asString())) memberVersion = row.get("membershipVersion").asLong();
        }
        assertThat(ownerVersion).isPositive();
        assertThat(memberVersion).isPositive();
        owner.request("DELETE", "/projects/" + id + "/members/" + ownerUser.get("id").asString(),
                Map.of("expectedMembershipVersion", ownerVersion, "clientMutationId", UUID.randomUUID().toString()), 409, UUID.randomUUID().toString());
        owner.request("DELETE", "/projects/" + id + "/members/" + memberUser.get("id").asString(),
                Map.of("expectedMembershipVersion", memberVersion, "clientMutationId", UUID.randomUUID().toString()), 200, UUID.randomUUID().toString());
        member.request("GET", "/projects/" + id, null, 403);
        member.bootstrap();
        member.request("GET", "/projects/" + id, null, 403);
        assertThat(member.request("GET", "/projects", null, 200).get("items").isEmpty()).isTrue();
        owner.request("DELETE", "/auth/session", null, 204);
        assertThat(owner.bootstrap().get("status").asString()).isEqualTo("anonymous");
        owner.request("GET", "/me", null, 401);
    }
}
