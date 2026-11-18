package com.teamplespace.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Service
public class OAuthService {

	private final JdbcClient jdbc;
	private final AuthService auth;
	private final AuthSettings settings;
	private final AuthSecrets secrets;
	private final OAuthGateway gateway;
	private final TransactionTemplate transaction;
	private final ObjectMapper json=new ObjectMapper();

	public OAuthService(JdbcClient jdbc,AuthService auth,AuthSettings settings,AuthSecrets secrets,OAuthGateway gateway,TransactionTemplate transaction) {
		this.jdbc=jdbc;this.auth=auth;this.settings=settings;this.secrets=secrets;this.gateway=gateway;this.transaction=transaction;
	}

	@Transactional
	public String authorize(String provider,String purpose,String returnTo,HttpServletRequest request,HttpServletResponse response) {
		if(!settings.oauthEnabled(provider)) throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,60);
		if(!"login".equals(purpose) && !"reauth".equals(purpose)) throw new ApiException(ErrorCode.VALIDATION_FAILED);
		String target=safeReturnTo(returnTo);
		AuthService.Session session=auth.session(request);
		if(purpose.equals("reauth")) session=auth.requireUser(request);
		if(session==null) session=auth.createSession(null,response);
		auth.rateLimit("oauth:"+session.id(),20,3600);
		String state=secrets.token(),nonce=secrets.token(),verifier=secrets.token();
		String payload=json.writeValueAsString(Map.of("purpose",purpose,"returnTo",target,"nonce",nonce,"verifier",secrets.encrypt(verifier)));
		jdbc.sql("INSERT INTO auth_transactions(id,session_id,user_id,type,provider,secret_hash,payload,expires_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'oauth',?,?,?,?)")
			.params(UUID.randomUUID().toString(),session.id().toString(),session.userId()==null ? null : session.userId().toString(),provider,secrets.hash(state),payload,Timestamp.from(Instant.now().plusSeconds(300))).update();
		return gateway.authorizationUrl(provider,state,nonce,verifier);
	}

	public String callback(String provider,String state,String code,String error,HttpServletRequest request,HttpServletResponse response) {
		AuthService.Session session=auth.session(request);
		if(session==null || state==null || state.length()>128) throw new ApiException(ErrorCode.OAUTH_STATE_MISMATCH);
		Map<String,Object> saved=transaction.execute(tx -> {
			Map<String,Object> row=jdbc.sql("SELECT BIN_TO_UUID(id) id,BIN_TO_UUID(session_id) session_id,provider,payload,consumed,expires_at FROM auth_transactions WHERE type='oauth' AND secret_hash=? FOR UPDATE")
				.param(secrets.hash(state)).query().listOfRows().stream().findFirst().orElseThrow(()->new ApiException(ErrorCode.OAUTH_STATE_MISMATCH));
			if(!row.get("session_id").equals(session.id().toString()) || !row.get("provider").equals(provider) || Boolean.TRUE.equals(row.get("consumed"))) throw new ApiException(ErrorCode.OAUTH_STATE_MISMATCH);
			if(AuthService.instant(row.get("expires_at")).isBefore(Instant.now())) throw new ApiException(ErrorCode.OAUTH_EXPIRED);
			jdbc.sql("UPDATE auth_transactions SET consumed=TRUE WHERE id=UUID_TO_BIN(?)").param(row.get("id")).update();
			return row;
		});
		if(error!=null) throw new ApiException(ErrorCode.OAUTH_CANCELED);
		if(code==null || code.isBlank() || code.length()>4096) throw new ApiException(ErrorCode.OAUTH_CODE_INVALID);
		var payload=json.readTree((String)saved.get("payload"));
		var identity=gateway.exchange(provider,code,state,payload.get("nonce").asString(),secrets.decrypt(payload.get("verifier").asString()));
		if(payload.get("purpose").asString().equals("reauth")) {
			if(session.userId()==null || !session.userId().equals(auth.identityUser(provider,identity.subject()))) throw new ApiException(ErrorCode.REAUTH_REQUIRED);
			return settings.origin()+"/account#reauthProof="+auth.proof(session).get("reauthProof");
		}
		var result=auth.login(session,provider,identity.subject(),identity.email()==null ? null : auth.normalizeEmail(identity.email()),response);
		String target=payload.get("returnTo").asString();
		return settings.origin()+(Boolean.TRUE.equals(result.get("needsOnboarding"))
				? UriComponentsBuilder.fromPath("/onboarding").queryParam("returnTo",target).build().encode().toUriString() : target);
	}

	public String safeReturnTo(String value) {
		if(value==null || value.isBlank()) return "/";
		if(!value.matches("/(?:|account|projects(?:/[a-zA-Z0-9-]+(?:/(?:settings|members|materials))?)?|invite/[A-Za-z0-9_-]{20,128})")) throw new ApiException(ErrorCode.INVALID_RETURN_TO);
		return value;
	}
}
