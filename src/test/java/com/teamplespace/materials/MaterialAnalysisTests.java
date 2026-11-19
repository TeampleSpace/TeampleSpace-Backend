package com.teamplespace.materials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class MaterialAnalysisTests {

	@Test
	void cleansControlsAndLineEndsWithoutChangingParagraphIndentation() {
		assertThat(MaterialAnalysis.clean(" \t첫\u0000 줄 \t\r\n\t둘째\u007f 줄\t \n \t\n 끝 "))
				.isEqualTo("첫 줄\n\t둘째 줄\n\n 끝");
		assertThat(MaterialAnalysis.clean(" \t\r\n\u0000\u007f ")).isEmpty();
	}

	@Test
	void longWhitespaceRunsAreCleanedWithinTheProcessingBudget() {
		String spaces = " \t".repeat(1_000_000);
		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
			assertThat(MaterialAnalysis.clean("x" + spaces + "y")).isEqualTo("x" + spaces + "y");
			assertThat(MaterialAnalysis.clean("x" + spaces + "\ny")).isEqualTo("x\ny");
		});
	}
}
