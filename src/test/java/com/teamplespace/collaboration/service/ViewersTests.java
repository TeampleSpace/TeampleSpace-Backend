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
		viewers.view(owner, project, "materials", "a");
		viewers.view(owner, project, "slides", "b");
		viewers.view(member, project, "outline", null);

		assertThat(viewers.others(project, member.userId()))
			.containsExactly(new Viewers.Viewer(owner.userId(), "slides"));

		// 이전 창의 떠남이 늦게 와도 마지막으로 알린 창은 남는다
		viewers.view(owner, project, null, "a");
		assertThat(viewers.others(project, member.userId())).hasSize(1);

		// 알림이 끊기면 일정 시간 뒤 빠진다
		clock.advance(Viewers.TTL.minus(Duration.ofSeconds(1)));
		viewers.view(member, project, "outline", null);
		clock.advance(Duration.ofSeconds(2));
		assertThat(viewers.others(project, member.userId())).isEmpty();
		assertThat(viewers.others(project, owner.userId()))
			.containsExactly(new Viewers.Viewer(member.userId(), "outline"));

		// 같은 창이 떠나면 바로 빠진다
		viewers.view(owner, project, "design", "c");
		viewers.view(owner, project, null, "c");
		assertThat(viewers.others(project, member.userId())).isEmpty();
	}

}
