package com.teamplespace.presentation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 생성한 글을 1280 x 720 캔버스의 요소로 배치한다
// 템플릿은 색과 표지 분위기만 정하고 본문 배치는 내용에 맞춰 고른다
final class SlideLayouts {

	record Point(String head, String body) {
	}

	record Section(String layout, String title, String message, List<Point> points) {
	}

	record Deck(String title, String subtitle, List<Section> slides, Section closing) {
	}

	// 템플릿마다 강조색과 표지 모양. 목록에 없는 템플릿은 제목색과 기본 표지를 쓴다
	record Style(String accent, String motif) {
	}

	static final Map<String, Style> STYLES = Map.ofEntries(Map.entry("clean-report", new Style("#285BDD", "rule")),
			Map.entry("blue-focus", new Style("#285BDD", "panel")),
			Map.entry("orange-pitch", new Style("#F27C36", "band")),
			Map.entry("green-insight", new Style("#2F7A5F", "blocks")),
			Map.entry("mono-editorial", new Style("#F27C36", "rule")),
			Map.entry("lilac-studio", new Style("#7454B4", "line")),
			Map.entry("data-briefing", new Style("#94B6FF", "bars")),
			Map.entry("warm-seminar", new Style("#B8633A", "rule")),
			Map.entry("team-story", new Style("#285BDD", "blocks")),
			Map.entry("editorial", new Style("#B4532A", "rule")),
			Map.entry("pitch-dark", new Style("#A9CBFF", "bars")),
			Map.entry("warm-story", new Style("#D9663F", "line")),
			Map.entry("grid-analysis", new Style("#2E8B6E", "blocks")),
			Map.entry("bold-cover", new Style("#232329", "band")));

	record Theme(String font, String background, String heading, String body, String accent, String accentText,
			String onAccent, String muted, String surface, String line, String motif) {

		// 제목색이 배경과 3:1 대비가 안 되면 본문색을 쓴다. 강조색도 같은 기준으로 글자에 쓸지 정한다
		static Theme of(String font, String background, String heading, String accent, String motif) {
			String body = readable(background);
			String title = contrast(background, heading) < 3 ? body : heading;
			String mark = accent == null || contrast(background, accent) < 1.6 ? title : accent;
			String markText = contrast(background, mark) < 3.4 ? title : mark;
			String muted = mix(body, background, 0.3);
			if (contrast(background, muted) < 4.5) {
				muted = body;
			}
			return new Theme(font, background, title, body, mark, markText, readable(mark), muted,
					mix(background, body, 0.06), mix(background, body, 0.16), motif == null ? "rule" : motif);
		}

		static String readable(String background) {
			return contrast(background, LIGHT) >= contrast(background, DARK) ? LIGHT : DARK;
		}

		static double contrast(String first, String second) {
			double a = luminance(first);
			double b = luminance(second);
			return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
		}

		static String mix(String from, String to, double amount) {
			int a = Integer.parseInt(from.substring(1), 16);
			int b = Integer.parseInt(to.substring(1), 16);
			int rgb = 0;
			for (int shift = 16; shift >= 0; shift -= 8) {
				int x = a >> shift & 0xFF;
				int y = b >> shift & 0xFF;
				rgb |= (int) Math.round(x + (y - x) * amount) << shift;
			}
			return String.format("#%06X", rgb);
		}

		private static double luminance(String hex) {
			int rgb = Integer.parseInt(hex.substring(1), 16);
			return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8 & 0xFF) + 0.0722 * channel(rgb & 0xFF);
		}

