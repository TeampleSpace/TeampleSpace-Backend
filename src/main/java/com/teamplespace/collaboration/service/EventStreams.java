package com.teamplespace.collaboration.service;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teamplespace.platform.security.Actor;
import com.teamplespace.workspace.service.ProjectAccess;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;

// 이벤트 구독 연결. 커서 다음 이벤트를 SSE로 보낸다
@Component
public class EventStreams {

	// 연결이 끝나면 클라이언트가 Last-Event-ID로 다시 구독하고 그때 로그인 세션을 다시 확인한다
	private static final Duration LIFETIME = Duration.ofMinutes(5);

	private static final long POLL_MILLIS = 500;

	// 이벤트가 없어도 15초마다 주석 줄을 보내 연결을 유지한다
	private static final int KEEP_ALIVE_POLLS = 30;

	private static final int BATCH = 100;

	private final ProjectEvents events;

	private final ProjectAccess access;

	private final Presence presence;

	// ponytail: 연결마다 0.5초 간격으로 DB를 조회하고 스레드 하나로 보낸다
	// 동시 연결이 늘면 커밋 알림으로 깨우고 보내는 스레드를 나눈다
	private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "event-stream");
		thread.setDaemon(true);
		return thread;
	});

	public EventStreams(ProjectEvents events, ProjectAccess access, Presence presence) {
		this.events = events;
		this.access = access;
		this.presence = presence;
	}

	// 커서가 없으면 지금부터의 이벤트만 보낸다
	public SseEmitter open(Actor actor, UUID projectId, String lastEventId) {
		// 권한 오류는 스트림을 열기 전에 일반 오류 응답으로 알린다
		access.requireMember(projectId, actor.userId());
		String cursor = lastEventId != null ? lastEventId : Long.toString(events.currentSeq(projectId));
		Subscription subscription = new Subscription(actor, projectId, cursor);
		subscription.task = poller.scheduleWithFixedDelay(subscription, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
		return subscription.emitter;
	}

	@PreDestroy
	void stop() {
		poller.shutdownNow();
	}

	private final class Subscription implements Runnable {

		private final SseEmitter emitter = new SseEmitter(LIFETIME.toMillis());

		private final Actor actor;

		private final UUID projectId;

		private String cursor;

		// 마지막으로 보낸 다른 팀원의 커서 목록. 바뀔 때만 다시 보낸다
		private List<Presence.Cursor> cursors = List.of();

		// 응답 헤더는 첫 전송 때 나간다. 첫 조회에 이벤트가 없어도 주석 줄을 보내 연결을 바로 알린다
		private int idlePolls = KEEP_ALIVE_POLLS - 1;

		private volatile ScheduledFuture<?> task;

		private Subscription(Actor actor, UUID projectId, String cursor) {
			this.actor = actor;
			this.projectId = projectId;
			this.cursor = cursor;
			emitter.onTimeout(emitter::complete);
			emitter.onCompletion(this::cancel);
			emitter.onError(error -> cancel());
		}

		@Override
		public void run() {
			try {
				// 조회할 때마다 프로젝트 참여 여부를 다시 확인한다
				ProjectEvents.Replay replay = events.after(actor, projectId, cursor, BATCH);
				if (replay.snapshotRequired()) {
					emitter.send(SseEmitter.event()
						.name("snapshot_required")
						.data(Map.of("projectId", projectId.toString()), MediaType.APPLICATION_JSON));
					close();
					return;
				}
				// 한 번 조회한 이벤트와 커서 위치를 한 번에 써서 비운다
				// 건마다 따로 쓰면 운영 경로의 중간 구간이 첫 건 뒤를 다음 연결 유지 신호까지 붙잡았다
				Set<ResponseBodyEmitter.DataWithMediaType> batch = new LinkedHashSet<>();
				String last = cursor;
				for (JsonNode event : replay.events()) {
					last = event.get("eventId").asString();
					batch.addAll(SseEmitter.event()
						.id(last)
						.name(event.get("type").asString())
						.data(event, MediaType.APPLICATION_JSON)
						.build());
				}
				// 커서 위치는 다시 받을 필요가 없어 이벤트 순번 없이 보낸다
				List<Presence.Cursor> now = presence.others(projectId, actor.userId());
				boolean moved = !now.equals(cursors);
				if (moved) {
					batch.addAll(SseEmitter.event()
						.name("presence.updated")
						.data(Map.of("projectId", projectId.toString(), "cursors", now), MediaType.APPLICATION_JSON)
						.build());
				}
				if (batch.isEmpty() && ++idlePolls >= KEEP_ALIVE_POLLS) {
					batch.addAll(SseEmitter.event().comment("keep-alive").build());
				}
				if (batch.isEmpty()) {
					return;
				}
				emitter.send(batch);
				cursor = last;
				if (moved) {
					cursors = now;
				}
				idlePolls = 0;
			}
			catch (IOException ex) {
				// 클라이언트가 떠났다
				cancel();
			}
			catch (RuntimeException ex) {
				// 권한을 잃었거나 조회에 실패했다. 연결을 끝내 클라이언트가 다시 구독하게 한다
				// 권한이 없으면 다시 연결할 때 오류 응답을 받는다
				close();
			}
		}

		private void close() {
			cancel();
			try {
				emitter.complete();
			}
			catch (RuntimeException ex) {
				// 이미 쓸 수 없는 연결이다
			}
		}

		private void cancel() {
			if (task != null) {
				task.cancel(false);
			}
		}

	}

}
