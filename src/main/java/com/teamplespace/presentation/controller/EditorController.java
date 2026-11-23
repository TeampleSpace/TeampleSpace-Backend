package com.teamplespace.presentation.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teamplespace.collaboration.service.EventStreams;
import com.teamplespace.collaboration.service.Presence;
import com.teamplespace.identity.service.AuthService;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.platform.web.RequestIdFilter;
import com.teamplespace.presentation.service.ElementPatchService;
import com.teamplespace.presentation.service.PresentationSnapshots;
import com.teamplespace.presentation.service.SamplePresentations;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

// 슬라이드 편집 화면이 쓰는 저장, 동기화 스냅샷, 이벤트 구독 경로
@RestController
@RequestMapping("/v1")
public class EditorController {

	private final AuthService auth;

	private final ElementPatchService patches;

	private final PresentationSnapshots snapshots;

	private final EventStreams streams;

	private final SamplePresentations samples;

	private final Presence presence;

	// 커서 위치. 슬라이드가 없으면 편집 화면을 떠난 것이다
	record PresenceRequest(UUID slideId, Double x, Double y) {
	}

	public EditorController(AuthService auth, ElementPatchService patches, PresentationSnapshots snapshots,
			EventStreams streams, SamplePresentations samples, Presence presence) {
		this.auth = auth;
		this.patches = patches;
		this.snapshots = snapshots;
		this.streams = streams;
		this.samples = samples;
		this.presence = presence;
	}

	@PatchMapping("/slides/{slideId}/elements/{elementId}")
	ResponseEntity<JsonNode> patch(@PathVariable UUID slideId, @PathVariable UUID elementId,
			@RequestBody JsonNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key,
			HttpServletRequest request) {
		ElementPatchService.Result result = patches.patch(actor(request), slideId, elementId, key, body);
		JsonNode response = result.body();
		if (result.status() >= 400) {
			// 멱등 기록에 남긴 거절 본문에는 요청 식별자가 없다
			response = ((ObjectNode) response).put("requestId",
					String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
		}
		return ResponseEntity.status(result.status()).body(response);
	}

	// 편집 화면용 묶음만 만든다. 다른 범위는 해당 화면을 만들 때 더한다
	@GetMapping("/projects/{projectId}/sync-snapshot")
	JsonNode snapshot(@PathVariable UUID projectId, @RequestParam(required = false) String scope,
			HttpServletRequest request) {
		Actor actor = actor(request);
		if (!"editor".equals(scope)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		return snapshots.editor(actor, projectId);
	}

	@GetMapping("/projects/{projectId}/events")
	SseEmitter events(@PathVariable UUID projectId,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, HttpServletRequest request) {
		return streams.open(actor(request), projectId, lastEventId);
	}

	@PostMapping("/projects/{projectId}/presence")
	ResponseEntity<Void> presence(@PathVariable UUID projectId, @RequestBody PresenceRequest input,
			HttpServletRequest request) {
		presence.update(actor(request), projectId, input.slideId(), input.x(), input.y());
		return ResponseEntity.noContent().build();
	}

	// 설정으로 켠 개발과 시험 환경에서만 동작하고 그 밖에는 없는 경로처럼 응답한다
	@PostMapping("/projects/{projectId}/sample-presentation")
	ResponseEntity<Void> sample(@PathVariable UUID projectId, HttpServletRequest request) {
		samples.create(actor(request).userId(), projectId);
		return ResponseEntity.noContent().build();
	}

	// ponytail: 로그인 세션을 편집 세션으로 써서 같은 로그인의 여러 탭이 변경 묶음을 서로 닫을 수 있다
	// 탭별로 나누려면 요청에 탭 식별자를 싣는다
	private Actor actor(HttpServletRequest request) {
		AuthService.Session session = auth.requireOnboarded(request);
		return new Actor(session.userId(), session.id());
	}

}
