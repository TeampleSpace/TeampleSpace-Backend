package com.teamplespace.presentation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.presentation.service.SlideLayouts.Deck;
import com.teamplespace.presentation.service.SlideLayouts.Point;
import com.teamplespace.presentation.service.SlideLayouts.Section;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 템플릿으로 만들 발표의 배치를 생성 엔진으로 그려 예시 슬라이드로 돌려준다
@Service
public class TemplatePreviews {

	public record Canvas(int width, int height, String unit) {
	}

	public record Geometry(int x, int y, int width, int height, int rotation, int z) {
	}

	public record PreviewElement(String type, Geometry geometry, ObjectNode content, ObjectNode style) {
	}

	public record PreviewSlide(String layoutId, List<PreviewElement> elements) {
	}

	public record Preview(UUID templateId, long templateVersion, Canvas canvas, List<PreviewSlide> slides) {
	}

	private final JdbcClient jdbc;
	private final ObjectMapper json;

	public TemplatePreviews(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public Preview preview(UUID templateId) {
		JsonNode template = jdbc.sql("""
				SELECT JSON_OBJECT('key', template_key, 'version', release_no, 'preview', preview, 'fonts', fonts)
				FROM templates WHERE id = UUID_TO_BIN(?)
				""").param(templateId.toString()).query(String.class).optional().map(json::readTree)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		SlideLayouts.Theme theme = PresentationGeneration.templateTheme(template);
		Deck deck = sample(SlideLayouts.CHART_STYLES.contains(theme.style()));
		List<PreviewSlide> slides = new ArrayList<>();
		for (SlideLayouts.Slide slide : SlideLayouts.build(json, deck, theme, sources(deck))) {
			List<PreviewElement> elements = new ArrayList<>();
			for (SlideLayouts.Element element : slide.elements()) {
				elements.add(new PreviewElement(element.type(), new Geometry(element.x(), element.y(), element.width(),
						element.height(), 0, elements.size()), element.content(), element.style()));
			}
			slides.add(new PreviewSlide(slide.layout(), List.copyOf(elements)));
		}
		return new Preview(templateId, template.path("version").asLong(),
				new Canvas(SlideLayouts.WIDTH, SlideLayouts.HEIGHT, "px"), List.copyOf(slides));
	}

	private static Point point(String head, String body) {
		return new Point(head, body, "");
	}

	// 미리보기와 배치 테스트에 쓰는 예시 발표
	static Deck sample() {
		return sample(false);
	}

	// 그래프 장을 그리는 템플릿은 비교 뒤에 설문 응답 그래프를 넣는다
	static Deck sample(boolean chart) {
		List<Section> sections = new ArrayList<>(List.of(
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
						point("참여 유도", "반납 보상과 홍보로 습관을 바꾸는 것이 가장 중요합니다")))));
		if (chart) {
			sections.add(3, new Section("chart", "다회용품을 쓰지 않는 이유", "반납 동선을 먼저 줄여야 합니다", "", List.of(
					new Point("반납 장소", "구매한 곳과 반납하는 곳이 멀어 다시 쓰지 않습니다", "64%"),
					new Point("휴대 부담", "수업 사이에 컵을 들고 다니기 어렵습니다", "46%"),
					new Point("세척 불편", "강의실 근처에 컵을 씻을 곳이 없습니다", "32%"),
					new Point("기타", "가격과 위생이 걱정된다는 응답입니다", "18%"))));
		}
		return new Deck("캠퍼스 일회용품, 줄일 수 있을까", "학생 설문과 현장 조사로 살펴본 교내 일회용품 사용 실태와 개선 방향", "focus",
				sections, new Section("list", "정리", "작은 습관의 변화가 캠퍼스를 바꿉니다", "", List.of(
						point("문제 확인", "일회용 컵이 교내 쓰레기의 큰 부분을 차지합니다"),
						point("해결 방향", "공용 다회용 컵과 회수 체계를 시범 도입합니다"),
						point("다음 단계", "학생회와 함께 시범 운영 계획을 확정합니다"))));
	}

	// 장마다 번갈아 출처 줄을 붙인다
	static List<String> sources(Deck deck) {
		List<String> sources = new ArrayList<>();
		for (int index = 0; index < deck.slides().size(); index++) {
			sources.add(index % 2 == 0 ? "출처: 캠퍼스 일회용품 실태 조사, 학생회 설문 결과 외 1건" : null);
		}
		return sources;
	}
}
