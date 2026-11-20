package com.teamplespace.processing.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.identity.service.AuthService;
import com.teamplespace.processing.service.JobQueue;

import jakarta.servlet.http.HttpServletRequest;

// contracts/stage-2.md의 작업 경로
@RestController
@RequestMapping("/v1")
public class JobController {

	private final JobQueue jobs;

	private final AuthService auth;

	public JobController(JobQueue jobs, AuthService auth) {
		this.jobs = jobs;
		this.auth = auth;
	}

	@GetMapping("/jobs/{jobId}")
	Map<String, Object> job(@PathVariable UUID jobId, HttpServletRequest request) {
		return jobs.get(actor(request), jobId);
	}

	@PostMapping("/jobs/{jobId}/cancel")
	Map<String, Object> cancel(@PathVariable UUID jobId, HttpServletRequest request) {
		return jobs.cancel(actor(request), jobId);
	}

	@GetMapping("/projects/{projectId}/jobs")
	Map<String, Object> list(@PathVariable UUID projectId, @RequestParam(required = false) String type,
			@RequestParam(required = false) String status, @RequestParam(required = false) String targetType,
			@RequestParam(required = false) UUID targetId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit, HttpServletRequest request) {
		return jobs.list(actor(request), projectId, type, status, targetType, targetId, cursor, limit);
	}

	private UUID actor(HttpServletRequest request) {
		return auth.requireOnboarded(request).userId();
	}

}
