package com.teamplespace.materials;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.identity.AuthService;
import com.teamplespace.materials.MaterialService.DeleteRequest;
import com.teamplespace.materials.MaterialService.MemoRequest;
import com.teamplespace.materials.MaterialService.NoteRequest;
import com.teamplespace.materials.MaterialService.PatchRequest;
import com.teamplespace.materials.UploadService.FileRequest;
import com.teamplespace.materials.UploadService.UploadRequest;

import jakarta.servlet.http.HttpServletRequest;

// contracts/stage-2.md의 자료와 팀 메모 경로
@RestController
@RequestMapping("/v1")
public class MaterialController {

	private final MaterialService materials;

	private final UploadService uploads;

	private final AuthService auth;

	public MaterialController(MaterialService materials, UploadService uploads, AuthService auth) {
		this.materials = materials;
		this.uploads = uploads;
		this.auth = auth;
	}

	@PostMapping("/projects/{projectId}/uploads")
	ResponseEntity<?> prepareUpload(@PathVariable UUID projectId, @RequestBody UploadRequest input,
			HttpServletRequest request) {
		return ResponseEntity.status(201).body(uploads.prepare(actor(request), projectId, input));
	}

	@PostMapping("/projects/{projectId}/materials/file")
	ResponseEntity<?> createFile(@PathVariable UUID projectId, @RequestBody FileRequest input,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		return ResponseEntity.status(201).body(uploads.createFile(actor(request), projectId, input, key));
	}

	@PostMapping("/materials/{materialId}/reprocess")
	ResponseEntity<?> reprocess(@PathVariable UUID materialId,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		return ResponseEntity.status(201).body(materials.reprocess(actor(request), materialId, key));
	}

	@GetMapping("/projects/{projectId}/materials")
	Map<String, Object> list(@PathVariable UUID projectId, @RequestParam(required = false) String q,
			@RequestParam(required = false) String kind, @RequestParam(required = false) String status,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
			HttpServletRequest request) {
		return materials.list(actor(request), projectId, q, kind, status, cursor, limit);
	}

	@PostMapping("/projects/{projectId}/materials/memo")
	ResponseEntity<?> createMemo(@PathVariable UUID projectId, @RequestBody MemoRequest input,
			@RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
		return ResponseEntity.status(201).body(materials.createMemo(actor(request), projectId, input, key));
	}

	@GetMapping("/materials/{materialId}")
	Map<String, Object> get(@PathVariable UUID materialId, HttpServletRequest request) {
		return materials.get(actor(request), materialId);
	}

	@PatchMapping("/materials/{materialId}")
	Map<String, Object> patch(@PathVariable UUID materialId, @RequestBody PatchRequest input,
			HttpServletRequest request) {
		return materials.patch(actor(request), materialId, input);
	}

	@DeleteMapping("/materials/{materialId}")
	Map<String, Object> delete(@PathVariable UUID materialId, @RequestBody DeleteRequest input,
			HttpServletRequest request) {
		return materials.delete(actor(request), materialId, input);
	}

	@GetMapping("/materials/{materialId}/source")
	Map<String, Object> source(@PathVariable UUID materialId, @RequestParam(required = false) UUID revisionId,
			HttpServletRequest request) {
		return materials.source(actor(request), materialId, revisionId);
	}

	@GetMapping("/materials/{materialId}/note")
	Map<String, Object> note(@PathVariable UUID materialId, HttpServletRequest request) {
		return materials.note(actor(request), materialId);
	}

	@PutMapping("/materials/{materialId}/note")
	Map<String, Object> saveNote(@PathVariable UUID materialId, @RequestBody NoteRequest input,
			HttpServletRequest request) {
		return materials.saveNote(actor(request), materialId, input);
	}

	private UUID actor(HttpServletRequest request) {
		return auth.requireOnboarded(request).userId();
	}

}
