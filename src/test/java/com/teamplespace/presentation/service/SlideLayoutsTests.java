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

// 모든 템플릿 색과 가장 긴 글로 만든 슬라이드가 캔버스 안에 들고 글끼리 겹치지 않으며 읽을 수 있는 대비인지 본다
// 결과는 build/slide-layouts에 남겨 브라우저로 그려 볼 수 있다
class SlideLayoutsTests {

	private static final Map<String, String[]> TEMPLATES = new LinkedHashMap<>();

	static {
		TEMPLATES.put("clean-report", new String[] { "#FFFFFF", "#212735" });
		TEMPLATES.put("blue-focus", new String[] { "#EBF1FF", "#2864E8" });
		TEMPLATES.put("editorial", new String[] { "#F8F4EB", "#28232B" });
		TEMPLATES.put("pitch-dark", new String[] { "#121827", "#A9CBFF" });
		TEMPLATES.put("warm-story", new String[] { "#FFF1E3", "#6A3729" });
		TEMPLATES.put("grid-analysis", new String[] { "#EAF5F1", "#174C43" });
		TEMPLATES.put("bold-cover", new String[] { "#FCE25B", "#232329" });
		TEMPLATES.put("orange-pitch", new String[] { "#FFF0E3", "#20242D" });
		TEMPLATES.put("green-insight", new String[] { "#E8EEE8", "#234B3D" });
		TEMPLATES.put("mono-editorial", new String[] { "#20242D", "#FFFFFF" });
		TEMPLATES.put("lilac-studio", new String[] { "#ECE7F7", "#403156" });
		TEMPLATES.put("data-briefing", new String[] { "#172C4D", "#FFFFFF" });
		TEMPLATES.put("warm-seminar", new String[] { "#FAF5E9", "#624738" });
		TEMPLATES.put("team-story", new String[] { "#FFFFFF", "#20242D" });
	}

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void everyTemplateKeepsTextInsideAndReadable() throws Exception {
		Path folder = Path.of("build", "slide-layouts");
		Files.createDirectories(folder);
		Map<String, Theme> themes = new LinkedHashMap<>();
		TEMPLATES.forEach((key, colors) -> {
			SlideLayouts.Style style = SlideLayouts.STYLES.get(key);
			themes.put(key, Theme.of("Pretendard", colors[0], colors[1], style.accent(), style.motif()));
		});
		themes.put("custom-white", Theme.of("Pretendard", "#FFFFFF", "#FFFFFF", "#FFFFFF", "rule"));
		themes.put("custom-green", Theme.of("Pretendard", "#FFFFFF", "#12B886", "#12B886", "rule"));

		for (var entry : themes.entrySet()) {
			for (var deck : Map.of("sample", sample(), "longest", longest()).entrySet()) {
				List<Slide> slides = SlideLayouts.build(json, deck.getValue(), entry.getValue(), sources(deck.getValue()));
				for (Slide slide : slides) {
					check(entry.getKey() + " " + deck.getKey() + " " + slide.layout(), slide);
				}
				Files.writeString(folder.resolve(entry.getKey() + "-" + deck.getKey() + ".json"),
						json.writeValueAsString(slides));
			}
		}
	}

	@Test
	void picksLayoutByContentAndAvoidsRepeats() {
		Theme theme = Theme.of("Pretendard", "#FFFFFF", "#212735", "#285BDD", "rule");
		List<Section> sections = List.of(section("compare", 3), section("cards", 3), section("cards", 3),
				section("process", 2), section("statement", 4));
		Deck deck = new Deck("제목", "소개", sections, section("list", 3));
		List<String> layouts = SlideLayouts.build(json, deck, theme, sources(deck)).stream().map(Slide::layout).toList();
		assertThat(layouts).containsExactly("title", "agenda", "cards", "list", "cards", "list", "cards", "summary");
	}

