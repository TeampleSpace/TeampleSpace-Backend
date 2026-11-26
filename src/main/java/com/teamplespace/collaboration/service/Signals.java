package com.teamplespace.collaboration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

// 이벤트 구독으로 바로 넘기고 버리는 신호. 메모 변경 조각과 커서 상태를 나른다
// 놓친 메모 조각은 받는 쪽이 순번 차이로 알아채고 다시 읽는다
// ponytail: 서버 한 대의 메모리에 둔다. 서버를 여러 대로 늘리면 메시지 브로커로 옮긴다
@Component
public class Signals {

	public record Signal(long id, UUID projectId, String type, Object data, Instant at) {
	}

	// 구독은 0.5초마다 읽으므로 이만큼이면 충분하다
	static final Duration KEEP = Duration.ofSeconds(30);

	private final AtomicLong last = new AtomicLong();

	private final ConcurrentLinkedDeque<Signal> recent = new ConcurrentLinkedDeque<>();

	private final Clock clock;

	public Signals(Clock clock) {
		this.clock = clock;
	}

	public void publish(UUID projectId, String type, Object data) {
		Instant now = clock.instant();
		// 번호를 붙이는 것과 넣는 것을 함께 해 순서대로 쌓는다
		synchronized (recent) {
			recent.addLast(new Signal(last.incrementAndGet(), projectId, type, data, now));
		}
		Instant expired = now.minus(KEEP);
		for (Signal first = recent.peekFirst(); first != null && first.at().isBefore(expired); first = recent.peekFirst()) {
			recent.pollFirst();
		}
	}

	public long last() {
		return last.get();
	}

	// 번호 다음의 신호 중 이 프로젝트의 것
	public List<Signal> after(long id, UUID projectId) {
		List<Signal> found = new ArrayList<>();
		for (Signal signal : recent) {
			if (signal.id() > id && signal.projectId().equals(projectId)) {
				found.add(signal);
			}
		}
		return found;
	}

}
