package com.teamplespace.collaboration.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.collaboration.service.ChangeRequests;
import com.teamplespace.collaboration.service.ChangeRequests.CreateRequest;
import com.teamplespace.identity.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;

// contracts/stage-3-generation.md의 승인 요청 경로
@RestController
@RequestMapping("/v1")
public class ChangeRequestController {

	private final ChangeRequests requests;
	private final AuthService auth;

	public ChangeRequestController(ChangeRequests requests, AuthService auth) {
		this.requests = requests;
		this.auth = auth;
	}

	@PostMapping("/projects/{projectId}/requests")
	ResponseEntity<?> create(@PathVariable UUID projectId, @RequestBody CreateRequest input,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		return ResponseEntity.status(201).body(requests.create(actor(request), projectId, key, input));
	}

	@GetMapping("/projects/{projectId}/requests")
	Map<String, Object> list(@PathVariable UUID projectId, HttpServletRequest request) {
		return requests.list(actor(request), projectId);
	}

	@PostMapping("/requests/{requestId}/approve")
	Map<String, Object> approve(@PathVariable UUID requestId, HttpServletRequest request) {
		return requests.approve(actor(request), requestId);
	}

	@PostMapping("/requests/{requestId}/reject")
	Map<String, Object> reject(@PathVariable UUID requestId, HttpServletRequest request) {
		return requests.reject(actor(request), requestId);
	}

	@PostMapping("/requests/{requestId}/cancel")
	Map<String, Object> cancel(@PathVariable UUID requestId, HttpServletRequest request) {
		return requests.cancel(actor(request), requestId);
	}

	private UUID actor(HttpServletRequest request) {
		return auth.requireOnboarded(request).userId();
	}

}