	@Test
	void measuresKoreanByWordsAndShrinksToFit() {
		assertThat(SlideLayouts.lines("가나다라마", 1000, 40, false)).isEqualTo(1);
		// 한 글자는 약 0.9em이라 200px에 20px 글자는 열 자 남짓 들어간다
		assertThat(SlideLayouts.lines("가나다라마 바사아자차 카타파하", 200, 20, false)).isEqualTo(2);
		assertThat(SlideLayouts.fit("가나다라마 바사아자차 카타파하", 200, 24, 12, 1, false).size()).isLessThan(24);
	}

	@Test
	void breaksLinesAtWordsWithoutDanglingSyllables() {
		String text = SlideLayouts.broken("교내 쓰레기의 절반이 한 번 쓰고 버린 컵입니다", 1040, 52, true);
		assertThat(text).contains("\n").doesNotContain(" 한\n").doesNotContain("\n번");
		assertThat(text.replace("\n", " ")).isEqualTo("교내 쓰레기의 절반이 한 번 쓰고 버린 컵입니다");
	}

	private void check(String name, Slide slide) {
		List<Element> elements = slide.elements();
		List<Element> texts = new ArrayList<>();
		for (int index = 0; index < elements.size(); index++) {
			Element element = elements.get(index);
			assertThat(element.x()).as(name).isBetween(0, SlideLayouts.WIDTH);
			assertThat(element.y()).as(name).isBetween(0, SlideLayouts.HEIGHT);
			assertThat(element.x() + element.width()).as(name).isLessThanOrEqualTo(SlideLayouts.WIDTH);
			assertThat(element.y() + element.height()).as(name).isLessThanOrEqualTo(SlideLayouts.HEIGHT);
			ElementRules.check(element.type(), element.content(), element.style(), null);
			if (!element.type().equals("text")) {
				continue;
			}
			String text = element.content().get("text").asString();
			int size = element.style().get("fontSize").asInt();
			boolean bold = element.style().get("fontWeight").asInt() >= 600;
			double lineHeight = element.style().get("lineHeight").asDouble();
			int lines = SlideLayouts.lines(text, element.width(), size, bold);
			assertThat(element.height()).as(name + " 글상자 높이 " + text).isGreaterThanOrEqualTo((int) (lines * size * lineHeight));
			for (Element other : texts) {
				assertThat(overlaps(element, other)).as(name + " 겹침 " + text + " / " + other.content().get("text"))
					.isFalse();
			}
			texts.add(element);
			String fill = below(elements, index);
			double needed = size >= 24 || bold && size >= 19 ? 3 : 4.5;
			assertThat(Theme.contrast(fill, element.style().get("color").asString())).as(name + " 대비 " + text)
				.isGreaterThanOrEqualTo(needed);
		}
	}

