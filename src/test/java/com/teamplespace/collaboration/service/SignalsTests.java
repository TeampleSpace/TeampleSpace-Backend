package com.teamplespace.collaboration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.teamplespace.support.MutableClock;

class SignalsTests {

	final MutableClock clock = new MutableClock();

	final Signals signals = new Signals(clock);

	final UUID project = UUID.randomUUID();

	@Test
	void concurrentPublishesKeepEveryLiveSignalAndDropOnlyExpiredOnes() throws Exception {
		for (int round = 0; round < 100; round++) {
			signals.publish(project, "old", "x");
			clock.advance(Signals.KEEP.plus(Duration.ofSeconds(1)));
			long before = signals.last();
			List<Thread> threads = new ArrayList<>();
			for (int index = 0; index < 4; index++) {
				threads.add(Thread.startVirtualThread(() -> signals.publish(project, "memo.updated", "y")));
			}
			for (Thread thread : threads) {
				thread.join();
			}
			// 만료된 신호만 빠지고 동시에 넣은 신호는 모두 순서대로 남는다
			assertThat(signals.after(before, project)).extracting(Signals.Signal::type).containsOnly("memo.updated").hasSize(4);
			assertThat(signals.after(0, project)).extracting(Signals.Signal::type).doesNotContain("old");
			clock.advance(Signals.KEEP.plus(Duration.ofSeconds(1)));
		}
	}

}
