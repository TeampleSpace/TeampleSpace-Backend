package com.teamplespace.identity.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.identity.config.AuthSettings;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class AuthService {

	public static final String COOKIE = "teamplespace_session";
	public record Session(UUID id, UUID userId, String token, Instant expiresAt) { }
	private final JdbcClient jdbc;
	private final AuthSettings settings;
	private final AuthSecrets secrets;
	private final ChallengeMail mail;
	private final TransactionTemplate transaction;

	public AuthService(JdbcClient jdbc, AuthSettings settings, AuthSecrets secrets, ChallengeMail mail,
			TransactionTemplate transaction) {
		this.jdbc = jdbc; this.settings = settings; this.secrets = secrets; this.mail = mail; this.transaction = transaction;
	}

	public Session session(HttpServletRequest request) {
		String token = cookie(request);
		if (token == null || token.length() > 128) return null;
		return jdbc.sql("""
				SELECT BIN_TO_UUID(s.id) id, BIN_TO_UUID(s.user_id) user_id, s.expires_at
				FROM auth_sessions s LEFT JOIN users u ON u.id = s.user_id
				WHERE s.token_hash = ? AND s.state IN ('anonymous','active') AND s.expires_at > UTC_TIMESTAMP(6)
				AND (s.user_id IS NULL OR u.status = 'active')
				""").param(secrets.hash(token)).query((rs, row) -> new Session(UUID.fromString(rs.getString("id")),
					rs.getString("user_id") == null ? null : UUID.fromString(rs.getString("user_id")), token,
					rs.getTimestamp("expires_at").toInstant())).optional().orElse(null);
	}

	public Session requireUser(HttpServletRequest request) {
		Session session = session(request);
		if (session == null || session.userId() == null) throw new ApiException(ErrorCode.UNAUTHENTICATED);
		return session;
	}

	public Session requireOnboarded(HttpServletRequest request) {
		Session session = requireUser(request);
		if (!user(session.userId()).get("onboardingStatus").equals("completed")) throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
		return session;
	}

	public void checkWrite(HttpServletRequest request) {
		if (!settings.configured()) throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, 60);
		String origin = request.getHeader("Origin");
		if (!settings.origin().equals(origin)) throw new ApiException(ErrorCode.CSRF_INVALID);
		Session session = session(request);
		String token = request.getHeader("X-CSRF-Token");
		if (session == null && request.getMethod().equals("DELETE") && request.getRequestURI().equals("/v1/auth/session")) return;
		if (session == null || token == null || !secrets.matches(secrets.hash(secrets.csrf(session.token())), secrets.hash(token))) {
			throw new ApiException(ErrorCode.CSRF_INVALID);
		}
	}

	@Transactional
	public Map<String, Object> bootstrap(HttpServletRequest request, HttpServletResponse response) {
		if (!settings.configured()) throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, 60);
		Session session = session(request);
		if (session == null) session = createSession(null, response);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", session.userId() == null ? "anonymous" : "authenticated");
		result.put("csrfToken", secrets.csrf(session.token()));
		result.put("expiresAt", session.expiresAt());
		if (session.userId() != null) {
			Map<String, Object> user = user(session.userId());
			result.put("user", Map.of("id", user.get("id"), "displayName", user.get("name"),
					"onboardingStatus", user.get("onboardingStatus"), "version", user.get("version")));
		}
		return result;
	}

	public Session createSession(UUID userId, HttpServletResponse response) {
		UUID id = UUID.randomUUID();
		String token = secrets.token();
		Instant expires = Instant.now().plusSeconds(userId == null ? 3600 : 604800);
		jdbc.sql("INSERT INTO auth_sessions (id,user_id,token_hash,csrf_hash,state,expires_at) VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?)")
			.params(id.toString(), userId == null ? null : userId.toString(), secrets.hash(token), secrets.hash(secrets.csrf(token)),
					userId == null ? "anonymous" : "active", Timestamp.from(expires)).update();
		response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, token).httpOnly(true).secure(settings.secureCookie())
				.sameSite("Lax").path("/").maxAge(userId == null ? 3600 : 604800).build().toString());
		return new Session(id, userId, token, expires);
	}

	@Transactional
	public Map<String, Object> challenge(HttpServletRequest request, String email, String purpose) {
		if (!settings.mailEnabled()) throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, 60);
		String normalized = normalizeEmail(email);
		if (!List.of("login", "reauth").contains(purpose)) throw new ApiException(ErrorCode.VALIDATION_FAILED);
		Session session = session(request);
		if (session == null) throw new ApiException(ErrorCode.CSRF_INVALID);
		if (purpose.equals("reauth")) {
			session = requireUser(request);
			if (jdbc.sql("SELECT COUNT(*) FROM auth_identities WHERE user_id=UUID_TO_BIN(?) AND email_verified=TRUE AND email=?")
					.params(session.userId().toString(), normalized).query(Integer.class).single() == 0) throw new ApiException(ErrorCode.REAUTH_REQUIRED);
		}
		rateLimit("email:" + normalized, 5, 3600);
		rateLimit("browser:" + session.id(), 10, 3600);
		if (jdbc.sql("SELECT COUNT(*) FROM auth_challenges WHERE email_normalized=? AND created_at > UTC_TIMESTAMP(6)-INTERVAL 60 SECOND")
				.param(normalized).query(Integer.class).single() > 0) throw new ApiException(ErrorCode.RATE_LIMITED,60);
		UUID id = UUID.randomUUID();
		String code = secrets.code();
		Instant expires = Instant.now().plusSeconds(300);
		jdbc.sql("INSERT INTO auth_challenges (id,email_normalized,purpose,session_id,user_id,code_hash,state,expires_at)"
				+ " VALUES (UUID_TO_BIN(?),?,?,UUID_TO_BIN(?),UUID_TO_BIN(?),?,'pending',?)")
			.params(id.toString(), normalized, purpose, session.id().toString(), session.userId() == null ? null : session.userId().toString(),
					secrets.hmac(id + ":" + code), Timestamp.from(expires)).update();
		mail.send(normalized, code);
		return Map.of("challengeId", id, "expiresAt", expires, "retryAfter", 60);
	}

	public void rateLimit(String bucket, int max, int seconds) {
		byte[] hash = secrets.hash(bucket);
		jdbc.sql("INSERT INTO auth_rate_limits VALUES (?,UTC_TIMESTAMP(6),0) ON DUPLICATE KEY UPDATE bucket_hash=bucket_hash").param(hash).update();
		Map<String,Object> state = jdbc.sql("SELECT window_started_at, requests FROM auth_rate_limits WHERE bucket_hash=? FOR UPDATE").param(hash).query().singleRow();
		Instant start = instant(state.get("window_started_at"));
		if (start.plusSeconds(seconds).isBefore(Instant.now())) {
			jdbc.sql("UPDATE auth_rate_limits SET requests=1,window_started_at=UTC_TIMESTAMP(6) WHERE bucket_hash=?").param(hash).update();
		} else {
			if (((Number) state.get("requests")).intValue() >= max) throw new ApiException(ErrorCode.RATE_LIMITED, seconds);
			jdbc.sql("UPDATE auth_rate_limits SET requests=requests+1 WHERE bucket_hash=?").param(hash).update();
		}
	}

	private Map<String,Object> checkChallenge(Session session, UUID challengeId, String code, String purpose) {
		var row = jdbc.sql("SELECT email_normalized,purpose,BIN_TO_UUID(session_id) session_id,code_hash,state,attempts,expires_at"
				+ " FROM auth_challenges WHERE id=UUID_TO_BIN(?) FOR UPDATE").param(challengeId.toString()).query().listOfRows().stream().findFirst();
		if (row.isEmpty()) return Map.of("error", ErrorCode.INVALID_CODE);
		Map<String, Object> item = row.get();
		if (!item.get("session_id").equals(session.id().toString()) || !item.get("purpose").equals(purpose)) return Map.of("error", ErrorCode.INVALID_CODE);
		if (instant(item.get("expires_at")).isBefore(Instant.now())) return Map.of("error", ErrorCode.CHALLENGE_EXPIRED);
		if (!item.get("state").equals("pending")) return Map.of("error", ErrorCode.INVALID_CODE);
		int attempts = ((Number) item.get("attempts")).intValue() + 1;
		boolean matches = code != null && code.matches("[0-9]{6}") && secrets.matches((byte[]) item.get("code_hash"), secrets.hmac(challengeId + ":" + code));
		jdbc.sql("UPDATE auth_challenges SET attempts=?,state=? WHERE id=UUID_TO_BIN(?)")
			.params(attempts, matches ? "consumed" : attempts >= 5 ? "blocked" : "pending", challengeId.toString()).update();
		return matches ? Map.of("email", item.get("email_normalized")) : Map.of("error", ErrorCode.INVALID_CODE);
	}

	public String consumeChallenge(Session session, UUID challengeId, String code, String purpose) {
		Map<String,Object> outcome=transaction.execute(status->checkChallenge(session,challengeId,code,purpose));
		if(outcome.containsKey("error")) throw new ApiException((ErrorCode)outcome.get("error"));
		return (String)outcome.get("email");
	}

	public Map<String,Object> authenticate(Session session,UUID challengeId,String code,HttpServletResponse response) {
		Map<String,Object> result=transaction.execute(status->{
			Map<String,Object> checked=checkChallenge(session,challengeId,code,"login");
			if(checked.containsKey("error")) return checked;
			String email=(String)checked.get("email");
			return login(session,"email",email,email,response);
		});
		if(result.containsKey("error")) throw new ApiException((ErrorCode)result.get("error"));
		return result;
	}

	@Transactional
	public Map<String, Object> login(Session oldSession, String provider, String subject, String email, HttpServletResponse response) {
		int rotated = jdbc.sql("UPDATE auth_sessions SET state='revoked' WHERE id=UUID_TO_BIN(?) AND state IN ('anonymous','active') AND expires_at>UTC_TIMESTAMP(6)")
			.param(oldSession.id().toString()).update();
		if (rotated != 1) throw new ApiException(ErrorCode.UNAUTHENTICATED);
		UUID userId = identityUser(provider, subject);
		if (userId == null) {
			if (email != null && jdbc.sql("SELECT COUNT(*) FROM users WHERE primary_email=?").param(email).query(Integer.class).single() > 0) throw new ApiException(ErrorCode.IDENTITY_CONFLICT);
			userId = UUID.randomUUID();
			try {
				jdbc.sql("INSERT INTO users (id,primary_email) VALUES (UUID_TO_BIN(?),?)").params(userId.toString(), email).update();
				jdbc.sql("INSERT INTO auth_identities (id,user_id,provider,subject,email,email_verified) VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?)")
					.params(UUID.randomUUID().toString(), userId.toString(), provider, subject, email, email != null && !provider.equals("naver")).update();
			} catch (DuplicateKeyException ex) { throw new ApiException(ErrorCode.IDENTITY_CONFLICT); }
		}
		Map<String, Object> user = user(userId);
		createSession(userId, response);
		return Map.of("user", user, "needsOnboarding", !user.get("onboardingStatus").equals("completed"));
	}

	public UUID identityUser(String provider, String subject) {
		return jdbc.sql("SELECT BIN_TO_UUID(user_id) FROM auth_identities WHERE provider=? AND subject=?")
			.params(provider, subject).query(String.class).optional().map(UUID::fromString).orElse(null);
	}

	public Map<String, Object> user(UUID id) {
		Map<String,Object> row = jdbc.sql("SELECT BIN_TO_UUID(id) id,display_name name,primary_email email,onboarding_status,version FROM users WHERE id=UUID_TO_BIN(?) AND status='active'")
			.param(id.toString()).query().listOfRows().stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		Map<String,Object> result = new LinkedHashMap<>();
		result.put("id", row.get("id")); result.put("name", row.get("name")); result.put("email", row.get("email"));
		result.put("onboardingStatus", row.get("onboarding_status")); result.put("version", row.get("version"));
		Map<String,String> consents = new LinkedHashMap<>();
		jdbc.sql("SELECT term_key,term_version FROM user_consents WHERE user_id=UUID_TO_BIN(?) ORDER BY accepted_at")
			.param(id.toString()).query((rs, i) -> { consents.put(rs.getString(1), rs.getString(2)); return 0; }).list();
		result.put("consentVersions", consents);
		result.put("canEmailReauth", settings.mailEnabled() && jdbc.sql("SELECT COUNT(*) FROM auth_identities WHERE user_id=UUID_TO_BIN(?) AND email_verified=TRUE AND email IS NOT NULL").param(id.toString()).query(Integer.class).single()>0);
		result.put("ownedProjectCount", jdbc.sql("SELECT COUNT(*) FROM projects WHERE owner_user_id=UUID_TO_BIN(?) AND status='active'").param(id.toString()).query(Integer.class).single());
		result.put("identities", jdbc.sql("SELECT provider,email,linked_at FROM auth_identities WHERE user_id=UUID_TO_BIN(?) ORDER BY provider")
			.param(id.toString()).query((rs,i) -> Map.of("provider", rs.getString("provider"), "maskedEmail", mask(rs.getString("email")),
					"linkedAt", rs.getTimestamp("linked_at").toInstant())).list());
		return result;
	}

	@Transactional
	public Map<String,Object> updateUser(Session session, String name, Long version, Map<String,String> consents, String requestId) {
		if (name == null || name.isBlank() || name.length() > 100 || version == null || version < 1) throw new ApiException(ErrorCode.VALIDATION_FAILED);
		jdbc.sql("SELECT id FROM users WHERE id=UUID_TO_BIN(?) FOR UPDATE").param(session.userId().toString()).query().singleRow();
		Map<String,Object> current = user(session.userId());
		if (((Number)current.get("version")).longValue() != version) throw new ApiException(ErrorCode.VERSION_CONFLICT);
		if (!current.get("onboardingStatus").equals("completed") || consents != null) {
			if (settings.legalDocuments().isEmpty()) throw new ApiException(ErrorCode.LEGAL_DOCUMENTS_UNAVAILABLE);
			for (Map<String,String> document : settings.legalDocuments()) {
				if (consents == null || !document.get("version").equals(consents.get(document.get("type")))) throw new ApiException(ErrorCode.REQUIRED_CONSENT);
			}
			for (Map<String,String> document : settings.legalDocuments()) {
				jdbc.sql("INSERT INTO user_consents(id,user_id,term_key,term_version,request_id) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?) ON DUPLICATE KEY UPDATE id=id")
					.params(UUID.randomUUID().toString(),session.userId().toString(),document.get("type"),document.get("version"),requestId).update();
			}
		}
		jdbc.sql("UPDATE users SET display_name=?,onboarding_status='completed',version=version+1 WHERE id=UUID_TO_BIN(?)")
			.params(name.strip(),session.userId().toString()).update();
		return user(session.userId());
	}

	@Transactional
	public Map<String,Object> proof(Session session) {
		String proof = secrets.token(); Instant expires = Instant.now().plusSeconds(300);
		jdbc.sql("INSERT INTO auth_transactions(id,session_id,user_id,type,secret_hash,payload,expires_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'reauth',?,JSON_OBJECT(),?)")
			.params(UUID.randomUUID().toString(),session.id().toString(),session.userId().toString(),secrets.hash(proof),Timestamp.from(expires)).update();
		return Map.of("reauthProof",proof,"expiresAt",expires);
	}

	@Transactional
	public Map<String,Object> deleteUser(Session session, String proof, String confirmation, HttpServletResponse response) {
		if (!"탈퇴".equals(confirmation)) throw new ApiException(ErrorCode.VALIDATION_FAILED);
		jdbc.sql("SELECT id FROM users WHERE id=UUID_TO_BIN(?) FOR UPDATE").param(session.userId().toString()).query().singleRow();
		if (((Number)user(session.userId()).get("ownedProjectCount")).intValue() > 0) throw new ApiException(ErrorCode.OWNED_PROJECTS_EXIST);
		if (proof == null || jdbc.sql("UPDATE auth_transactions SET consumed=TRUE WHERE secret_hash=? AND user_id=UUID_TO_BIN(?) AND session_id=UUID_TO_BIN(?) AND type='reauth' AND consumed=FALSE AND expires_at>UTC_TIMESTAMP(6)")
			.params(secrets.hash(proof),session.userId().toString(),session.id().toString()).update() != 1) throw new ApiException(ErrorCode.REAUTH_REQUIRED);
		jdbc.sql("UPDATE project_memberships SET state='left',version=version+1,left_at=UTC_TIMESTAMP(6) WHERE user_id=UUID_TO_BIN(?) AND state='active'").param(session.userId().toString()).update();
		jdbc.sql("DELETE FROM auth_identities WHERE user_id=UUID_TO_BIN(?)").param(session.userId().toString()).update();
		jdbc.sql("UPDATE users SET status='deleted',display_name='탈퇴한 사용자',primary_email=NULL,deleted_at=UTC_TIMESTAMP(6),version=version+1 WHERE id=UUID_TO_BIN(?)").param(session.userId().toString()).update();
		jdbc.sql("UPDATE auth_sessions SET state='revoked' WHERE user_id=UUID_TO_BIN(?)").param(session.userId().toString()).update();
		UUID id=UUID.randomUUID();
		jdbc.sql("INSERT INTO account_deletion_requests(id,user_id,state) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'completed')").params(id.toString(),session.userId().toString()).update();
		clearCookie(response);
		return Map.of("deletionRequestId",id,"status","completed");
	}

	public void revoke(UUID sessionId) { jdbc.sql("UPDATE auth_sessions SET state='revoked' WHERE id=UUID_TO_BIN(?)").param(sessionId.toString()).update(); }
	public void clearCookie(HttpServletResponse response) {
		response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,"").httpOnly(true).secure(settings.secureCookie()).sameSite("Lax").path("/").maxAge(0).build().toString());
	}
	public String normalizeEmail(String email) {
		if(email==null || email.length()>254 || !email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new ApiException(ErrorCode.INVALID_EMAIL);
		return email.strip().toLowerCase(Locale.ROOT);
	}
	public static Instant instant(Object value) {
		return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((LocalDateTime)value).toInstant(ZoneOffset.UTC);
	}
	private String mask(String email) { if(email==null) return ""; int at=email.indexOf('@'); return at<1 ? "" : email.substring(0,1)+"***"+email.substring(at); }
	private String cookie(HttpServletRequest request) {
		if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookie.getName().equals(COOKIE)) return cookie.getValue();
		return null;
	}
}
