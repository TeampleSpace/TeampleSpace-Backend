package com.teamplespace.materials.service;

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
	void loginWallsAndNearlyEmptyPagesAreNotReadable() {
		// 실제 인스타그램 프로필에서 뽑힌 본문과 같은 모양
		assertThat(MaterialAnalysis.readable("Log In (https://www.instagram.com/accounts/login/)\n\nSign Up (https://www.instagram.com/accounts/emailsignup/)\n\nShow more posts from natgeo")).isFalse();
		assertThat(MaterialAnalysis.readable("사진과 동영상을 보려면 로그인하세요.\n\n계정이 없으신가요? 가입하기")).isFalse();
		// 주소만 길고 글이 없는 페이지
		assertThat(MaterialAnalysis.readable("https://example.com/" + "a".repeat(300) + "\n짧은 글")).isFalse();
		String paragraph = "여러 대학이 다회용컵 반납함을 늘리고 반납 동선을 줄이자 이용이 늘었다. ";
		assertThat(MaterialAnalysis.readable(paragraph.repeat(4))).isTrue();
		// 짧은 글에 로그인 안내가 섞이면 읽지 못한 것으로 보고, 본문이 충분하면 안내가 있어도 읽은 것으로 본다
		assertThat(MaterialAnalysis.readable(paragraph.repeat(4) + "\n로그인")).isFalse();
		assertThat(MaterialAnalysis.readable("Log inSign up\n" + paragraph.repeat(20))).isTrue();
		// 단어 안에 든 글자는 로그인 안내로 보지 않는다
		assertThat(MaterialAnalysis.readable("Our catalog includes signal processing notes. ".repeat(4))).isTrue();
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
