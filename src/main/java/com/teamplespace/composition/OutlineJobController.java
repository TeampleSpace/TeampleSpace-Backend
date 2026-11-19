package com.teamplespace.composition;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.identity.AuthService;
import com.teamplespace.platform.error.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/projects/{projectId}/outline-jobs")
public class OutlineJobController {

	private final AuthService auth;
	private final OutlineGeneration generator;

	public OutlineJobController(AuthService auth, OutlineGeneration generator) {
		this.auth = auth;
		this.generator = generator;
	}

	@PostMapping
	ResponseEntity<JsonNode> create(@PathVariable UUID projectId, @RequestBody JsonNode body,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		var result = generator.create(auth.requireOnboarded(request).userId(), projectId, key, body);
		if (result.status() == 409) {
			throw ApiException.conflict("outlineVersion", result.body().get("outlineVersion").asLong());
		}
		return ResponseEntity.accepted().body(result.body());
	}
}
