package com.teamplespace.composition.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.composition.service.OutlineService;
import com.teamplespace.identity.service.AuthService;
import com.teamplespace.platform.error.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1")
public class OutlineController {

	private final AuthService auth;
	private final OutlineService outlines;

	public OutlineController(AuthService auth, OutlineService outlines) {
		this.auth = auth;
		this.outlines = outlines;
	}

	@GetMapping("/projects/{projectId}/outline")
	OutlineService.Outline get(@PathVariable UUID projectId, HttpServletRequest request) {
		return outlines.get(auth.requireOnboarded(request).userId(), projectId);
	}

	@PutMapping("/projects/{projectId}/outline")
	JsonNode save(@PathVariable UUID projectId, @RequestBody JsonNode input,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		return saved(outlines.save(auth.requireOnboarded(request).userId(), projectId, key, input));
	}

	@PutMapping("/outline-items/{itemId}/sources")
	JsonNode sources(@PathVariable UUID itemId, @RequestBody JsonNode input,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		return saved(outlines.replaceSources(auth.requireOnboarded(request).userId(), itemId, key, input));
	}

	private JsonNode saved(OutlineService.SaveResult result) {
		if (result.status() == 409) {
			throw ApiException.conflict("outlineVersion", result.body().get("outlineVersion").asLong());
		}
		return result.body();
	}
}
