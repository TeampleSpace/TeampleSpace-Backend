package com.teamplespace.collaboration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.teamplespace.platform.security.Actor;
import com.teamplespace.support.MutableClock;
import com.teamplespace.workspace.service.ProjectAccess;

class ViewersTests {

	final MutableClock clock = new MutableClock();

	final Viewers viewers = new Viewers(clock, mock(ProjectAccess.class));

	final UUID project = UUID.randomUUID();

	final Actor owner = new Actor(UUID.randomUUID(), UUID.randomUUID());

	final Actor member = new Actor(UUID.randomUUID(), UUID.randomUUID());

	@Test
	void othersSeeLatestTabUntilItExpires() {
		viewers.view(owner, project, "materials", "a", null);
		viewers.view(owner, project, "slides", "b", null);
		viewers.view(member, project, "outline", null, null);

		assertThat(viewers.others(project, member.userId()))
			.containsExactly(new Viewers.Viewer(owner.userId(), "slides"));

		// 이전 창의 떠남이 늦게 와도 마지막으로 알린 창은 남는다
		viewers.view(owner, project, null, "a", null);
		assertThat(viewers.others(project, member.userId())).hasSize(1);

		// 알림이 끊기면 일정 시간 뒤 빠진다
		clock.advance(Viewers.TTL.minus(Duration.ofSeconds(1)));
		viewers.view(member, project, "outline", null, null);
		clock.advance(Duration.ofSeconds(2));
		assertThat(viewers.others(project, member.userId())).isEmpty();
		assertThat(viewers.others(project, owner.userId()))
			.containsExactly(new Viewers.Viewer(member.userId(), "outline"));

		// 같은 창이 떠나면 바로 빠진다
		viewers.view(owner, project, "design", "c", null);
		viewers.view(owner, project, null, "c", null);
		assertThat(viewers.others(project, member.userId())).isEmpty();
	}

	@Test
	void lateNoticesFromTheSameViewDoNotOverrideNewerOnes() {
		// 떠남이 먼저 처리되고 이전 탭 알림이 늦게 와도 다시 보이지 않는다
		viewers.view(owner, project, null, "a", 2L);
		viewers.view(owner, project, "materials", "a", 1L);
		assertThat(viewers.others(project, member.userId())).isEmpty();

		// 탭을 옮긴 뒤 이전 탭 알림이 늦게 와도 새 탭이 남는다
		viewers.view(member, project, "design", "b", 4L);
		viewers.view(member, project, "outline", "b", 3L);
		assertThat(viewers.others(project, owner.userId()))
			.containsExactly(new Viewers.Viewer(member.userId(), "design"));

		// 같은 창이 다시 들어오면 더 큰 순번으로 보인다
		viewers.view(owner, project, "slides", "a", 3L);
		assertThat(viewers.others(project, member.userId()))
			.containsExactly(new Viewers.Viewer(owner.userId(), "slides"));
	}

	@Test
	void lateNoticeFromAClosedWindowDoesNotOverrideAnotherOpenWindow() {
		// A 창 알림, B 창 알림, A 창 떠남 뒤에 늦게 온 A 창 알림은 버리고 B 창 탭을 보여 준다
		viewers.view(owner, project, "materials", "a", 1L);
		viewers.view(owner, project, "design", "b", 1L);
		viewers.view(owner, project, null, "a", 3L);
		viewers.view(owner, project, "outline", "a", 2L);
		assertThat(viewers.others(project, member.userId()))
			.containsExactly(new Viewers.Viewer(owner.userId(), "design"));

		// 남은 창도 떠나면 빠진다
		viewers.view(owner, project, null, "b", 2L);
		assertThat(viewers.others(project, member.userId())).isEmpty();
	}

	@Test
	void lateNoticeWithoutViewIdDoesNotReturnAfterLeaving() {
		viewers.view(owner, project, "materials", null, 1L);
		viewers.view(owner, project, null, null, 3L);
		viewers.view(owner, project, "outline", null, 2L);
		assertThat(viewers.others(project, member.userId())).isEmpty();
	}

	@Test
	void expiryCleanupDoesNotDropConcurrentUpdates() throws Exception {
		// 만료 정리와 새 알림이 겹쳐도 마지막 알림은 남는다
		for (int round = 0; round < 200; round++) {
			UUID room = UUID.randomUUID();
			viewers.view(owner, room, "materials", null, null);
			clock.advance(Viewers.TTL.plus(Duration.ofSeconds(1)));
			Thread cleaner = new Thread(() -> viewers.others(room, member.userId()));
			cleaner.start();
			viewers.view(member, room, "outline", null, null);
			cleaner.join();
			assertThat(viewers.others(room, owner.userId()))
				.containsExactly(new Viewers.Viewer(member.userId(), "outline"));
		}
	}

}
