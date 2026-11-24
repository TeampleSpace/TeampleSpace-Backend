package com.teamplespace.composition.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.composition.service.DesignService;
import com.teamplespace.identity.service.AuthService;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.presentation.service.TemplatePreviews;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1")
public class DesignController {

	private final AuthService auth;
	private final DesignService designs;
	private final TemplatePreviews previews;

	public DesignController(AuthService auth, DesignService designs, TemplatePreviews previews) {
		this.auth = auth;
		this.designs = designs;
		this.previews = previews;
	}

	@GetMapping("/templates")
	DesignService.Catalog templates(HttpServletRequest request) {
		auth.requireOnboarded(request);
		return designs.catalog();
	}

	@GetMapping("/templates/{templateId}/preview")
	TemplatePreviews.Preview preview(@PathVariable UUID templateId, HttpServletRequest request) {
		auth.requireOnboarded(request);
		return previews.preview(templateId);
	}

	@GetMapping("/projects/{projectId}/design-settings")
	DesignService.Settings settings(@PathVariable UUID projectId, HttpServletRequest request) {
		return designs.get(auth.requireOnboarded(request).userId(), projectId);
	}

	@PutMapping("/projects/{projectId}/design-settings")
	JsonNode save(@PathVariable UUID projectId, @RequestBody JsonNode body,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		var result = designs.save(auth.requireOnboarded(request).userId(), projectId, key, body);
		if (result.status() == 409) {
			throw ApiException.conflict("designVersion", result.body().get("designVersion").asLong());
		}
		return result.body();
	}
}
