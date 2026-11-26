package com.teamplespace.collaboration.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.collaboration.service.MemoUpdates;
import com.teamplespace.collaboration.service.Signals;
import com.teamplespace.identity.service.AuthService;
import com.teamplespace.platform.security.Actor;

import jakarta.servlet.http.HttpServletRequest;

// 메모 공유 편집의 변경 조각 경로
@RestController
@RequestMapping("/v1/materials/{materialId}")
public class MemoController {

	record AppendRequest(String data, Boolean initial) {
	}

	record CompactRequest(Long upTo, String data) {
	}

	record AwarenessRequest(String data) {
	}

	private final MemoUpdates updates;

	private final Signals signals;

	private final AuthService auth;

	public MemoController(MemoUpdates updates, Signals signals, AuthService auth) {
		this.updates = updates;
		this.signals = signals;
		this.auth = auth;
	}

	@GetMapping("/memo-updates")
	Map<String, Object> list(@PathVariable UUID materialId, @RequestParam(defaultValue = "0") long after,
			HttpServletRequest request) {
		return updates.after(actor(request), materialId, after);
	}

	// 저장이 끝난 뒤 같은 프로젝트의 구독으로 조각을 바로 보낸다
	@PostMapping("/memo-updates")
	ResponseEntity<Map<String, Object>> append(@PathVariable UUID materialId, @RequestBody AppendRequest input,
			HttpServletRequest request) {
		MemoUpdates.Appended appended = updates.append(actor(request), materialId, input.data(),
				Boolean.TRUE.equals(input.initial()));
		signals.publish(appended.projectId(), "memo.updated",
				Map.of("materialId", materialId.toString(), "seq", appended.seq(), "data", input.data()));
		return ResponseEntity.status(201).body(Map.of("seq", appended.seq()));
	}

	@PostMapping("/memo-updates/compaction")
	ResponseEntity<Void> compact(@PathVariable UUID materialId, @RequestBody CompactRequest input,
			HttpServletRequest request) {
		updates.compact(actor(request), materialId, input.upTo() == null ? 0 : input.upTo(), input.data());
		return ResponseEntity.noContent().build();
	}

	// 커서와 선택 영역. 같은 프로젝트의 구독으로 바로 넘기고 저장하지 않는다
	@PostMapping("/memo-awareness")
	ResponseEntity<Void> awareness(@PathVariable UUID materialId, @RequestBody AwarenessRequest input,
			HttpServletRequest request) {
		UUID projectId = updates.awareness(actor(request), materialId, input.data());
		signals.publish(projectId, "memo.awareness", Map.of("materialId", materialId.toString(), "data", input.data()));
		return ResponseEntity.noContent().build();
	}

	private Actor actor(HttpServletRequest request) {
		AuthService.Session session = auth.requireOnboarded(request);
		return new Actor(session.userId(), session.id());
	}

}
