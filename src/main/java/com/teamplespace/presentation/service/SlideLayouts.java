package com.teamplespace.presentation.service;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 생성한 글을 1280 x 720 캔버스의 요소로 배치한다. 글자 수 상한은 각 글상자에 두 줄로 들어가는 길이다
final class SlideLayouts {

	record Section(String title, List<String> points) {
	}

	record Deck(String title, String subtitle, List<Section> slides, Section closing) {
	}

	record Theme(String font, String background, String heading, String body) {

		// 어두운 배경에는 밝은 본문색을 쓴다
		static Theme of(String font, String background, String heading) {
			int rgb = Integer.parseInt(background.substring(1), 16);
			double luminance = (0.2126 * (rgb >> 16) + 0.7152 * (rgb >> 8 & 0xFF) + 0.0722 * (rgb & 0xFF)) / 255;
			return new Theme(font, background, heading, luminance < 0.5 ? "#F5F7FA" : "#1F2933");
		}
	}

	record Element(String type, int x, int y, int width, int height, ObjectNode content, ObjectNode style) {
	}

	record Slide(String layout, List<Element> elements) {
	}

	static final int WIDTH = 1280;
	static final int HEIGHT = 720;
	static final int DECK_TITLE_MAX = 40;
	static final int SUBTITLE_MAX = 80;
	static final int SLIDE_TITLE_MAX = 40;
	static final int POINT_MAX = 90;
	static final int POINTS_MAX = 5;

	private static final int MARGIN = 80;
	private static final int POINT_TOP = 206;
	private static final int POINT_GAP = 84;

	private final ObjectMapper json;
	private final Theme theme;

	private SlideLayouts(ObjectMapper json, Theme theme) {
		this.json = json;
		this.theme = theme;
	}

	// sources는 본문 슬라이드마다 출처 문구이고 없으면 null이다
	static List<Slide> build(ObjectMapper json, Deck deck, Theme theme, List<String> sources) {
		var layouts = new SlideLayouts(json, theme);
		List<Slide> slides = new ArrayList<>();
		slides.add(layouts.cover(deck.title(), deck.subtitle()));
		for (int index = 0; index < deck.slides().size(); index++) {
			slides.add(layouts.content("content", deck.slides().get(index), sources.get(index)));
		}
		slides.add(layouts.content("summary", deck.closing(), null));
		return slides;
	}

	private Slide cover(String title, String subtitle) {
		List<Element> elements = new ArrayList<>();
		elements.add(rect(0, 0, WIDTH, HEIGHT, theme.background()));
		elements.add(rect(MARGIN, 232, 96, 10, theme.heading()));
		elements.add(text(MARGIN, 268, 1120, 160, title, 56, 700, theme.heading(), 1.3));
		elements.add(text(MARGIN, 452, 1120, 80, subtitle, 26, 400, theme.body(), 1.4));
		return new Slide("title", elements);
	}

	private Slide content(String layout, Section section, String source) {
		List<Element> elements = new ArrayList<>();
		elements.add(rect(0, 0, WIDTH, HEIGHT, theme.background()));
		elements.add(text(MARGIN, 56, 1120, 104, section.title(), 40, 700, theme.heading(), 1.3));
		elements.add(rect(MARGIN, 172, 1120, 3, theme.heading()));
		for (int index = 0; index < section.points().size(); index++) {
			int top = POINT_TOP + index * POINT_GAP;
			elements.add(rect(88, top + 12, 10, 10, theme.heading()));
			elements.add(text(116, top, 1084, 76, section.points().get(index), 24, 400, theme.body(), 1.45));
		}
		if (source != null) {
			elements.add(text(MARGIN, 652, 1120, 32, source, 16, 400, theme.body(), 1.4));
		}
		return new Slide(layout, elements);
	}

	private Element rect(int x, int y, int width, int height, String fill) {
		ObjectNode style = json.createObjectNode().put("fill", fill).put("stroke", (String) null).put("strokeWidth", 0);
		return new Element("shape", x, y, width, height, json.createObjectNode().put("shape", "rect"), style);
	}

	private Element text(int x, int y, int width, int height, String value, int size, int weight, String color,
			double lineHeight) {
		ObjectNode style = json.createObjectNode().put("fontFamily", theme.font()).put("fontSize", size)
			.put("fontWeight", weight).put("color", color).put("align", "left").put("lineHeight", lineHeight);
		return new Element("text", x, y, width, height, json.createObjectNode().put("text", value), style);
	}

}
