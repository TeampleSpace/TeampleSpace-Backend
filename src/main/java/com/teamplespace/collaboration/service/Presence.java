package com.teamplespace.collaboration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.workspace.service.ProjectAccess;

// 편집 화면의 커서 위치. 잠깐 쓰고 버리는 값이라 DB에 쌓지 않는다
// ponytail: 서버 한 대의 메모리에 둔다. 서버를 여러 대로 늘리면 공유 저장소나 메시지 브로커로 옮긴다
@Component
public class Presence {

	public record Cursor(UUID userId, UUID slideId, double x, double y) {
	}

	private record Entry(Cursor cursor, Instant at) {
	}

	// 이 시간 동안 위치가 오지 않으면 화면을 떠난 것으로 본다
	static final Duration TTL = Duration.ofSeconds(10);

	// 캔버스 밖으로 조금 나간 위치까지만 받는다
	private static final double LIMIT = 10_000;

	private final Map<UUID, Map<UUID, Entry>> projects = new ConcurrentHashMap<>();

	private final Clock clock;

	private final ProjectAccess access;

	public Presence(Clock clock, ProjectAccess access) {
		this.clock = clock;
		this.access = access;
	}

	// 슬라이드가 없으면 편집 화면을 떠난 것이다. 같은 사람의 여러 탭은 마지막 위치 하나로 본다
	public void update(Actor actor, UUID projectId, UUID slideId, Double x, Double y) {
		access.requireMember(projectId, actor.userId());
		if (slideId == null) {
			Map<UUID, Entry> users = projects.get(projectId);
			if (users != null) {
				users.remove(actor.userId());
			}
			return;
		}
		check("x", x);
		check("y", y);
		projects.computeIfAbsent(projectId, key -> new ConcurrentHashMap<>())
			.put(actor.userId(), new Entry(new Cursor(actor.userId(), slideId, x, y), clock.instant()));
	}

	// 나를 뺀 팀원의 위치. 순서를 고정해 바뀌었는지 목록 비교로 알 수 있게 한다
	public List<Cursor> others(UUID projectId, UUID userId) {
		Map<UUID, Entry> users = projects.get(projectId);
		if (users == null) {
			return List.of();
		}
		Instant expired = clock.instant().minus(TTL);
		users.values().removeIf(entry -> entry.at().isBefore(expired));
		if (users.isEmpty()) {
			projects.remove(projectId, users);
		}
		return users.values()
			.stream()
			.map(Entry::cursor)
			.filter(cursor -> !cursor.userId().equals(userId))
			.sorted(Comparator.comparing(Cursor::userId))
			.toList();
	}

	private static void check(String field, Double value) {
		if (value == null || !Double.isFinite(value) || Math.abs(value) > LIMIT) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, field, "INVALID", "커서 위치를 확인해 주세요.");
		}
	}

}
