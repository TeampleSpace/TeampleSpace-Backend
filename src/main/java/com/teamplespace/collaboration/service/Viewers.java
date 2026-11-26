package com.teamplespace.collaboration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.workspace.service.ProjectAccess;

// 팀원이 지금 보고 있는 프로젝트 탭. 커서 위치처럼 잠깐 쓰고 버리는 값이라 DB에 쌓지 않는다
// ponytail: 서버 한 대의 메모리에 둔다. 서버를 여러 대로 늘리면 공유 저장소나 메시지 브로커로 옮긴다
@Component
public class Viewers {

	public record Viewer(UUID userId, String tab) {
	}

	// 창 하나의 마지막 알림. 떠난 창은 tab 없이 순번과 함께 남겨 늦게 도착한 그 창의 이전 알림을 버린다
	private record View(String tab, Long seq, long order, Instant at) {
	}

	static final Set<String> TABS = Set.of("overview", "materials", "outline", "design", "slides", "settings");

	// 이 시간 동안 알림이 오지 않으면 화면을 떠난 것으로 본다
	static final Duration TTL = Duration.ofSeconds(20);

	// 프로젝트, 사용자, 창 식별자 순서로 둔다. 창 식별자가 없는 알림은 빈 문자열 창으로 본다
	private final Map<UUID, Map<UUID, Map<String, View>>> projects = new ConcurrentHashMap<>();

	// 같은 사람의 여러 창 가운데 마지막으로 알린 창을 고를 때 쓴다
	private final AtomicLong order = new AtomicLong();

	private final Clock clock;

	private final ProjectAccess access;

	public Viewers(Clock clock, ProjectAccess access) {
		this.clock = clock;
		this.access = access;
	}

	// 탭이 없으면 그 창이 프로젝트 화면을 떠난 것이다. 같은 사람은 아직 열린 창 가운데 마지막으로 알린 탭으로 보인다
	// 같은 창의 알림은 seq가 더 큰 것만 받는다. 늦게 도착한 이전 알림이 최신 상태를 덮지 않는다
	public void view(Actor actor, UUID projectId, String tab, String viewId, Long seq) {
		access.requireMember(projectId, actor.userId());
		if (viewId != null && (viewId.isBlank() || viewId.length() > 64)) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "viewId", "INVALID", "창 식별자를 확인해 주세요.");
		}
		if (seq != null && seq < 0) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "seq", "INVALID", "알림 순번을 확인해 주세요.");
		}
		if (tab != null && !TABS.contains(tab)) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "tab", "INVALID", "탭 이름을 확인해 주세요.");
		}
		String key = viewId == null ? "" : viewId;
		// 프로젝트별 갱신과 빈 목록 정리를 같은 원자적 연산으로 처리해 정리 중인 목록에 쓴 알림이 사라지지 않게 한다
		projects.compute(projectId, (project, users) -> {
			Map<UUID, Map<String, View>> current = users != null ? users : new ConcurrentHashMap<>();
			Map<String, View> views = current.computeIfAbsent(actor.userId(), user -> new ConcurrentHashMap<>());
			View last = views.get(key);
			if (last != null && last.seq() != null && seq != null && seq <= last.seq()) {
				return current;
			}
			if (tab == null && viewId == null) {
				// 창을 구분하지 않은 떠남은 이 사람의 표시를 모두 지운다
				views.clear();
			}
			else {
				views.put(key, new View(tab, seq, order.incrementAndGet(), clock.instant()));
			}
			if (views.isEmpty()) {
				current.remove(actor.userId());
			}
			return current.isEmpty() ? null : current;
		});
	}

	// 나를 뺀 팀원의 탭. 순서를 고정해 바뀌었는지 목록 비교로 알 수 있게 한다
	public List<Viewer> others(UUID projectId, UUID userId) {
		Instant expired = clock.instant().minus(TTL);
		List<Viewer> found = new ArrayList<>();
		projects.computeIfPresent(projectId, (project, current) -> {
			current.values().forEach(views -> views.values().removeIf(view -> view.at().isBefore(expired)));
			current.values().removeIf(Map::isEmpty);
			current.forEach((user, views) -> {
				if (user.equals(userId)) {
					return;
				}
				views.values()
					.stream()
					.filter(view -> view.tab() != null)
					.max(Comparator.comparingLong(View::order))
					.ifPresent(view -> found.add(new Viewer(user, view.tab())));
			});
			return current.isEmpty() ? null : current;
		});
		found.sort(Comparator.comparing(Viewer::userId));
		return found;
	}

}
