package com.teamplespace.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import tools.jackson.databind.JsonNode;

// 문서의 글꼴 이름과 굵기를 글꼴 파일과 PowerPoint 글꼴 이름으로 바꾼다
record ExportFonts(String family, boolean bold, String fileName) {

	private static final Map<Integer, String> WEIGHT_NAMES = Map.of(100, "Thin", 200, "ExtraLight", 300, "Light", 400,
			"Regular", 500, "Medium", 600, "SemiBold", 700, "Bold", 800, "ExtraBold", 900, "Black");

	// PowerPoint는 굵기를 숫자로 받지 못해 보통과 굵게 외의 굵기는 글꼴 이름으로 고른다
	static ExportFonts of(JsonNode style) {
		String family = style.get("fontFamily").asString();
		int weight = style.get("fontWeight").asInt();
		String weightName = WEIGHT_NAMES.get(weight);
		String fileName = family + "-" + weightName + ".ttf";
		if (weight == 400) {
			return new ExportFonts(family, false, fileName);
		}
		if (weight == 700) {
			return new ExportFonts(family, true, fileName);
		}
		return new ExportFonts(family + " " + weightName, false, fileName);
	}

	// 글꼴 파일 폴더. 기본값은 macOS 사용자 글꼴 폴더
	static Path directory() {
		String configured = System.getenv("EXPORT_FONT_DIR");
		return configured != null ? Path.of(configured) : Path.of(System.getProperty("user.home"), "Library", "Fonts");
	}

	static boolean available(Path directory) {
		return Files.isRegularFile(directory.resolve("Pretendard-Regular.ttf"));
	}

}
