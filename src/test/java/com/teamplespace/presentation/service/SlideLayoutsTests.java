package com.teamplespace.presentation.service;

import static com.teamplespace.presentation.service.TemplatePreviews.sample;
import static com.teamplespace.presentation.service.TemplatePreviews.sources;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.teamplespace.presentation.service.SlideLayouts.Deck;
import com.teamplespace.presentation.service.SlideLayouts.Element;
import com.teamplespace.presentation.service.SlideLayouts.Point;
import com.teamplespace.presentation.service.SlideLayouts.Section;
import com.teamplespace.presentation.service.SlideLayouts.Slide;
import com.teamplespace.presentation.service.SlideLayouts.Theme;

import tools.jackson.databind.ObjectMapper;

// 모든 템플릿과 직접 입력 계열로 예시 글과 가장 긴 글을 배치해
// 글상자가 캔버스와 놓인 면 안에 들고 서로 겹치지 않으며 읽을 수 있는 대비인지 본다
// 결과는 build/slide-layouts에 남겨 브라우저로 그려 볼 수 있다
class SlideLayoutsTests {

	private final ObjectMapper json = new ObjectMapper();

	private static Map<String, Theme> themes() {
		Map<String, Theme> themes = new LinkedHashMap<>();
		SlideLayouts.TEMPLATES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			SlideLayouts.Look look = entry.getValue();
			String display = look.style().equals("pop") ? "Gmarket Sans TTF" : "Pretendard";
			themes.put(entry.getKey(), Theme.of("Pretendard", display, look));
		});
		for (String style : SlideLayouts.STYLES) {
			themes.put("custom-" + style, Theme.of("ProjectFont-1", "ProjectFont-1", SlideLayouts.custom(style, "#12B886")));
		}
		themes.put("custom-white", Theme.of("Pretendard", "Pretendard", SlideLayouts.custom("focus", "#FFFFFF")));
		themes.put("custom-yellow", Theme.of("Pretendard", "Pretendard", SlideLayouts.custom("swiss", "#FFE14D")));
		return themes;
	}

	@Test
	void everyDesignKeepsTextInsideAndReadable() throws Exception {
		Path folder = Path.of("build", "slide-layouts");
		Files.createDirectories(folder);
		List<String> problems = new ArrayList<>();
		for (var theme : themes().entrySet()) {
			for (var deck : Map.of("sample", sample(), "longest", longest(), "few", few(), "unspaced", unspaced(90),
					"unspaced-mid", unspaced(50)).entrySet()) {
				List<Slide> slides = SlideLayouts.build(json, deck.getValue(), theme.getValue(), sources(deck.getValue()));
				for (int index = 0; index < slides.size(); index++) {
					String name = theme.getKey() + " " + deck.getKey() + " " + index + " " + slides.get(index).layout();
					check(name, slides.get(index), problems);
					footer(name, slides, index, deck.getValue(), problems);
				}
				Files.writeString(folder.resolve(theme.getKey() + "-" + deck.getKey() + ".json"),
						json.writeValueAsString(slides));
			}
		}
		assertThat(problems).as(problems.size() + "건").isEmpty();
	}

	@Test
	void sampleDeckUsesEachFamilyLayout() {
		for (var theme : themes().entrySet()) {
			List<String> layouts = SlideLayouts.build(json, sample(), theme.getValue(), sources(sample())).stream()
				.map(Slide::layout).toList();
			assertThat(layouts).as(theme.getKey())
				.containsExactly("title", "agenda", "statement", "cards", "compare", "process", "list", "summary");
		}
	}

	@Test
	void picksLayoutByContentAndAvoidsRepeats() {
		Theme theme = Theme.of("Pretendard", "Pretendard", SlideLayouts.TEMPLATES.get("blue-focus"));
		List<Section> sections = List.of(section("compare", 3), section("cards", 3), section("cards", 3),
				section("process", 2), section("statement", 4));
		Deck deck = new Deck("제목", "소개", "focus", sections, section("list", 3));
		List<String> layouts = SlideLayouts.build(json, deck, theme, sources(deck)).stream().map(Slide::layout).toList();
		assertThat(layouts).containsExactly("title", "agenda", "cards", "list", "cards", "list", "cards", "summary");
	}

	@Test
	void agendaAndNumbersFollowOutlineItems() {
		Theme theme = Theme.of("Pretendard", "Pretendard", SlideLayouts.TEMPLATES.get("blue-focus"));
		List<Section> sections = new ArrayList<>();
		for (String title : List.of("첫 항목", "첫 항목 이어서", "둘째 항목", "셋째 항목")) {
			sections.add(new Section("list", title, "", "", List.of(point("요점", "설명"), point("다음", "설명"))));
		}
		Deck deck = new Deck("제목", "소개", "focus", sections, section("list", 2), List.of(1, 1, 2, 3));
		List<String> sources = new ArrayList<>(List.of("출처: 첫 자료", "출처: 둘째 자료"));
		sources.add(null);
		List<Slide> slides = SlideLayouts.build(json, deck, theme, sources);
		assertThat(slides).hasSize(7);
		assertThat(texts(slides.get(1))).contains("첫 항목", "둘째 항목", "셋째 항목").doesNotContain("첫 항목 이어서");
		assertThat(texts(slides.get(2))).contains("SECTION 01", "출처: 첫 자료");
		assertThat(texts(slides.get(3))).contains("SECTION 01", "출처: 첫 자료");
		assertThat(texts(slides.get(4))).contains("SECTION 02", "출처: 둘째 자료");
		assertThat(texts(slides.get(5))).contains("SECTION 03").doesNotContain("출처: 둘째 자료");
	}

	private static List<String> texts(Slide slide) {
		return slide.elements().stream().filter(element -> element.type().equals("text"))
			.map(element -> element.content().get("text").asString()).toList();
	}

	// 스튜디오 템플릿은 공통 계열이 아니라 피그마 전용 배치로 만든다
	@Test
	void studioTemplatesUseTheirOwnDesigns() {
		assertThat(SlideLayouts.TEMPLATES.get("burgundy-editorial").style()).isEqualTo("burgundy");
		assertThat(SlideLayouts.TEMPLATES.get("tech-pitch").style()).isEqualTo("signal");
		assertThat(SlideLayouts.TEMPLATES.get("open-campus").style()).isEqualTo("campus");
		assertThat(SlideLayouts.TEMPLATES.get("field-notes").style()).isEqualTo("fieldnotes");
		Theme theme = Theme.of("Pretendard", "Pretendard", SlideLayouts.TEMPLATES.get("burgundy-editorial"));
		Slide agenda = SlideLayouts.build(json, sample(), theme, sources(sample())).get(1);
		assertThat(texts(agenda)).contains("Contents");
	}

	@Test
	void customStyleFollowsPrimaryColor() {
		Theme theme = Theme.of("Pretendard", "Pretendard", SlideLayouts.custom("corporate", "#7A1F2B"));
		assertThat(theme.style()).isEqualTo("corporate");
		assertThat(theme.accent()).isEqualTo("#7A1F2B");
		assertThat(SlideLayouts.custom("없는 계열", "#7A1F2B").style()).isEqualTo("focus");
	}

	@Test
	void measuresKoreanByWordsAndShrinksToFit() {
		assertThat(SlideLayouts.lines("가나다라마", 1000, 40, false, false)).isEqualTo(1);
		// 한 글자는 약 0.9em이라 200px에 20px 글자는 열 자 남짓 들어간다
		assertThat(SlideLayouts.lines("가나다라마 바사아자차 카타파하", 200, 20, false, false)).isEqualTo(2);
		assertThat(SlideLayouts.fit("가나다라마 바사아자차 카타파하", 200, 24, 12, 1, false, false).size()).isLessThan(24);
		// 제목 글꼴은 글자가 넓어 같은 폭에 덜 들어간다
		assertThat(SlideLayouts.lines("가나다라마 바사아자차", 190, 20, true, true)).isEqualTo(2);
	}

	@Test
	void breaksLinesAtWordsWithoutDanglingSyllables() {
		String text = SlideLayouts.broken("교내 쓰레기의 절반이 한 번 쓰고 버린 컵입니다", 1040, 52, true, false);
		assertThat(text).contains("\n").doesNotContain(" 한\n").doesNotContain("\n번");
		assertThat(text.replace("\n", " ")).isEqualTo("교내 쓰레기의 절반이 한 번 쓰고 버린 컵입니다");
	}

	private void check(String name, Slide slide, List<String> problems) {
		List<Element> elements = slide.elements();
		List<Element> texts = new ArrayList<>();
		for (int index = 0; index < elements.size(); index++) {
			Element element = elements.get(index);
			ElementRules.check(element.type(), element.content(), element.style(), null);
			if (!element.type().equals("text")) {
				continue;
			}
			String text = element.content().get("text").asString();
			if (element.x() < 0 || element.y() < 0 || element.x() + element.width() > SlideLayouts.WIDTH
					|| element.y() + element.height() > SlideLayouts.HEIGHT) {
				problems.add(name + " 캔버스 밖 " + text);
			}
			int size = element.style().get("fontSize").asInt();
			boolean bold = element.style().get("fontWeight").asInt() >= 600;
			boolean wide = element.style().get("fontFamily").asString().startsWith("Gmarket");
			double lineHeight = element.style().get("lineHeight").asDouble();
			int lines = SlideLayouts.lines(text, element.width(), size, bold, wide);
			if (lines != text.lines().count()) {
				problems.add(name + " 줄바꿈 " + text);
			}
			if (element.height() < (int) (lines * size * lineHeight)) {
				problems.add(name + " 글상자 높이 " + text);
			}
			for (Element other : texts) {
				if (overlaps(element, other)) {
					problems.add(name + " 겹침 " + text + " / " + other.content().get("text").asString());
				}
			}
			texts.add(element);
			Element under = below(elements, index);
			if (under.width() < SlideLayouts.WIDTH || under.height() < SlideLayouts.HEIGHT) {
				if (!inside(element, under)) {
					problems.add(name + " 면 밖으로 넘침 " + text);
				}
			}
			// 흐리게 깐 큰 번호는 장식이라 대비를 보지 않는다
			if (text.matches("\\d{2}") && size >= 48) {
				continue;
			}
			double needed = size >= 24 || bold && size >= 19 ? 3 : 4.5;
			double contrast = Theme.contrast(under.style().get("fill").asString(), element.style().get("color").asString());
			if (contrast < needed) {
				problems.add(name + " 대비 " + String.format("%.2f", contrast) + " " + text);
			}
		}
	}

	// 본문 슬라이드는 모두 쪽 번호를 적고 연결된 자료가 있으면 출처도 적는다
	private static void footer(String name, List<Slide> slides, int index, Deck deck, List<String> problems) {
		int first = slides.size() - deck.slides().size() - 1;
		if (index < first || index == slides.size() - 1) {
			return;
		}
		List<String> texts = slides.get(index).elements().stream().filter(e -> e.type().equals("text"))
			.map(e -> e.content().get("text").asString()).toList();
		if (!texts.contains(String.format("%02d", index + 1))) {
			problems.add(name + " 쪽 번호 없음");
		}
		String source = sources(deck).get(index - first);
		if (source != null && !texts.contains(source)) {
			problems.add(name + " 출처 없음");
		}
	}

	// 글상자 첫 글자 아래에 깔린 가장 위의 채운 도형. 1px 선과 격자는 제외한다
	private static Element below(List<Element> elements, int index) {
		Element text = elements.get(index);
		String align = text.style().get("align").asString();
		int probe = Math.min(text.width(), 24) / 2;
		int x = align.equals("left") ? text.x() + probe
				: align.equals("right") ? text.x() + text.width() - probe : text.x() + text.width() / 2;
		int y = text.y() + Math.min(text.height(), 24) / 2;
		for (int at = index - 1; at >= 0; at--) {
			Element shape = elements.get(at);
			if (shape.type().equals("shape") && !shape.style().get("fill").isNull() && shape.width() > 4
					&& shape.height() > 4 && x >= shape.x() && x <= shape.x() + shape.width() && y >= shape.y()
					&& y <= shape.y() + shape.height()) {
				return shape;
			}
		}
		throw new AssertionError("배경이 없는 글상자 " + text.content().get("text").asString() + " " + text.x() + "," + text.y());
	}

	private static boolean inside(Element text, Element shape) {
		return text.x() >= shape.x() - 1 && text.y() >= shape.y() - 1
				&& text.x() + text.width() <= shape.x() + shape.width() + 1
				&& text.y() + text.height() <= shape.y() + shape.height() + 1;
	}

	private static boolean overlaps(Element a, Element b) {
		return a.x() < b.x() + b.width() && b.x() < a.x() + a.width() && a.y() < b.y() + b.height()
				&& b.y() < a.y() + a.height();
	}

	private static Section section(String layout, int count) {
		List<Point> points = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			points.add(new Point("요점 " + index, "요점을 풀어 쓴 문장 " + index, ""));
		}
		return new Section(layout, "제목", "핵심 문장", "", points);
	}

	private static Point point(String head, String body) {
		return new Point(head, body, "");
	}

	// 띄어쓰기 없는 가장 긴 키워드와 설명
	private static Deck unspaced(int bodyLength) {
		String head = "가".repeat(SlideLayouts.HEAD_MAX);
		String body = "나".repeat(bodyLength);
		List<Section> sections = new ArrayList<>();
		String[] layouts = { "statement", "cards", "compare", "process", "list" };
		int[] counts = { 3, 4, 2, 5, 5 };
		for (int index = 0; index < layouts.length; index++) {
			List<Point> points = new ArrayList<>();
			for (int count = 0; count < counts[index]; count++) {
				points.add(new Point(head, body, "월평균삼백이십오명"));
			}
			sections.add(new Section(layouts[index], "다".repeat(SlideLayouts.SLIDE_TITLE_MAX), "짧은 핵심 문장", "", points));
			// 근거 두 개짜리 단계와 카드도 넣어 원과 알약 안의 긴 수치를 본다
			sections.add(new Section(layouts[index], "단계 " + index, "", "", List.of(
					new Point("짧은 키워드", "짧은 설명", "월평균삼백이십오명"), new Point("둘째", "설명", "2학기"),
					new Point("셋째", "설명", "3곳"))));
		}
		return new Deck("라".repeat(SlideLayouts.DECK_TITLE_MAX), "마".repeat(SlideLayouts.SUBTITLE_MAX), "focus", sections,
				new Section("list", "정리", "", "", List.of(new Point(head, body, ""), new Point(head, body, ""))));
	}

	// 구성 항목이 두 개라 목차가 없고 핵심 문장과 수치가 비어 있는 발표
	private static Deck few() {
		return new Deck("짧은 발표", "", "focus", List.of(
				new Section("statement", "하나", "", "", List.of(point("요점", "짧은 설명"))),
				new Section("cards", "둘", "", "", List.of(point("가", "설명 하나"), point("나", "설명 둘")))),
				new Section("list", "정리", "", "", List.of(point("끝", "마무리"), point("다음", "다음 단계"))));
	}

	// 글자 수 상한을 모두 채운 발표. 항목 20개로 긴 목차도 본다
	private static Deck longest() {
		String title = "가".repeat(SlideLayouts.SLIDE_TITLE_MAX - 8) + " 나다라마바사";
		String message = ("핵심 주장을 담은 아주 긴 문장 ").repeat(5).substring(0, SlideLayouts.MESSAGE_MAX).strip();
		String head = "키워드가 길어진 경우 끝까지 길게 쓴 경우입니다".substring(0, SlideLayouts.HEAD_MAX).strip();
		String body = ("본문 설명이 길어서 여러 줄로 ").repeat(7).substring(0, SlideLayouts.POINT_MAX).strip();
		List<Section> sections = new ArrayList<>();
		String[] layouts = { "cards", "list", "process", "compare", "statement" };
		int[] counts = { 4, 5, 5, 2, 3 };
		for (int index = 0; index < 20; index++) {
			List<Point> points = new ArrayList<>();
			for (int count = 0; count < counts[index % 5]; count++) {
				points.add(new Point(head, body, "123,456명"));
			}
			sections.add(new Section(layouts[index % 5], title, message, "1,234,567명", points));
		}
		String deckTitle = ("발표 제목이 아주 길게 ").repeat(4).substring(0, SlideLayouts.DECK_TITLE_MAX).strip();
		String subtitle = ("발표를 소개하는 문장이 상당히 길게 ").repeat(4).substring(0, SlideLayouts.SUBTITLE_MAX).strip();
		List<Point> closing = new ArrayList<>();
		for (int count = 0; count < SlideLayouts.POINTS_MAX; count++) {
			closing.add(new Point(head, body, ""));
		}
		return new Deck(deckTitle, subtitle, "focus", sections, new Section("list", title, message, "", closing));
	}

	@Test
	void generatedTextDropsEmojiAndDecorations() {
		assertThat(PresentationGeneration.clean("\uD83D\uDE80 \u2605 매출  12% \u2192 증가\u2705"))
			.isEqualTo("매출 12% 증가");
		assertThat(PresentationGeneration.clean("- 영하 3\u2103, 84\u33A1")).isEqualTo("영하 3\u2103, 84\u33A1");
		assertThat(PresentationGeneration.clean("\uD83D\uDC4D\uD83C\uDFFB")).isEmpty();
		assertThat(PresentationGeneration.clean("화씨 77\u2109")).isEqualTo("화씨 77\u2109");
	}

}
