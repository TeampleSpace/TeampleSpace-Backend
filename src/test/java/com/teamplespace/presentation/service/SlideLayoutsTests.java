package com.teamplespace.presentation.service;

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
			for (var deck : Map.of("sample", sample(), "longest", longest(), "few", few()).entrySet()) {
				List<Slide> slides = SlideLayouts.build(json, deck.getValue(), theme.getValue(), sources(deck.getValue()));
				for (int index = 0; index < slides.size(); index++) {
					check(theme.getKey() + " " + deck.getKey() + " " + index + " " + slides.get(index).layout(),
							slides.get(index), problems);
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

	private static List<String> sources(Deck deck) {
		List<String> sources = new ArrayList<>();
		for (int index = 0; index < deck.slides().size(); index++) {
			sources.add(index % 2 == 0 ? "출처: 캠퍼스 일회용품 실태 조사, 학생회 설문 결과 외 1건" : null);
		}
		return sources;
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

	static Deck sample() {
		return new Deck("캠퍼스 일회용품, 줄일 수 있을까", "학생 설문과 현장 조사로 살펴본 교내 일회용품 사용 실태와 개선 방향", "focus", List.of(
				new Section("statement", "왜 지금 일회용품인가", "교내 쓰레기의 절반이 한 번 쓰고 버린 컵입니다", "50%", List.of(
						point("배출 증가", "카페 이용이 늘며 컵 배출량이 꾸준히 증가했습니다"),
						point("분리 배출 한계", "음료가 남은 컵은 재활용되지 못하고 일반 쓰레기가 됩니다"))),
				new Section("cards", "조사 방법", "세 가지 방법으로 사용 실태를 확인했습니다", "", List.of(
						new Point("설문 조사", "재학생 312명에게 일회용품 사용 습관을 물었습니다", "312명"),
						new Point("현장 관찰", "점심시간 학생식당과 카페 세 곳의 배출량을 기록했습니다", "3곳"),
						new Point("인터뷰", "카페 운영자와 환경 동아리의 의견을 들었습니다", "2개 그룹"))),
				new Section("compare", "다회용 컵 도입 전후", "회수 체계가 있으면 다회용 컵이 정착합니다", "", List.of(
						point("개인 텀블러 할인뿐", "할인만으로는 참여가 일부 학생에 머물고 반납 습관이 생기지 않습니다"),
						point("공용 컵과 회수함", "누구나 빌리고 반납하는 공용 다회용 컵과 건물마다 회수함을 둡니다"))),
				new Section("process", "실행 계획", "한 학기 시범 운영 뒤 전체로 넓힙니다", "", List.of(
						new Point("준비", "회수함 위치와 세척 업체를 정합니다", "3월"),
						new Point("시범 운영", "학생회관 카페 한 곳에서 8주간 운영합니다", "4~5월"),
						new Point("평가", "반납률과 만족도를 조사합니다", "6월"),
						new Point("확대", "결과를 바탕으로 모든 카페에 적용합니다", "2학기"))),
				new Section("list", "예상 효과와 과제", "효과는 분명하지만 비용과 참여를 함께 준비해야 합니다", "", List.of(
						point("쓰레기 감소", "컵 배출량이 줄어 청소 부담이 줄어듭니다"),
						point("비용 분담", "초기 컵 구매와 세척 비용을 학교와 나누어야 합니다"),
						point("참여 유도", "반납 보상과 홍보로 습관을 바꾸는 것이 가장 중요합니다")))),
				new Section("list", "정리", "작은 습관의 변화가 캠퍼스를 바꿉니다", "", List.of(
						point("문제 확인", "일회용 컵이 교내 쓰레기의 큰 부분을 차지합니다"),
						point("해결 방향", "공용 다회용 컵과 회수 체계를 시범 도입합니다"),
						point("다음 단계", "학생회와 함께 시범 운영 계획을 확정합니다"))));
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

}
