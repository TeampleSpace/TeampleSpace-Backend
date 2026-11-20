package com.teamplespace.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

class ProjectFontTests {

	@Test
	void acceptsTrueTypeAndOpenTypeDirectories() {
		assertThat(ProjectFontData.format(directory(false))).isEqualTo("ttf");
		assertThat(ProjectFontData.format(directory(true))).isEqualTo("otf");
	}

	@Test
	void rejectsTruncatedFilesAndOverlappingOrOutOfRangeTables() {
		byte[] font = directory(false);
		assertRejected(Arrays.copyOf(font, 40));
		byte[] outside = font.clone();
		ByteBuffer.wrap(outside).putInt(20, -4);
		assertRejected(outside);
		byte[] overlap = font.clone();
		var offsets = ByteBuffer.wrap(overlap);
		offsets.putInt(36, offsets.getInt(20));
		assertRejected(overlap);
		byte[] wrongHeader = font.clone();
		ByteBuffer.wrap(wrongHeader).putInt(0, 0x74746366);
		assertRejected(wrongHeader);
		assertRejected("html".getBytes(StandardCharsets.US_ASCII));
	}

	@Test
	void oversizedUploadFailsBeforeCheckingOrWritingBytes() {
		var fonts = new ProjectFontService(null, null);
		byte[] bytes = new byte[ProjectFontService.MAX_FILE_BYTES + 1];
		assertThatThrownBy(() -> fonts.upload(UUID.randomUUID(), UUID.randomUUID(), "large.ttf", bytes))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.code()).isEqualTo(ErrorCode.UPLOAD_TOO_LARGE));
	}

	private static void assertRejected(byte[] bytes) {
		assertThatThrownBy(() -> ProjectFontData.format(bytes)).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	// 테이블 디렉터리와 머리글 검사에 쓰는 데이터
	static byte[] directory(boolean openType) {
		String[] names = {"head", "cmap", "hhea", "hmtx", "maxp", "name", "OS/2", "post",
				openType ? "CFF " : "glyf", openType ? "DSIG" : "loca"};
		int[] lengths = {54, 14, 36, 4, 6, 6, 78, 32, 4, 4};
		ByteBuffer font = ByteBuffer.allocate(512);
		font.putInt(openType ? 0x4f54544f : 0x00010000).putShort((short) names.length).position(12);
		int offset = 12 + 16 * names.length;
		for (int i = 0; i < names.length; i++) {
			font.put(names[i].getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(offset).putInt(lengths[i]);
			switch (names[i]) {
				case "head" -> font.putInt(offset + 12, 0x5f0f3cf5).putShort(offset + 18, (short) 1000);
				case "cmap" -> font.putShort(offset + 2, (short) 1).putInt(offset + 8, 12);
				case "hhea" -> font.putShort(offset + 34, (short) 1);
				case "maxp" -> font.putShort(offset + 4, (short) 1);
				default -> { }
			}
			offset += (lengths[i] + 3) & ~3;
		}
		return Arrays.copyOf(font.array(), offset);
	}
}