	// 글상자 가운데 아래에 깔린 가장 위의 채운 도형 색
	private static String below(List<Element> elements, int index) {
		Element text = elements.get(index);
		int x = text.x() + Math.min(text.width(), 40) / 2;
		int y = text.y() + Math.min(text.height(), 40) / 2;
		for (int at = index - 1; at >= 0; at--) {
			Element shape = elements.get(at);
			if (shape.type().equals("shape") && !shape.style().get("fill").isNull() && x >= shape.x()
					&& x <= shape.x() + shape.width() && y >= shape.y() && y <= shape.y() + shape.height()) {
				return shape.style().get("fill").asString();
			}
		}
		throw new AssertionError("배경이 없는 글상자");
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
			points.add(new Point("요점 " + index, "요점을 풀어 쓴 문장 " + index));
		}
		return new Section(layout, "제목", "핵심 문장", points);
	}

	private static Deck sample() {
		return new Deck("캠퍼스 일회용품, 줄일 수 있을까", "학생 설문과 현장 조사로 살펴본 교내 일회용품 사용 실태와 개선 방향", List.of(
				new Section("statement", "왜 지금 일회용품인가", "교내 쓰레기의 절반이 한 번 쓰고 버린 컵입니다", List.of(
						new Point("배출 증가", "카페 이용이 늘며 컵 배출량이 꾸준히 증가했습니다"),
						new Point("분리 배출 한계", "음료가 남은 컵은 재활용되지 못하고 일반 쓰레기가 됩니다"))),
				new Section("cards", "조사 방법", "세 가지 방법으로 사용 실태를 확인했습니다", List.of(
						new Point("설문 조사", "재학생 312명에게 일회용품 사용 습관을 물었습니다"),
						new Point("현장 관찰", "점심시간 학생식당과 카페 세 곳의 배출량을 기록했습니다"),
						new Point("인터뷰", "카페 운영자와 환경 동아리의 의견을 들었습니다"))),
				new Section("compare", "다회용 컵 도입 전후", "회수 체계가 있으면 다회용 컵이 정착합니다", List.of(
						new Point("현재", "개인 텀블러 할인만 있어 참여가 일부 학생에 머뭅니다"),
						new Point("개선안", "공용 다회용 컵과 회수함을 두어 누구나 빌리고 반납합니다"))),
				new Section("process", "실행 계획", "한 학기 시범 운영 뒤 전체로 넓힙니다", List.of(
						new Point("준비", "회수함 위치와 세척 업체를 정합니다"),
						new Point("시범 운영", "학생회관 카페 한 곳에서 8주간 운영합니다"),
						new Point("평가", "반납률과 만족도를 조사합니다"),
						new Point("확대", "결과를 바탕으로 모든 카페에 적용합니다"))),
				new Section("list", "예상 효과와 과제", "", List.of(
						new Point("쓰레기 감소", "컵 배출량이 줄어 청소 부담이 줄어듭니다"),
						new Point("비용", "초기 컵 구매와 세척 비용을 학교와 나누어야 합니다"),
						new Point("참여 유도", "반납 보상과 홍보로 습관을 바꾸는 것이 중요합니다")))),
				new Section("list", "정리", "작은 습관의 변화가 캠퍼스를 바꿉니다", List.of(
						new Point("문제 확인", "일회용 컵이 교내 쓰레기의 큰 부분을 차지합니다"),
						new Point("해결 방향", "공용 다회용 컵과 회수 체계를 시범 도입합니다"),
						new Point("다음 단계", "학생회와 함께 시범 운영 계획을 확정합니다"))));
	}

	// 글자 수 상한을 모두 채운 발표. 항목 20개로 목차 두 줄 배치도 본다
	private static Deck longest() {
		String title = "가".repeat(SlideLayouts.SLIDE_TITLE_MAX - 8) + " 나다라마바사";
		String message = ("핵심 주장을 담은 아주 긴 문장 ").repeat(5).substring(0, SlideLayouts.MESSAGE_MAX).strip();
		String head = "키워드가 길어진 경우 끝까지".substring(0, 14);
		String body = ("본문 설명이 길어서 여러 줄로 ").repeat(7).substring(0, SlideLayouts.POINT_MAX).strip();
		List<Section> sections = new ArrayList<>();
		String[] layouts = { "cards", "list", "process", "compare", "statement" };
		int[] counts = { 4, 5, 5, 2, 3 };
		for (int index = 0; index < 20; index++) {
			List<Point> points = new ArrayList<>();
			for (int count = 0; count < counts[index % 5]; count++) {
				points.add(new Point(head, body));
			}
			sections.add(new Section(layouts[index % 5], title, message, points));
		}
		String deckTitle = ("발표 제목이 아주 길게 ").repeat(4).substring(0, SlideLayouts.DECK_TITLE_MAX).strip();
		String subtitle = ("발표를 소개하는 문장이 상당히 길게 ").repeat(4).substring(0, SlideLayouts.SUBTITLE_MAX).strip();
		return new Deck(deckTitle, subtitle, sections,
				new Section("list", title, message, List.of(new Point(head, body), new Point(head, body),
						new Point(head, body), new Point(head, body))));
	}

}
