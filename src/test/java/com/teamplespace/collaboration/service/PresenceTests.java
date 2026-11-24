package com.teamplespace.collaboration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.support.MutableClock;
import com.teamplespace.workspace.service.ProjectAccess;

class PresenceTests {

	final MutableClock clock = new MutableClock();

	final ProjectAccess access = mock(ProjectAccess.class);

	final Presence presence = new Presence(clock, access);

	final UUID project = UUID.randomUUID();

	final UUID slide = UUID.randomUUID();

	final Actor owner = new Actor(UUID.randomUUID(), UUID.randomUUID());

	final Actor member = new Actor(UUID.randomUUID(), UUID.randomUUID());

	@Test
	void othersSeeLatestPositionUntilItExpiresOrTheyLeave() {
		presence.update(owner, project, slide, 10.0, 20.0);
		presence.update(owner, project, slide, 30.0, 40.0);
		presence.update(member, project, slide, 1.0, 2.0);

		// 내 커서는 빼고 같은 사람은 마지막 위치 하나만 본다
		assertThat(presence.others(project, member.userId()))
			.containsExactly(new Presence.Cursor(owner.userId(), slide, 30.0, 40.0));
		assertThat(presence.others(project, owner.userId()))
			.containsExactly(new Presence.Cursor(member.userId(), slide, 1.0, 2.0));

		// 슬라이드 없이 보내면 떠난 것이다
		presence.update(member, project, null, null, null);
		assertThat(presence.others(project, owner.userId())).isEmpty();

		// 새 위치가 오지 않으면 일정 시간 뒤 빠진다
		clock.advance(Presence.TTL.plus(Duration.ofSeconds(1)));
		assertThat(presence.others(project, member.userId())).isEmpty();
	}

	@Test
	void rejectsInvalidPositionsAndNonMembers() {
		for (Double value : new Double[] { null, Double.NaN, Double.POSITIVE_INFINITY, 10_001.0 }) {
			assertThatThrownBy(() -> presence.update(owner, project, slide, value, 0.0))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
		}
		Actor stranger = new Actor(UUID.randomUUID(), UUID.randomUUID());
		doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).requireMember(project, stranger.userId());
		assertThatThrownBy(() -> presence.update(stranger, project, slide, 1.0, 1.0)).isInstanceOf(ApiException.class);
		assertThat(presence.others(project, owner.userId())).isEmpty();
	}

}
