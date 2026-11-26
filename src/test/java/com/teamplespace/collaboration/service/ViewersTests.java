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
		viewers.view(owner, project, "materials");
		viewers.view(owner, project, "slides");
		viewers.view(member, project, "outline");

		assertThat(viewers.others(project, member.userId()))
			.containsExactly(new Viewers.Viewer(owner.userId(), "slides"));

		// 알림이 끊기면 일정 시간 뒤 빠진다
		clock.advance(Viewers.TTL.minus(Duration.ofSeconds(1)));
		viewers.view(member, project, "outline");
		clock.advance(Duration.ofSeconds(2));
		assertThat(viewers.others(project, member.userId())).isEmpty();
		assertThat(viewers.others(project, owner.userId()))
			.containsExactly(new Viewers.Viewer(member.userId(), "outline"));
	}

}
