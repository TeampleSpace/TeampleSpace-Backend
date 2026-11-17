package com.teamplespace.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// 기다리지 않고 서버 시각을 옮기는 테스트용 시계
public class MutableClock extends Clock {

	private volatile Instant now = Instant.parse("2026-11-18T01:00:00Z");

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}

}