		private static double channel(int value) {
			double c = value / 255.0;
			return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
		}
	}

	private static final String LIGHT = "#F5F7FA";
	private static final String DARK = "#1F2933";

	record Element(String type, int x, int y, int width, int height, ObjectNode content, ObjectNode style) {
	}

	record Slide(String layout, List<Element> elements) {
	}

	static final int WIDTH = 1280;
	static final int HEIGHT = 720;
	static final int DECK_TITLE_MAX = 40;
	static final int SUBTITLE_MAX = 80;
	static final int SLIDE_TITLE_MAX = 40;
	static final int MESSAGE_MAX = 80;
	static final int HEAD_MAX = 24;
	static final int POINT_MAX = 90;
	static final int POINTS_MAX = 5;

	static final List<String> LAYOUTS = List.of("cards", "list", "process", "compare", "statement");

	private static final int MARGIN = 80;
	private static final int INNER = WIDTH - MARGIN * 2;
	private static final int BOTTOM = 628;
	private static final int FOOTER = 668;

	private final ObjectMapper json;
	private final Theme theme;
	private List<Element> out;

	private SlideLayouts(ObjectMapper json, Theme theme) {
		this.json = json;
		this.theme = theme;
	}

	// sources는 본문 슬라이드마다 출처 문구이고 없으면 null이다
	static List<Slide> build(ObjectMapper json, Deck deck, Theme theme, List<String> sources) {
		var layouts = new SlideLayouts(json, theme);
		List<Slide> slides = new ArrayList<>();
		slides.add(layouts.cover(deck.title(), deck.subtitle()));
		if (deck.slides().size() >= 3) {
			slides.add(layouts.agenda(deck.slides().stream().map(Section::title).toList()));
		}
		String previous = null;
		for (int index = 0; index < deck.slides().size(); index++) {
			Slide slide = layouts.content(deck.slides().get(index), index + 1, sources.get(index), slides.size() + 1,
					previous);
			previous = slide.layout();
			slides.add(slide);
		}
		slides.add(layouts.closing(deck.closing()));
		return slides;
	}

	// 표지

	private Slide cover(String title, String subtitle) {
		out = new ArrayList<>();
		background(theme.background());
		switch (theme.motif()) {
			case "panel" -> {
				rect(0, 0, 440, HEIGHT, theme.accent(), 0);
				coverText(520, 680, title, subtitle);
				ring(88, 380, 248);
				ring(168, 452, 248);
			}
			case "band" -> {
				rect(0, 168, WIDTH, 384, theme.accent(), 0);
				Fit fit = fit(title, INNER, 76, 44, 3, true);
				int height = fit.height(1.2);
				text(MARGIN, 168 + (384 - height) / 2, INNER, height, title, fit.size(),
						800, theme.onAccent(), "left", 1.2);
				Fit sub = fit(subtitle, INNER, 26, 20, 2, false);
				text(MARGIN,
						584, INNER, sub.height(1.4), subtitle, sub.size(), 400, theme.body(), "left", 1.4);
			}
			case "blocks" -> {
				rect(900, 136, 260, 268, theme.accent(), 0);
				coverText(MARGIN, 660, title, subtitle);
				rect(772, 404, 260, 196, theme.heading(), 0);
			}
			case "bars" -> {
				rect(964, 400, 56, 200, theme.accent(), 0);
				coverText(MARGIN, 760, title, subtitle);
				rect(1044, 300, 56, 300, theme.accent(), 0);
				rect(1124, 184, 56, 416, theme.accent(), 0);
			}
			case "line" -> {
				rect(MARGIN, 80, 8, 560, theme.accent(), 0);
				coverText(MARGIN + 64, INNER - 64, title, subtitle);
			}
			default -> {
				Fit head = fit(title, INNER, 76, 40, 3, true);
				Fit sub = fit(subtitle, INNER, 26, 20, 2, false);
				int top = (HEIGHT - head.height(1.22) - 82 - sub.height(1.45)) / 2 - 12;
				rect(MARGIN, top - 56, 72, 8, theme.accent(), 0);
				text(MARGIN, top, INNER, head.height(1.22), title, head.size(),
						800, theme.heading(), "left", 1.22);
				rect(MARGIN, top + head.height(1.22) + 40, INNER, 2, theme.line(), 0);
				text(MARGIN, top + head.height(1.22) + 82, INNER, sub.height(1.45), subtitle, sub.size(),
						400, theme.muted(),
						"left", 1.45);
			}
		}
		return new Slide("title", out);
	}

	// 제목과 소개를 세로 가운데에 놓는다
	private void coverText(int x, int width, String title, String subtitle) {
		Fit head = fit(title, width, 76, 40, 3, true);
		Fit sub = fit(subtitle, width, 26, 20, 2, false);
		int top = (HEIGHT - head.height(1.22) - 32 - sub.height(1.45)) / 2 - 12;
		text(x, top, width, head.height(1.22), title, head.size(),
				800, theme.heading(), "left", 1.22);
		text(x, top + head.height(1.22) + 32, width, sub.height(1.45), subtitle, sub.size(),
				400, theme.muted(), "left",
				1.45);
	}

	private void ring(int x, int y, int size) {
		ObjectNode style = json.createObjectNode().putNull("fill").put("stroke", theme.onAccent()).put("strokeWidth", 2);
		out.add(new Element("shape", x, y, size, size, json.createObjectNode().put("shape", "ellipse"), style));
	}

	// 목차

	private Slide agenda(List<String> titles) {
		out = new ArrayList<>();
		background(theme.background());
		text(MARGIN, 96, 360, 26, "CONTENTS", 19, 700, theme.accentText(), "left", 1.3);
		text(MARGIN, 128, 360, 72, "목차", 56, 800, theme.heading(), "left", 1.25);
		rect(MARGIN, 220, 64, 6, theme.accent(), 0);
		if ("panel".equals(theme.motif()) || "line".equals(theme.motif())) {
			rect(0, 0, 16, HEIGHT, theme.accent(), 0);
		}

		int columns = titles.size() > 6 ? 2 : 1;
		int rows = (titles.size() + columns - 1) / columns;
		int left = columns == 1 ? 520 : 460;
		int columnWidth = columns == 1 ? 680 : 350;
		int rowHeight = Math.min(84, 560 / rows);
		int top = (HEIGHT - rowHeight * rows) / 2;
		int number = columns == 1 ? 22 : 19;
		for (int index = 0; index < titles.size(); index++) {
			int x = left + index / rows * (columnWidth + 40);
			int y = top + index % rows * rowHeight;
			Fit fit = fit(titles.get(index), columnWidth - 56, columns == 1 ? 24 : 20, 14, 2, true);
			int textTop = y + (rowHeight - fit.height(1.3)) / 2;
			text(x, y + (rowHeight - 30) / 2, 48, 30, two(index + 1), number, 700, theme.accentText(), "left", 1.3);
			text(x + 56, textTop, columnWidth - 56, fit.height(1.3), titles.get(index), fit.size(),
					600, theme.heading(),
					"left", 1.3);
			rect(x, y + rowHeight - 1, columnWidth, 1, theme.line(), 0);
		}
		return new Slide("agenda", out);
	}

	// 본문

	private Slide content(Section section, int number, String source, int page, String previous) {
		for (String layout : candidates(section, previous)) {
			out = new ArrayList<>();
			background(theme.background());
			boolean fits = switch (layout) {
				case "cards" -> cards(section, number);
				case "process" -> process(section, number);
				case "compare" -> compare(section, number);
				case "statement" -> statement(section, number);
				default -> list(section, number, true, false);
			};
			if (fits) {
				footer(source, page);
				return new Slide(layout, out);
			}
		}
		// 다 넘치면 핵심 문장을 빼고 목록으로 그린다
		for (boolean force : new boolean[] { false, true }) {
			out = new ArrayList<>();
			background(theme.background());
			if (list(section, number, false, force)) {
				break;
			}
		}
		footer(source, page);
		return new Slide("list", out);
	}

	// 요청한 배치를 먼저 쓰고 항목 수가 맞지 않거나 넘치면 다음 배치로 바꾼다. 같은 배치가 이어지면 카드와 목록을 번갈아 쓴다
	private static List<String> candidates(Section section, String previous) {
		int count = section.points().size();
		Predicate<String> usable = layout -> switch (layout) {
			case "cards" -> count >= 2 && count <= 4;
			case "process" -> count >= 3 && count <= 5;
			case "compare" -> count == 2;
			case "statement" -> count <= 3 && !section.message().isEmpty();
			default -> true;
		};
		String first = section.layout();
		if (first.equals(previous) && first.equals("cards") && usable.test("list")) {
			first = "list";
		}
		else if (first.equals(previous) && first.equals("list") && usable.test("cards")) {
			first = "cards";
		}
		List<String> order = new ArrayList<>(List.of(first));
		order.addAll(switch (first) {
			case "compare", "statement" -> List.of("cards", "list");
			default -> List.of("list");
		});
		return order.stream().distinct().filter(usable).toList();
	}

	// 번호, 제목, 핵심 문장을 놓고 본문이 시작할 위치를 돌려준다
	private int header(Section section, int number, boolean message) {
		text(MARGIN, 52, 48, 26, two(number), 19, 700, theme.accentText(), "left", 1.3);
		rect(MARGIN + 36, 62, 28, 3, theme.accent(), 0);
		Fit title = fit(section.title(), INNER, 40, 30, 2, true);
		text(MARGIN, 88, INNER, title.height(1.25), section.title(), title.size(),
				700, theme.heading(), "left", 1.25);
		int bottom = 88 + title.height(1.25);
		if (message && !section.message().isEmpty()) {
			Fit sub = fit(section.message(), INNER, 22, 18, 2, false);
			text(MARGIN, bottom + 12, INNER, sub.height(1.45), section.message(), sub.size(),
					400, theme.muted(), "left",
					1.45);
			bottom += 12 + sub.height(1.45);
		}
		return bottom + 44;
	}

	private boolean cards(Section section, int number) {
		int top = header(section, number, true);
		List<Point> points = section.points();
		int count = points.size();
		int gap = 24;
		int width = (INNER - gap * (count - 1)) / count;
		int pad = count == 4 ? 28 : 36;
		int inner = width - pad * 2;
		List<Fit> heads = new ArrayList<>();
		List<Fit> bodies = new ArrayList<>();
		int tallest = 0;
		for (Point point : points) {
			Fit head = fit(point.head(), inner, 26, 20, 2, true);
			Fit body = fit(point.body(), inner, 20, 16, 7, false);
			heads.add(head);
			bodies.add(body);
			tallest = Math.max(tallest, head.height(1.3) + body.height(1.5));
		}
		// 카드는 남는 높이를 채우고 번호는 위, 글은 아래에 맞춘다
		int needed = pad * 2 + 40 + 32 + tallest + 14;
		if (top + needed > BOTTOM) {
			return false;
		}
		int height = Math.max(needed, Math.min(BOTTOM - top, 340));
		for (int index = 0; index < count; index++) {
			int x = MARGIN + index * (width + gap);
			rect(x, top, width, height, theme.surface(), 20);
			text(x + pad, top + pad, inner, 40, two(index + 1), 32, 800, theme.accentText(), "left", 1.2);
			int y = top + height - pad - tallest - 14;
			Fit head = heads.get(index);
			text(x + pad, y, inner, head.height(1.3), points.get(index).head(), head.size(),
					700, theme.heading(), "left",
					1.3);
			y += head.height(1.3) + 14;
			Fit body = bodies.get(index);
			text(x + pad, y, inner, body.height(1.5), points.get(index).body(), body.size(), 400, theme.body(), "left",
					1.5);
		}
		return true;
	}

	// 번호, 키워드, 설명을 한 줄씩 놓는 표 형식. force면 넘쳐도 가장 작은 글자로 배치한다
	private boolean list(Section section, int number, boolean message, boolean force) {
		int top = header(section, number, message);
		List<Point> points = section.points();
		int[][] sizes = { { 24, 20 }, { 22, 18 }, { 20, 16 }, { 18, 15 } };
		for (int attempt = 0; attempt < sizes.length; attempt++) {
			int headSize = sizes[attempt][0];
			int bodySize = sizes[attempt][1];
			List<Fit> heads = new ArrayList<>();
			List<Fit> bodies = new ArrayList<>();
			int content = 0;
			for (Point point : points) {
				Fit head = fit(point.head(), 300, headSize, headSize - 4, 2, true);
				Fit body = fit(point.body(), 700, bodySize, bodySize, 4, false);
				heads.add(head);
				bodies.add(body);
				content += Math.max(head.height(1.35), body.height(1.5));
			}
			int space = BOTTOM - top - content;
			int pad = Math.min(36, space / (points.size() * 2));
			if (pad < 12 && !(force && attempt == sizes.length - 1)) {
				continue;
			}
			pad = Math.max(pad, 12);
			int y = top;
			rect(MARGIN, y, INNER, 2, theme.heading(), 0);
			for (int index = 0; index < points.size(); index++) {
				Fit head = heads.get(index);
				Fit body = bodies.get(index);
				int row = Math.max(head.height(1.35), body.height(1.5));
				int line = y + pad;
				text(MARGIN, line, 56, (int) Math.ceil(headSize * 1.35) + 4, two(index + 1), headSize,
						700, theme.accentText(), "left", 1.35);
				text(MARGIN + 72, line, 300, head.height(1.35), points.get(index).head(), head.size(), 700,
						theme.heading(), "left", 1.35);
				text(MARGIN + 420, line,
						700, body.height(1.5), points.get(index).body(), body.size(), 400, theme.body(),
						"left", 1.5);
				y = line + row + pad;
				if (index < points.size() - 1) {
					rect(MARGIN, y, INNER, 1, theme.line(), 0);
				}
			}
			return true;
		}
		return false;
	}

	private boolean process(Section section, int number) {
		int top = header(section, number, true);
		List<Point> points = section.points();
		int count = points.size();
		int column = INNER / count;
		int inner = column - 32;
		int circle = 60;
		int tallest = 0;
		for (Point point : points) {
			tallest = Math.max(tallest, fit(point.head(), inner, 24, 18, 2, true).height(1.3) + 12
					+ fit(point.body(), inner, 19, 15, 6, false).height(1.5));
		}
		// 단계 묶음을 남는 높이의 가운데에 놓는다
		top += Math.max(0, (BOTTOM - top - circle - 28 - tallest) / 2);
		rect(MARGIN + column / 2, top + circle / 2 - 1, INNER - column, 2, theme.line(), 0);
		int bottom = top;
		for (int index = 0; index < count; index++) {
			int center = MARGIN + column * index + column / 2;
			ObjectNode style = json.createObjectNode().put("fill", theme.accent()).putNull("stroke").put("strokeWidth", 0);
			out.add(new Element("shape", center - circle / 2, top, circle, circle,
					json.createObjectNode().put("shape", "ellipse"), style));
			text(center - circle / 2, top + 16, circle, 28, two(index + 1), 20, 700, theme.onAccent(), "center", 1.3);
			Fit head = fit(points.get(index).head(), inner, 24, 18, 2, true);
			int y = top + circle + 28;
			text(center - inner / 2, y, inner, head.height(1.3), points.get(index).head(), head.size(), 700,
					theme.heading(), "center", 1.3);
			y += head.height(1.3) + 12;
			Fit body = fit(points.get(index).body(), inner, 19, 15, 6, false);
			text(center - inner / 2, y, inner, body.height(1.5), points.get(index).body(), body.size(),
					400, theme.body(),
					"center", 1.5);
			bottom = Math.max(bottom, y + body.height(1.5));
		}
		return bottom <= BOTTOM;
	}

	private boolean compare(Section section, int number) {
		int top = header(section, number, true);
		int gap = 24;
		int width = (INNER - gap) / 2;
		int pad = 44;
		int tallest = 0;
		for (Point point : section.points()) {
			tallest = Math.max(tallest, fit(point.head(), width - pad * 2, 32, 22, 2, true).height(1.3) + 40
					+ fit(point.body(), width - pad * 2, 22, 17, 6, false).height(1.5));
		}
		if (top + tallest + pad * 2 > BOTTOM) {
			return false;
		}
		int height = Math.max(tallest + pad * 2, Math.min(BOTTOM - top, 320));
		String strong = strong();
		String onStrong = Theme.readable(strong);
		for (int index = 0; index < 2; index++) {
			Point point = section.points().get(index);
			boolean filled = index == 1;
			int x = MARGIN + index * (width + gap);
			String ink = filled ? onStrong : theme.heading();
			String text = filled ? onStrong : theme.body();
			rect(x, top, width, height, filled ? strong : theme.surface(), 24);
			Fit head = fit(point.head(), width - pad * 2, 32, 22, 2, true);
			Fit body = fit(point.body(), width - pad * 2, 22, 17, 6, false);
			text(x + pad, top + pad, width - pad * 2, head.height(1.3), point.head(), head.size(),
					800, ink, "left", 1.3);
			rect(x + pad, top + pad + head.height(1.3) + 16, 40, 4, filled ? onStrong : theme.accent(), 0);
			text(x + pad, top + pad + head.height(1.3) + 40, width - pad * 2, body.height(1.5), point.body(), body.size(),
					400, text, "left", 1.5);
		}
		return true;
	}

	// 핵심 문장을 크게 쓰고 근거를 아래에 작게 놓는다
	private boolean statement(Section section, int number) {
		text(MARGIN, 52, 48, 26, two(number), 19, 700, theme.accentText(), "left", 1.3);
		Fit title = fit(section.title(), INNER - 48, 24, 20, 1, true);
		text(MARGIN + 48, 52, INNER - 48, title.height(1.3), section.title(), title.size(), 700, theme.muted(), "left",
				1.3);
		Fit message = fit(section.message(), 1040, 52, 34, 3, true);
		int y = 168;
		rect(MARGIN, y, 72, 8, theme.accent(), 0);
		y += 48;
		text(MARGIN, y, 1040, message.height(1.3), section.message(), message.size(),
				800, theme.heading(), "left", 1.3);
		int messageBottom = y + message.height(1.3);

		List<Point> points = section.points();
		int gap = 40;
		int width = (INNER - gap * (points.size() - 1)) / points.size();
		int tallest = 0;
		for (Point point : points) {
			tallest = Math.max(tallest, 20 + fit(point.head(), width, 20, 18, 1, true).height(1.3) + 8
					+ fit(point.body(), width, 18, 15, 4, false).height(1.5));
		}
		int top = BOTTOM - tallest;
		if (top < messageBottom + 40) {
			return false;
		}
		for (int index = 0; index < points.size(); index++) {
			Point point = points.get(index);
			int x = MARGIN + index * (width + gap);
			rect(x, top, width, 2, theme.line(), 0);
			Fit head = fit(point.head(), width, 20, 18, 1, true);
			text(x, top + 20, width, head.height(1.3), point.head(), head.size(), 700, theme.accentText(), "left", 1.3);
			Fit body = fit(point.body(), width, 18, 15, 4, false);
			text(x, top + 20 + head.height(1.3) + 8, width, body.height(1.5), point.body(), body.size(),
					400, theme.body(),
					"left", 1.5);
		}
		return true;
	}

	private void footer(String source, int page) {
		if (source != null) {
			text(MARGIN, FOOTER, 960, 22, source, 14, 400, theme.muted(), "left", 1.4);
		}
		text(WIDTH - MARGIN - 80, FOOTER, 80, 22, two(page), 14, 700, theme.muted(), "right", 1.4);
	}

	// 강조색으로 채운 면. 그 위 본문 글자가 잘 안 읽히면 제목색으로 채운다
	private String strong() {
		return Theme.contrast(theme.accent(), theme.onAccent()) >= 4.5 ? theme.accent() : theme.heading();
	}

	// 정리. 강조색 면으로 마지막 장을 구분한다

	private Slide closing(Section section) {
		out = new ArrayList<>();
		String background = strong();
		String ink = Theme.contrast(background, theme.background()) >= 4.5 ? theme.background()
				: Theme.readable(background);
		String soft = Theme.mix(ink, background, 0.22);
		if (Theme.contrast(background, soft) < 4.5) {
			soft = ink;
		}
		String mark = Theme.contrast(background, theme.accent()) >= 3 ? theme.accent() : ink;
		String rule = Theme.mix(background, ink, 0.22);
		background(background);

		Fit title = fit(section.title(), 360, 48, 32, 3, true);
		int left = 120;
		text(MARGIN, left, 360, 26, "SUMMARY", 19, 700, mark, "left", 1.3);
		text(MARGIN, left + 40,
				360, title.height(1.25), section.title(), title.size(), 800, ink, "left", 1.25);
		int y = left + 40 + title.height(1.25) + 28;
		rect(MARGIN, y, 64, 6, mark, 0);
		if (!section.message().isEmpty()) {
			Fit message = fit(section.message(), 360, 22, 18, 4, false);
			text(MARGIN, y + 34, 360, message.height(1.5), section.message(), message.size(), 400, soft, "left", 1.5);
		}

		List<Point> points = section.points();
		int x = 540;
		int width = WIDTH - MARGIN - x;
		List<ClosingRow> rows = closingRows(points, width - 64, 24, 19);
		int total = rows.stream().mapToInt(ClosingRow::height).sum();
		if (total + 20 * rows.size() > HEIGHT - 40) {
			rows = closingRows(points, width - 64, 18, 15);
			total = rows.stream().mapToInt(ClosingRow::height).sum();
		}
		int pad = Math.min(28, Math.max(0, (HEIGHT - 40 - total) / (rows.size() * 2)));
		int top = (HEIGHT - total - pad * 2 * rows.size()) / 2;
		for (int index = 0; index < rows.size(); index++) {
			ClosingRow point = rows.get(index);
			Fit head = point.head();
			Fit body = point.body();
			int row = top + pad;
			text(x, row, 48, 30, two(index + 1), 22, 800, mark, "left", 1.3);
			text(x + 64, row, width - 64, head.height(1.3), point.value().head(), head.size(),
					700, ink, "left", 1.3);
			text(x + 64, row + head.height(1.3) + 8, width - 64, body.height(1.5), point.value().body(), body.size(),
					400, soft, "left", 1.5);
			top = row + point.height() + pad;
			if (index < rows.size() - 1) {
				rect(x, top, width, 1, rule, 0);
			}
		}
		return new Slide("summary", out);
	}

	private record ClosingRow(Point value, Fit head, Fit body) {
		int height() {
			return head.height(1.3) + 8 + body.height(1.5);
		}
	}

	private static List<ClosingRow> closingRows(List<Point> points, int width, int headSize, int bodySize) {
		return points.stream().map(point -> new ClosingRow(point,
				fit(point.head(), width, headSize, 18, 2, true),
				fit(point.body(), width, bodySize, 15, 3, false))).toList();
	}

	// 요소

	private void background(String fill) {
		rect(0, 0, WIDTH, HEIGHT, fill, 0);
	}

	private void rect(int x, int y, int width, int height, String fill, int radius) {
		ObjectNode style = json.createObjectNode().put("fill", fill).putNull("stroke").put("strokeWidth", 0);
		if (radius > 0) {
			style.put("cornerRadius", radius);
		}
		out.add(new Element("shape", x, y, width, height, json.createObjectNode().put("shape", "rect"), style));
	}

	private void text(int x, int y, int width, int height, String value, int size, int weight, String color,
			String align, double lineHeight) {
		value = broken(value, width, size, weight >= 600);
		ObjectNode style = json.createObjectNode().put("fontFamily", theme.font()).put("fontSize", size)
			.put("fontWeight", weight).put("color", color).put("align", align).put("lineHeight", lineHeight);
		out.add(new Element("text", x, y, width, height, json.createObjectNode().put("text", value), style));
	}

	private static String two(int number) {
		return String.format("%02d", number);
	}

	// 글자 맞춤

	record Fit(int size, int lines) {

		int height(double lineHeight) {
			return (int) Math.ceil(size * lineHeight * lines) + 4;
		}
	}

	// 줄 수가 maxLines 안에 드는 가장 큰 글자 크기. 끝까지 안 맞으면 가장 작은 크기와 그때의 줄 수
	static Fit fit(String text, int width, int maxSize, int minSize, int maxLines, boolean bold) {
		for (int size = maxSize; size > minSize; size--) {
			int lines = lines(text, width, size, bold);
			if (lines <= maxLines) {
				return new Fit(size, lines);
			}
		}
		return new Fit(minSize, lines(text, width, minSize, bold));
	}

	static int lines(String text, int width, int size, boolean bold) {
		return text.lines().mapToInt(line -> wrap(line, width, size, bold).size()).sum();
	}

	// 띄어쓰기에서 줄을 바꾸고 한 줄보다 긴 낱말만 글자 단위로 나눈다. 보는 프로그램마다 다른 폭을 감안해 4%를 남긴다
	static List<String> wrap(String text, int width, int size, boolean bold) {
		double limit = width * 0.96;
		double scale = size * (bold ? 1.04 : 1.0);
		double space = 0.26 * scale;
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		double used = 0;
		for (String word : text.split(" ")) {
			double length = word.codePoints().mapToDouble(cp -> em(cp) * scale).sum();
			if (used > 0 && used + space + length <= limit) {
				line.append(' ').append(word);
				used += space + length;
				continue;
			}
			if (used > 0) {
				lines.add(line.toString());
				line.setLength(0);
				used = 0;
			}
			if (length <= limit) {
				line.append(word);
				used = length;
				continue;
			}
			for (int cp : word.codePoints().toArray()) {
				double advance = em(cp) * scale;
				if (used + advance > limit) {
					lines.add(line.toString());
					line.setLength(0);
					used = 0;
				}
				line.appendCodePoint(cp);
				used += advance;
			}
		}
		lines.add(line.toString());
		return lines;
	}

	// 낱말 단위로 줄을 미리 나눠 둔다. 화면이 한글을 글자 단위로 나눠 낱말이 두 줄로 갈리는 일을 막는다
	// 줄 수가 늘지 않는 만큼 폭을 줄여 줄 길이를 고르게 맞추고, 한 글자 낱말이 줄 끝에 남는 나눔은 피한다
	static String broken(String text, int width, int size, boolean bold) {
		List<String> lines = wrap(text, width, size, bold);
		List<String> best = lines;
		for (int narrower = width - width / 20; lines.size() > 1 && narrower > width / 2; narrower -= width / 20) {
			List<String> next = wrap(text, narrower, size, bold);
			if (next.size() != lines.size()) {
				break;
			}
			if (!dangling(next) || dangling(best)) {
				best = next;
			}
		}
		return String.join("\n", best);
	}

	private static boolean dangling(List<String> lines) {
		for (int index = 0; index < lines.size() - 1; index++) {
			String line = lines.get(index);
			int space = line.lastIndexOf(' ');
			if (space >= 0 && line.codePointCount(space + 1, line.length()) == 1) {
				return true;
			}
		}
		return false;
	}

	// Pretendard에서 잰 글자 폭을 글자 크기에 대한 비율로 묶었다
	private static double em(int cp) {
		if (cp >= 'A' && cp <= 'Z') {
			return 0.68;
		}
		if (cp >= '0' && cp <= '9') {
			return 0.62;
		}
		if (cp >= 'a' && cp <= 'z') {
			return 0.56;
		}
		if (cp < 128) {
			return 0.42;
		}
		return 0.9;
	}

}
