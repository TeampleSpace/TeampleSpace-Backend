package com.teamplespace.presentation.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.identity.service.AuthService;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.presentation.service.SlideRevision;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1")
public class SlideRevisionController {

	private final AuthService auth;
	private final SlideRevision revisions;

	public SlideRevisionController(AuthService auth, SlideRevision revisions) {
		this.auth = auth;
		this.revisions = revisions;
	}

	@PostMapping("/slides/{slideId}/ai-revision-jobs")
	ResponseEntity<JsonNode> create(@PathVariable UUID slideId, @RequestBody JsonNode body,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		var result = revisions.create(actor(request), slideId, key, body);
		return ResponseEntity.status(result.status()).body(result.body());
	}

	// 제안 식별자는 수정 작업의 식별자다
	@PostMapping("/ai-proposals/{proposalId}/apply")
	ResponseEntity<JsonNode> apply(@PathVariable UUID proposalId,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		var result = revisions.apply(actor(request), proposalId, key);
		return ResponseEntity.status(result.status()).body(result.body());
	}

	private Actor actor(HttpServletRequest request) {
		AuthService.Session session = auth.requireOnboarded(request);
		return new Actor(session.userId(), session.id());
	}

}
