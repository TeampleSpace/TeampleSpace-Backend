package com.teamplespace.collaboration.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teamplespace.platform.error.ApiException;
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

	// ponytail: 연결마다 0.5초 간격으로 DB를 조회하고 스레드 하나로 보낸다
	// 동시 연결이 늘면 커밋 알림으로 깨우고 보내는 스레드를 나눈다
	private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "event-stream");
		thread.setDaemon(true);
		return thread;
	});

	public EventStreams(ProjectEvents events, ProjectAccess access) {
		this.events = events;
		this.access = access;
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
				for (JsonNode event : replay.events()) {
					String id = event.get("eventId").asString();
					emitter.send(SseEmitter.event()
						.id(id)
						.name(event.get("type").asString())
						.data(event, MediaType.APPLICATION_JSON));
					cursor = id;
				}
				if (!replay.events().isEmpty()) {
					idlePolls = 0;
				}
				else if (++idlePolls >= KEEP_ALIVE_POLLS) {
					emitter.send(SseEmitter.event().comment("keep-alive"));
					idlePolls = 0;
				}
			}
			catch (ApiException ex) {
				// 권한을 잃었거나 프로젝트가 사라졌다. 다시 연결하면 오류 응답을 받는다
				close();
			}
			catch (IOException | RuntimeException ex) {
				// 클라이언트가 떠났거나 보낼 수 없는 상태다
				cancel();
			}
		}

		private void close() {
			cancel();
			emitter.complete();
		}

		private void cancel() {
			if (task != null) {
				task.cancel(false);
			}
		}

	}

}
