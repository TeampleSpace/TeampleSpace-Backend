package com.teamplespace.composition;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.identity.AuthService;
import com.teamplespace.platform.error.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/projects/{projectId}/outline")
public class OutlineController {

	private final AuthService auth;
	private final OutlineService outlines;

	public OutlineController(AuthService auth, OutlineService outlines) {
		this.auth = auth;
		this.outlines = outlines;
	}

	@GetMapping
	OutlineService.Outline get(@PathVariable UUID projectId, HttpServletRequest request) {
		return outlines.get(auth.requireOnboarded(request).userId(), projectId);
	}

	@PutMapping
	JsonNode save(@PathVariable UUID projectId, @RequestBody JsonNode input,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		var result = outlines.save(auth.requireOnboarded(request).userId(), projectId, key, input);
		if (result.status() == 409) {
			throw ApiException.conflict("outlineVersion", result.body().get("outlineVersion").asLong());
		}
		return result.body();
	}
}
