package com.teamplespace.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class MaterialExcerptTests {

	@Test
	void blankLinesWithinTheBodyLimitAreProcessedWithoutQuadraticScanning() {
		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			assertThat(MaterialService.excerpt("\n".repeat(100_000))).isNull();
			assertThat(MaterialService.excerpt("\r\n".repeat(40_000) + "\t## 마지막 내용"))
				.isEqualTo("마지막 내용");
		});
	}

	@Test
	void onlyHorizontalIndentationAndMarkerSpacingAreRemoved() {
		assertThat(MaterialService.excerpt("  ## 제목\n\t- 항목\n  1. 다음 항목\n> 인용"))
			.isEqualTo("제목 항목 다음 항목 인용");
		assertThat(MaterialService.excerpt("##\n그다음 줄"))
			.isEqualTo("## 그다음 줄");
	}
}
