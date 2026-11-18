package com.teamplespace.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
	"teamplespace.auth.secret=integration-test-key-not-for-production-2026",
	"teamplespace.auth.frontend-origin=http://localhost:5174",
	"teamplespace.auth.backend-origin=http://localhost:8081",
	"teamplespace.auth.secure-cookie=false",
	"teamplespace.auth.mail-from=noreply@example.test",
	"spring.mail.host=127.0.0.1",
	"teamplespace.auth.terms-version=test-v1",
	"teamplespace.auth.terms-url=http://localhost:5174/legal/terms",
	"teamplespace.auth.privacy-version=test-v1",
	"teamplespace.auth.privacy-url=http://localhost:5174/legal/privacy",
	"teamplespace.auth.google.client-id=test-client",
	"teamplespace.auth.google.client-secret=test-secret"
})
@AutoConfigureMockMvc
class AuthIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired AuthSecrets secrets;
	@LocalServerPort int port;
	@MockitoBean ChallengeMail mail;
	@MockitoBean OAuthGateway oauth;
	final ObjectMapper json=new ObjectMapper();
	final Map<String,String> codes=new ConcurrentHashMap<>();

	@BeforeEach void setupMail() {
		doAnswer(invocation->{codes.put(invocation.getArgument(0),invocation.getArgument(1));return null;}).when(mail).send(anyString(),anyString());
	}

	class Browser {
		Cookie cookie;
		String csrf;
		String email=UUID.randomUUID()+"@example.test";
		Browser() throws Exception { bootstrap(); }
		void bootstrap() throws Exception {
			var req=get("/v1/auth/bootstrap");if(cookie!=null)req.cookie(cookie);
			var response=mvc.perform(req).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(200);
			if(response.getCookie(AuthService.COOKIE)!=null)cookie=response.getCookie(AuthService.COOKIE);
			csrf=body(response).get("csrfToken").asString();
		}
		MockHttpServletResponse send(MockHttpServletRequestBuilder request,Object value) throws Exception {
			request.cookie(cookie).header("Origin","http://localhost:5174").header("X-CSRF-Token",csrf);
			if(value!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value));
			return mvc.perform(request).andReturn().getResponse();
		}
		String challenge(String purpose) throws Exception {
			var response=send(post("/v1/auth/challenges"),Map.of("email",email,"purpose",purpose));
			assertThat(response.getStatus()).isEqualTo(202);return body(response).get("challengeId").asString();
		}
		JsonNode login() throws Exception {
			String challenge=challenge("login");
			var response=send(post("/v1/auth/sessions"),Map.of("challengeId",challenge,"code",codes.get(email)));
			assertThat(response.getStatus()).isEqualTo(201);cookie=response.getCookie(AuthService.COOKIE);bootstrap();return body(response).get("user");
		}
	}

	@Test void realHttpBootstrapUsesCookieAndNoStore() throws Exception {
		var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/auth/bootstrap")).GET().build(),HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly","SameSite=Lax");
		assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
		assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("anonymous");
	}

	@Test void loginRotatesSessionAndRequiresExplicitConsent() throws Exception {
		var browser=new Browser();Cookie old=browser.cookie;JsonNode user=browser.login();
		assertThat(browser.cookie.getValue()).isNotEqualTo(old.getValue());
		assertThat(mvc.perform(get("/v1/me").cookie(old)).andReturn().getResponse().getStatus()).isEqualTo(401);
		var missing=browser.send(patch("/v1/me"),Map.of("name","정혁","version",1));
		assertCode(missing,422,"REQUIRED_CONSENT");
		var accepted=browser.send(patch("/v1/me"),Map.of("name","정혁","version",1,"consentVersions",Map.of("terms","test-v1","privacy","test-v1")));
		assertThat(accepted.getStatus()).isEqualTo(200);assertThat(body(accepted).get("onboardingStatus").asString()).isEqualTo("completed");
		assertThat(body(accepted).get("id").asString()).isEqualTo(user.get("id").asString());
		assertCode(browser.send(patch("/v1/me"),Map.of("name","다른 이름","version",1)),409,"VERSION_CONFLICT");
	}

	@Test void csrfAndOriginAreCheckedBeforeSendingEmail() throws Exception {
		var browser=new Browser();
		var request=post("/v1/auth/challenges").cookie(browser.cookie).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("email",browser.email,"purpose","login")));
		assertCode(mvc.perform(request).andReturn().getResponse(),403,"CSRF_INVALID");
		request.header("X-CSRF-Token",browser.csrf).header("Origin","https://attacker.example");
		assertCode(mvc.perform(request).andReturn().getResponse(),403,"CSRF_INVALID");
		assertThat(codes).doesNotContainKey(browser.email);
	}

	@Test void wrongAttemptsPersistAndBlockTheCorrectCode() throws Exception {
		var browser=new Browser();String id=browser.challenge("login");String correct=codes.get(browser.email);
		for(int i=0;i<5;i++)assertCode(browser.send(post("/v1/auth/sessions"),Map.of("challengeId",id,"code","wrong")),422,"INVALID_CODE");
		assertCode(browser.send(post("/v1/auth/sessions"),Map.of("challengeId",id,"code",correct)),422,"INVALID_CODE");
		assertThat(jdbc.sql("SELECT attempts FROM auth_challenges WHERE id=UUID_TO_BIN(?)").param(id).query(Integer.class).single()).isEqualTo(5);
	}

	@Test void expiredOrOtherBrowserChallengeCannotAuthenticate() throws Exception {
		var browser=new Browser();String id=browser.challenge("login");var other=new Browser();
		assertCode(other.send(post("/v1/auth/sessions"),Map.of("challengeId",id,"code",codes.get(browser.email))),422,"INVALID_CODE");
		jdbc.sql("UPDATE auth_challenges SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=UUID_TO_BIN(?)").param(id).update();
		assertCode(browser.send(post("/v1/auth/sessions"),Map.of("challengeId",id,"code",codes.get(browser.email))),410,"CHALLENGE_EXPIRED");
	}

	@Test void challengeCooldownIsEnforced() throws Exception {
		var browser=new Browser();browser.challenge("login");
		assertCode(browser.send(post("/v1/auth/challenges"),Map.of("email",browser.email,"purpose","login")),429,"RATE_LIMITED");
	}

	@Test void logoutRevokesAuthentication() throws Exception {
		var browser=new Browser();browser.login();Cookie active=browser.cookie;
		assertThat(browser.send(delete("/v1/auth/session"),null).getStatus()).isEqualTo(204);
		assertThat(mvc.perform(get("/v1/me").cookie(active)).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test void reauthenticationIsBoundToUserSessionAndDeletionRevokesAccess() throws Exception {
		var browser=new Browser();JsonNode user=browser.login();
		jdbc.sql("UPDATE auth_challenges SET created_at=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND WHERE email_normalized=?").param(browser.email).update();
		String challenge=browser.challenge("reauth");
		var verified=browser.send(post("/v1/auth/reauthentications"),Map.of("challengeId",challenge,"code",codes.get(browser.email)));
		assertThat(verified.getStatus()).isEqualTo(201);String proof=body(verified).get("reauthProof").asString();
		var other=new Browser();other.login();
		assertCode(other.send(delete("/v1/me"),Map.of("reauthProof",proof,"confirmationText","탈퇴")),401,"REAUTH_REQUIRED");
		assertThat(browser.send(delete("/v1/me"),Map.of("reauthProof",proof,"confirmationText","탈퇴")).getStatus()).isEqualTo(202);
		assertThat(browser.send(get("/v1/me"),null).getStatus()).isEqualTo(401);
		assertThat(jdbc.sql("SELECT status FROM users WHERE id=UUID_TO_BIN(?)").param(user.get("id").asString()).query(String.class).single()).isEqualTo("deleted");
	}

	@Test void unsafeOAuthReturnToIsRejected() throws Exception {
		var browser=new Browser();
		for(String target:new String[]{"https://evil.example","//evil.example","/\\evil","/%2f%2fevil","/account?next=https://evil.example"}) {
			assertCode(browser.send(get("/v1/auth/oauth/google/authorize").param("returnTo",target),null),422,"INVALID_RETURN_TO");
		}
	}

	@Test void oauthStateIsBoundToBrowserAndCannotBeReused() throws Exception {
		var browser=new Browser();var other=new Browser();String[] state={null};
		when(oauth.authorizationUrl(anyString(),anyString(),anyString(),anyString())).thenAnswer(invocation->{state[0]=invocation.getArgument(1);return "https://accounts.google.com/test";});
		when(oauth.exchange(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new OAuthGateway.Identity(UUID.randomUUID().toString(),browser.email));
		assertThat(browser.send(get("/v1/auth/oauth/google/authorize"),null).getStatus()).isEqualTo(302);
		var mismatch=other.send(get("/v1/auth/oauth/google/callback").param("state",state[0]).param("code","test"),null);
		assertThat(mismatch.getHeader("Location")).contains("OAUTH_STATE_MISMATCH");
		var success=browser.send(get("/v1/auth/oauth/google/callback").param("state",state[0]).param("code","test"),null);
		assertThat(success.getHeader("Location")).contains("/onboarding");
		var repeated=browser.send(get("/v1/auth/oauth/google/callback").param("state",state[0]).param("code","test"),null);
		assertThat(repeated.getHeader("Location")).contains("OAUTH_STATE_MISMATCH");
	}

	@Test void differentProviderWithSameEmailDoesNotMergeAccounts() throws Exception {
		var browser=new Browser();JsonNode user=browser.login();String[] state={null};
		when(oauth.authorizationUrl(anyString(),anyString(),anyString(),anyString())).thenAnswer(invocation->{state[0]=invocation.getArgument(1);return "https://accounts.google.com/test";});
		when(oauth.exchange(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new OAuthGateway.Identity(UUID.randomUUID().toString(),browser.email));
		browser.send(get("/v1/auth/oauth/google/authorize"),null);
		var response=browser.send(get("/v1/auth/oauth/google/callback").param("state",state[0]).param("code","test"),null);
		assertThat(response.getHeader("Location")).contains("IDENTITY_CONFLICT");
		assertThat(body(browser.send(get("/v1/me"),null)).get("id").asString()).isEqualTo(user.get("id").asString());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE primary_email=?").param(browser.email).query(Integer.class).single()).isEqualTo(1);
	}

	JsonNode body(MockHttpServletResponse response) throws Exception { return json.readTree(response.getContentAsString()); }
	void assertCode(MockHttpServletResponse response,int status,String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(body(response).get("error").get("code").asString()).isEqualTo(code);
	}
}
