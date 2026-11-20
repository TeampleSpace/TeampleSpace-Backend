package com.teamplespace.materials.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.identity.service.AuthService;
import com.teamplespace.materials.service.UploadService.StoredFile;
import com.teamplespace.materials.service.UploadService;

import jakarta.servlet.http.HttpServletRequest;

// 서명한 주소로 파일을 받고 내려 준다. 외부 저장소를 쓰게 되면 이 경로 대신 저장소의 주소를 발급한다
@RestController
@RequestMapping("/v1/storage")
public class StorageController {

	private final UploadService uploads;

	private final AuthService auth;

	public StorageController(UploadService uploads, AuthService auth) {
		this.uploads = uploads;
		this.auth = auth;
	}

	// CSRF 헤더 없이 받는다. 서명한 주소와 준비한 사용자의 세션이 모두 맞아야 한다
	@PutMapping("/uploads/{token}")
	ResponseEntity<Void> upload(@PathVariable String token, HttpServletRequest request) throws IOException {
		uploads.receive(auth.requireOnboarded(request).userId(), token, request.getContentLengthLong(),
				request.getInputStream());
		return ResponseEntity.noContent().build();
	}

	// 범위 요청은 프레임워크가 처리한다
	@GetMapping("/files/{token}")
	ResponseEntity<Resource> file(@PathVariable String token, HttpServletRequest request) {
		StoredFile file = uploads.file(auth.requireOnboarded(request).userId(), token);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.mimeType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
			.body(new FileSystemResource(file.path()));
	}

}
