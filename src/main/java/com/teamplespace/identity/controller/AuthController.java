package com.teamplespace.identity.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.teamplespace.composition.infra.OpenAiOutlineClient;
import com.teamplespace.identity.config.AuthSettings;
import com.teamplespace.identity.service.AuthService;
import com.teamplespace.identity.service.OAuthService;
import com.teamplespace.materials.infra.OpenAiMaterialSummaryClient;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.web.RequestIdFilter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/v1")
public class AuthController {

	public record ChallengeRequest(String email,String purpose) { }
	public record VerifyRequest(UUID challengeId,String code) { }
	public record ProfileRequest(String name,Long version,Map<String,String> consentVersions) { }
	public record DeleteRequest(String reauthProof,String confirmationText) { }
	private final AuthService auth;
	private final AuthSettings settings;
	private final OAuthService oauth;
	private final long pdfMaxBytes;
	private final OpenAiOutlineClient outlineAi;
	private final OpenAiMaterialSummaryClient summaryAi;

	public AuthController(AuthService auth,AuthSettings settings,OAuthService oauth,OpenAiOutlineClient outlineAi,OpenAiMaterialSummaryClient summaryAi,@org.springframework.beans.factory.annotation.Value("${teamplespace.materials.pdf-max-bytes:20971520}") long pdfMaxBytes) { this.auth=auth;this.settings=settings;this.oauth=oauth;this.outlineAi=outlineAi;this.summaryAi=summaryAi;this.pdfMaxBytes=pdfMaxBytes; }

	@GetMapping("/auth/bootstrap")
	Map<String,Object> bootstrap(HttpServletRequest req,HttpServletResponse res) { return auth.bootstrap(req,res); }

	@GetMapping("/capabilities")
	Map<String,Object> capabilities() {
		Map<String,Object> result=new LinkedHashMap<>();
		result.put("configVersion","stage-2");result.put("policyRevision",settings.legalDocuments().isEmpty()?"unconfigured":settings.value("terms-version"));
		result.put("uploadTypes",List.of(Map.of("kind","material_pdf","mimeType","application/pdf","maxBytes",pdfMaxBytes)));result.put("formats",List.of());result.put("fonts",List.of());
		result.put("featureFlags",Map.of("billing",false,"aiRevision",false,"restore",false,"accountLinking",false,"regeneration",false,"offlineDrafts",false,"outlineGeneration",outlineAi.available(),"materialSummary",summaryAi.available()));
		result.put("legalDocuments",settings.legalDocuments());result.put("idempotencyRetentionSeconds",86400);
		result.put("listLimit",Map.of("default",30,"max",100));
		result.put("authProviders",List.of("email","kakao","naver","google").stream().map(provider->Map.of("provider",provider,"enabled",provider.equals("email")?settings.mailEnabled():settings.oauthEnabled(provider),"reauthEnabled",provider.equals("email")?settings.mailEnabled():!provider.equals("google")&&settings.oauthEnabled(provider))).toList());
		return result;
	}

	@PostMapping("/auth/challenges")
	ResponseEntity<?> challenge(@RequestBody ChallengeRequest body,HttpServletRequest req) { return ResponseEntity.accepted().body(auth.challenge(req,body.email(),body.purpose())); }

	@PostMapping("/auth/sessions")
	ResponseEntity<?> login(@RequestBody VerifyRequest body,HttpServletRequest req,HttpServletResponse res) {
		if(body.challengeId()==null) throw new ApiException(ErrorCode.VALIDATION_FAILED);
		var session=auth.session(req);
		return ResponseEntity.status(201).body(auth.authenticate(session,body.challengeId(),body.code(),res));
	}

	@PostMapping("/auth/reauthentications")
	ResponseEntity<?> reauthenticate(@RequestBody VerifyRequest body,HttpServletRequest req) {
		if(body.challengeId()==null) throw new ApiException(ErrorCode.VALIDATION_FAILED);
		var session=auth.requireUser(req);auth.consumeChallenge(session,body.challengeId(),body.code(),"reauth");
		return ResponseEntity.status(201).body(auth.proof(session));
	}

	@GetMapping("/me")
	Map<String,Object> me(HttpServletRequest req) { return auth.user(auth.requireUser(req).userId()); }

	@PatchMapping("/me")
	Map<String,Object> profile(@RequestBody ProfileRequest body,HttpServletRequest req) {
		return auth.updateUser(auth.requireUser(req),body.name(),body.version(),body.consentVersions(),(String)req.getAttribute(RequestIdFilter.ATTRIBUTE));
	}

	@DeleteMapping("/auth/session")
	ResponseEntity<?> logout(HttpServletRequest req,HttpServletResponse res) {
		var session=auth.session(req);if(session!=null) auth.revoke(session.id());auth.clearCookie(res);return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/me")
	ResponseEntity<?> delete(@RequestBody DeleteRequest body,HttpServletRequest req,HttpServletResponse res) {
		return ResponseEntity.accepted().body(auth.deleteUser(auth.requireUser(req),body.reauthProof(),body.confirmationText(),res));
	}

	@GetMapping("/auth/oauth/{provider}/authorize")
	ResponseEntity<?> authorize(@PathVariable String provider,@RequestParam(defaultValue="login") String purpose,
			@RequestParam(required=false) String returnTo,HttpServletRequest req,HttpServletResponse res) {
		return ResponseEntity.status(302).header("Location",oauth.authorize(provider,purpose,returnTo,req,res)).build();
	}

	@GetMapping("/auth/oauth/{provider}/callback")
	ResponseEntity<?> callback(@PathVariable String provider,@RequestParam(required=false) String code,
			@RequestParam(required=false) String state,@RequestParam(required=false) String error,HttpServletRequest req,HttpServletResponse res) {
		try { return ResponseEntity.status(303).header("Location",oauth.callback(provider,state,code,error,req,res)).build(); }
		catch(ApiException ex) { return ResponseEntity.status(303).header("Location",settings.origin()+"/auth/error?code="+ex.code().name()).build(); }
	}

	@PostMapping({"/me/identities","/me/identities/{provider}/authorize"})
	void linkingDisabled() { throw new ApiException(ErrorCode.FEATURE_NOT_ENABLED); }
}
