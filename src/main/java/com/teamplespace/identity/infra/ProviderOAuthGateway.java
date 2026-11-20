package com.teamplespace.identity.infra;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.teamplespace.identity.config.AuthSettings;
import com.teamplespace.identity.service.AuthSecrets;
import com.teamplespace.identity.service.OAuthGateway;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

@Component
public class ProviderOAuthGateway implements OAuthGateway {

	private final AuthSettings settings;
	private final AuthSecrets secrets;
	private final RestClient client;

	public ProviderOAuthGateway(AuthSettings settings, AuthSecrets secrets) {
		this.settings=settings; this.secrets=secrets;
		var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.client=RestClient.builder().requestFactory(factory).build();
	}

	public String authorizationUrl(String provider,String state,String nonce,String verifier,String purpose) {
		String endpoint=switch(provider) {
			case "google" -> "https://accounts.google.com/o/oauth2/v2/auth";
			case "kakao" -> "https://kauth.kakao.com/oauth/authorize";
			case "naver" -> "https://nid.naver.com/oauth2.0/authorize";
			default -> throw new ApiException(ErrorCode.UNSUPPORTED_PROVIDER);
		};
		var uri=UriComponentsBuilder.fromUriString(endpoint).queryParam("client_id",settings.value(provider+".client-id"))
			.queryParam("redirect_uri",callback(provider)).queryParam("response_type","code").queryParam("state",state);
		if(provider.equals("google")) uri.queryParam("scope","openid email profile").queryParam("nonce",nonce)
			.queryParam("code_challenge",Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.hash(verifier))).queryParam("code_challenge_method","S256");
		if (purpose.equals("reauth")) {
			if (provider.equals("kakao")) uri.queryParam("prompt","login");
			if (provider.equals("naver")) uri.queryParam("auth_type","reauthenticate");
			if (provider.equals("google")) uri.queryParam("prompt","select_account")
				.queryParam("claims","{\"id_token\":{\"auth_time\":{\"essential\":true}}}");
		}
		return uri.build().encode().toUriString();
	}

	@SuppressWarnings("unchecked")
	public Identity exchange(String provider,String code,String state,String nonce,String verifier) {
		String tokenUrl=switch(provider) {
			case "google" -> "https://oauth2.googleapis.com/token";
			case "kakao" -> "https://kauth.kakao.com/oauth/token";
			case "naver" -> "https://nid.naver.com/oauth2.0/token";
			default -> throw new ApiException(ErrorCode.UNSUPPORTED_PROVIDER);
		};
		try {
			var form=new LinkedMultiValueMap<String,String>();
			form.add("grant_type","authorization_code"); form.add("client_id",settings.value(provider+".client-id"));
			form.add("client_secret",settings.value(provider+".client-secret")); form.add("code",code); form.add("redirect_uri",callback(provider));
			if(provider.equals("google")) form.add("code_verifier",verifier);
			if(provider.equals("naver")) form.add("state",state);
			Map<String,Object> tokens=client.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
			if(tokens==null || !(tokens.get("access_token") instanceof String)) throw new ApiException(ErrorCode.OAUTH_CODE_INVALID);
			if(provider.equals("google")) {
				var decoder=NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
				decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("https://accounts.google.com"),
					jwt -> jwt.getAudience().contains(settings.value("google.client-id")) && nonce.equals(jwt.getClaimAsString("nonce"))
						? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
				var jwt=decoder.decode((String)tokens.get("id_token"));
				if(jwt.getSubject()==null) throw new ApiException(ErrorCode.ID_TOKEN_INVALID);
				return new Identity(jwt.getSubject(),Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) ? jwt.getClaimAsString("email") : null, jwt.getClaimAsInstant("auth_time"));
			}
			String userUrl=provider.equals("kakao") ? "https://kapi.kakao.com/v2/user/me" : "https://openapi.naver.com/v1/nid/me";
			Map<String,Object> user=client.get().uri(userUrl).headers(headers->headers.setBearerAuth((String)tokens.get("access_token"))).retrieve().body(Map.class);
			if(user==null) throw new ApiException(ErrorCode.OAUTH_CODE_INVALID);
			if(provider.equals("kakao")) {
				if(user.get("id")==null) throw new ApiException(ErrorCode.OAUTH_CODE_INVALID);
				Map<String,Object> account=(Map<String,Object>)user.getOrDefault("kakao_account",Map.of());
				return new Identity(user.get("id").toString(),Boolean.TRUE.equals(account.get("is_email_verified")) && Boolean.TRUE.equals(account.get("is_email_valid")) ? (String)account.get("email") : null);
			}
			Map<String,Object> profile=(Map<String,Object>)user.get("response");
			if(!"00".equals(user.get("resultcode")) || profile==null || !(profile.get("id") instanceof String)) throw new ApiException(ErrorCode.OAUTH_CODE_INVALID);
			return new Identity((String)profile.get("id"),(String)profile.get("email"));
		} catch(ApiException ex) { throw ex; }
		catch(Exception ex) { throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,60); }
	}

	private String callback(String provider) { return settings.value("backend-origin")+"/v1/auth/oauth/"+provider+"/callback"; }
}
