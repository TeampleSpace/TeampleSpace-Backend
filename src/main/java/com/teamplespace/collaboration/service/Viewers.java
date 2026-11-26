package com.teamplespace.collaboration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

	// 창마다 다른 viewId로 어느 창의 알림인지 구분한다
	private record Entry(Viewer viewer, String viewId, Instant at) {
	}

	static final Set<String> TABS = Set.of("overview", "materials", "outline", "design", "slides", "settings");

	// 이 시간 동안 알림이 오지 않으면 화면을 떠난 것으로 본다
	static final Duration TTL = Duration.ofSeconds(20);

	private final Map<UUID, Map<UUID, Entry>> projects = new ConcurrentHashMap<>();

	private final Clock clock;

	private final ProjectAccess access;

	public Viewers(Clock clock, ProjectAccess access) {
		this.clock = clock;
		this.access = access;
	}

	// 탭이 없으면 프로젝트 화면을 떠난 것이다. 같은 사람의 여러 창은 마지막 알림 하나로 본다
	// 떠난 창이 마지막으로 알린 창이 아니면 지우지 않는다. 새로고침 때 이전 페이지의 떠남이 늦게 도착해도 새 페이지가 남는다
	public void view(Actor actor, UUID projectId, String tab, String viewId) {
		access.requireMember(projectId, actor.userId());
		if (viewId != null && (viewId.isBlank() || viewId.length() > 64)) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "viewId", "INVALID", "창 식별자를 확인해 주세요.");
		}
		if (tab != null && !TABS.contains(tab)) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "tab", "INVALID", "탭 이름을 확인해 주세요.");
		}
		// 프로젝트별 갱신과 빈 목록 정리를 같은 원자적 연산으로 처리해 정리 중인 목록에 쓴 알림이 사라지지 않게 한다
		projects.compute(projectId, (key, users) -> {
			Map<UUID, Entry> next = users != null ? users : new ConcurrentHashMap<>();
			if (tab != null) {
				next.put(actor.userId(), new Entry(new Viewer(actor.userId(), tab), viewId, clock.instant()));
			}
			else {
				next.computeIfPresent(actor.userId(),
						(user, entry) -> viewId == null || viewId.equals(entry.viewId()) ? null : entry);
			}
			return next.isEmpty() ? null : next;
		});
	}

	// 나를 뺀 팀원의 탭. 순서를 고정해 바뀌었는지 목록 비교로 알 수 있게 한다
	public List<Viewer> others(UUID projectId, UUID userId) {
		Instant expired = clock.instant().minus(TTL);
		Map<UUID, Entry> users = projects.computeIfPresent(projectId, (key, current) -> {
			current.values().removeIf(entry -> entry.at().isBefore(expired));
			return current.isEmpty() ? null : current;
		});
		if (users == null) {
			return List.of();
		}
		return List.copyOf(users.values())
			.stream()
			.map(Entry::viewer)
			.filter(viewer -> !viewer.userId().equals(userId))
			.sorted(Comparator.comparing(Viewer::userId))
			.toList();
	}

}
