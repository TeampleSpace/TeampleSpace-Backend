package com.teamplespace.composition.controller;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.composition.service.ProjectFontService;
import com.teamplespace.identity.service.AuthService;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/v1/projects/{projectId}/design-fonts")
public class ProjectFontController {

	private final AuthService auth;
	private final ProjectFontService fonts;

	public ProjectFontController(AuthService auth, ProjectFontService fonts) {
		this.auth = auth;
		this.fonts = fonts;
	}

	@GetMapping
	ProjectFontService.FontList list(@PathVariable UUID projectId, HttpServletRequest request) {
		return fonts.list(auth.requireOnboarded(request).userId(), projectId);
	}

	@PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	ProjectFontService.Font upload(@PathVariable UUID projectId, @RequestParam String fileName,
			HttpServletRequest request) throws IOException {
		UUID actor = auth.requireOnboarded(request).userId();
		fonts.requireMember(actor, projectId);
		if (request.getContentLengthLong() > ProjectFontService.MAX_FILE_BYTES) {
			throw new ApiException(ErrorCode.UPLOAD_TOO_LARGE);
		}
		byte[] bytes = request.getInputStream().readNBytes(ProjectFontService.MAX_FILE_BYTES + 1);
		return fonts.upload(actor, projectId, fileName, bytes);
	}

	@GetMapping("/{fontId}/content")
	ResponseEntity<byte[]> content(@PathVariable UUID projectId, @PathVariable UUID fontId,
			HttpServletRequest request, HttpServletResponse response) {
		var font = fonts.content(auth.requireOnboarded(request).userId(), projectId, fontId);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
		response.setHeader("X-Content-Type-Options", "nosniff");
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("font/" + font.format()))
				.header("Cross-Origin-Resource-Policy", "same-origin")
				.body(font.bytes());
	}
}
