package com.teamplespace.presentation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 생성한 글을 1280 x 720 캔버스의 요소로 배치한다
// 디자인은 피그마 Slide Layouts 페이지의 여덟 가지 계열을 따르고, 스튜디오 템플릿 네 종류는 PPT Templates 페이지의 전용 배치를 쓴다
final class SlideLayouts {

	record Point(String head, String body, String figure) {
	}

	record Section(String layout, String title, String message, String figure, List<Point> points) {
	}

	// style은 템플릿 없이 만들 때 제공자가 고른 디자인 계열이다
	// items는 본문 슬라이드마다 속한 구성 항목 번호다. 1부터 시작하고 한 항목이 여러 슬라이드를 가질 수 있다
	record Deck(String title, String subtitle, String style, List<Section> slides, Section closing, List<Integer> items) {

		Deck(String title, String subtitle, String style, List<Section> slides, Section closing) {
			this(title, subtitle, style, slides, closing,
					IntStream.rangeClosed(1, slides.size()).boxed().toList());
		}

		// 구성 항목마다 첫 슬라이드. 목차와 표지는 항목 기준으로 쓴다
		List<Section> topics() {
			List<Section> topics = new ArrayList<>();
			for (int index = 0; index < slides.size(); index++) {
				if (index == 0 || !items.get(index).equals(items.get(index - 1))) {
					topics.add(slides.get(index));
				}
			}
			return topics;
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
	static final int MESSAGE_MAX = 80;
	static final int HEAD_MAX = 24;
	static final int POINT_MAX = 90;
	static final int FIGURE_MAX = 12;
	static final int POINTS_MAX = 5;

	static final List<String> LAYOUTS = List.of("cards", "list", "process", "compare", "statement");
	static final List<String> STYLES = List.of("focus", "editorial", "pop", "data", "natural", "swiss", "bento",
			"corporate");

	// 계열과 바탕, 글자, 강조색, 보조색
	record Look(String style, String background, String ink, String accent, String accent2) {
	}

	static final Map<String, Look> TEMPLATES = Map.ofEntries(
			Map.entry("clean-report", new Look("swiss", "#FFFFFF", "#141820", "#2457D6", "#2457D6")),
			Map.entry("blue-focus", new Look("focus", "#FFFFFF", "#141A26", "#285BDD", "#F27C36")),
			Map.entry("orange-pitch", new Look("pop", "#FFF7E8", "#1D1B19", "#FF7A2F", "#FFD23F")),
			Map.entry("green-insight", new Look("natural", "#F3EEE4", "#1F2A24", "#2F5D46", "#C9734A")),
			Map.entry("mono-editorial", new Look("editorial", "#F6F3EC", "#111111", "#E8611F", "#E8611F")),
			Map.entry("lilac-studio", new Look("bento", "#F4F1FA", "#221B33", "#7B5CD6", "#C4EEDF")),
			Map.entry("data-briefing", new Look("data", "#0E1A30", "#FFFFFF", "#7FA8FF", "#FFC857")),
			Map.entry("warm-seminar", new Look("natural", "#FAF5E9", "#2B211B", "#624738", "#C9734A")),
			Map.entry("team-story", new Look("focus", "#FFFFFF", "#20242D", "#285BDD", "#F27C36")),
			Map.entry("editorial", new Look("editorial", "#F8F4EB", "#28232B", "#B4532A", "#B4532A")),
			Map.entry("pitch-dark", new Look("data", "#121827", "#FFFFFF", "#A9CBFF", "#FFC857")),
			Map.entry("warm-story", new Look("pop", "#FFF1E3", "#2A1D18", "#D9663F", "#FFC95C")),
			Map.entry("grid-analysis", new Look("corporate", "#FFFFFF", "#17241F", "#174C43", "#D9A928")),
			Map.entry("bold-cover", new Look("swiss", "#FFFFFF", "#18181C", "#C62A1C", "#FCE25B")),
			Map.entry("burgundy-editorial", new Look("burgundy", "#F3F0E8", "#1C1C1A", "#682A35", "#682A35")),
			Map.entry("tech-pitch", new Look("signal", "#121819", "#FFFFFF", "#D5F45B", "#7FD3FF")),
			Map.entry("open-campus", new Look("campus", "#F8F5EF", "#142E68", "#285BDD", "#FF6B4A")),
			Map.entry("field-notes", new Look("fieldnotes", "#F2F4E9", "#254E40", "#254E40", "#E8D57D")));

	// 템플릿 없이 만들 때 계열마다 대표색을 강조색으로 쓴다
	static Look custom(String style, String primary) {
		return switch (STYLES.contains(style) ? style : "focus") {
			case "editorial" -> new Look("editorial", "#F6F3EC", "#111111", primary, primary);
			case "pop" -> new Look("pop", "#FFF7E8", "#1D1B19", primary, "#FFD23F");
			case "data" -> new Look("data", Theme.mix(primary, "#000000", 0.82), "#FFFFFF",
					Theme.mix(primary, "#FFFFFF", 0.45), "#FFC857");
			case "natural" -> new Look("natural", "#F3EEE4", "#1F2A24", primary, "#C9734A");
			case "swiss" -> new Look("swiss", "#FFFFFF", "#111111", primary, primary);
			case "bento" -> new Look("bento", "#F4F1FA", "#221B33", primary, "#C4EEDF");
			case "corporate" -> new Look("corporate", "#FFFFFF", "#1A2433", primary, "#C9A227");
			default -> new Look("focus", "#FFFFFF", "#141A26", primary, "#F27C36");
		};
	}

	// 색 역할. 글자색은 놓일 바탕과 대비를 맞춰 둔다
	record Theme(String font, String display, String style, String bg, String ink, String body, String muted,
			String surface, String line, String accent, String deep, String soft, String accentText, String onAccent,
			String onAccentSoft, String accent2, String onAccent2, String dark, String dark2, String onDark,
			String onDarkSoft, String darkMark) {

		static final String LIGHT = "#FFFFFF";
		static final String DARK = "#141A22";

		// display는 큰 제목 글꼴이다. 프로젝트 글꼴을 고르면 모두 그 글꼴로 쓴다
		static Theme of(String font, String display, Look look) {
			String bg = look.background();
			boolean night = luminance(bg) < 0.2;
			String ink = contrast(bg, look.ink()) >= 7 ? look.ink() : readable(bg);
			String surface = night ? mix(bg, LIGHT, 0.07) : mix(bg, look.accent(), 0.06);
			String body = guard(mix(ink, bg, 0.16), 4.5, ink, bg, surface);
			String muted = guard(mix(ink, bg, 0.38), 4.5, body, bg, surface);
			// 대표색이 바탕과 거의 같으면 보이도록 진하게 또는 밝게 바꾼다
			String accent = contrast(bg, look.accent()) < 1.6
					? mix(look.accent(), night ? LIGHT : "#000000", 0.55) : look.accent();
			String deep = mix(accent, "#000000", 0.28);
			String soft = night ? mix(accent, bg, 0.6) : mix(accent, LIGHT, 0.74);
			String accentText = contrast(bg, accent) >= 3.4 && contrast(surface, accent) >= 3.4 ? accent : ink;
			String onAccent = readable(accent);
			String onAccentSoft = guard(mix(onAccent, accent, 0.2), 4.5, onAccent, accent);
			String dark = night ? mix(accent, LIGHT, 0.86) : mix(ink, accent, 0.1);
			String dark2 = mix(dark, night ? "#000000" : LIGHT, 0.08);
			String onDark = readable(dark);
			String onDarkSoft = guard(mix(onDark, dark, 0.28), 4.5, onDark, dark, dark2);
			String darkMark = guard(night ? mix(accent, "#000000", 0.35) : mix(accent, LIGHT, 0.35), 3.4, onDark,
					dark, dark2);
			return new Theme(font, display, look.style(), bg, ink, body, muted, surface, mix(bg, ink, night ? 0.2 : 0.14),
					accent, deep, soft, accentText, onAccent, onAccentSoft, look.accent2(), readable(look.accent2()),
					dark, dark2, onDark, onDarkSoft, darkMark);
		}

		static String readable(String background) {
			return contrast(background, LIGHT) >= contrast(background, DARK) ? LIGHT : DARK;
		}

		// 모든 바탕과 대비가 min 이상이면 color, 아니면 fallback
		static String guard(String color, double min, String fallback, String... backgrounds) {
			for (String background : backgrounds) {
				if (contrast(background, color) < min) {
					return fallback;
				}
			}
			return color;
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

		static double luminance(String hex) {
			int rgb = Integer.parseInt(hex.substring(1), 16);
			return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8 & 0xFF) + 0.0722 * channel(rgb & 0xFF);
		}

		private static double channel(int value) {
			double c = value / 255.0;
			return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
		}
	}

	// sources는 구성 항목마다 출처 문구이고 없으면 null이다
	static List<Slide> build(ObjectMapper json, Deck deck, Theme theme, List<String> sources) {
		Design design = switch (theme.style()) {
			case "editorial" -> new Editorial();
			case "pop" -> new Pop();
			case "data" -> new Data();
			case "natural" -> new Natural();
			case "swiss" -> new Swiss();
			case "bento" -> new Bento();
			case "corporate" -> new Corporate();
			case "burgundy" -> new Burgundy();
			case "signal" -> new Signal();
			case "campus" -> new Campus();
			case "fieldnotes" -> new Fieldnotes();
			default -> new Focus();
		};
		design.json = json;
		design.t = theme;
		design.deck = deck;
		List<Slide> slides = new ArrayList<>();
		design.begin();
		design.cover(deck.title(), deck.subtitle());
		slides.add(design.finish("title"));
		if (deck.topics().size() >= 3) {
			design.begin();
			List<String> titles = deck.topics().stream().map(Section::title).toList();
			if (titles.size() <= design.agendaLimit()) {
				design.agenda(titles);
			}
			else {
				design.longAgenda(titles);
			}
			slides.add(design.finish("agenda"));
		}
		String previous = null;
		for (int index = 0; index < deck.slides().size(); index++) {
			int item = deck.items().get(index);
			Slide slide = design.content(deck.slides().get(index), item, sources.get(item - 1), slides.size() + 1,
					previous);
			previous = slide.layout();
			slides.add(slide);
		}
		design.begin();
		design.closing(deck.closing());
		slides.add(design.finish("summary"));
		return slides;
	}

	// 요청한 배치를 먼저 쓰고 항목 수가 맞지 않거나 넘치면 다음 배치로 바꾼다. 같은 배치가 이어지면 카드와 목록을 번갈아 쓴다
	static List<String> candidates(Section section, String previous) {
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
			case "compare", "statement", "process" -> List.of("cards", "list");
			default -> List.of("list");
		});
		List<String> result = new ArrayList<>(order.stream().distinct().filter(usable).toList());
		// 넘겨받은 배치가 앞 슬라이드와 같으면 다음 후보를 먼저 쓴다
		if (result.size() > 1 && result.get(0).equals(previous)) {
			result.add(1, result.remove(0));
		}
		return result;
	}

	// 디자인 계열의 공통 도구. 좌표는 1280 x 720 기준이다
	private abstract static class Design {

		ObjectMapper json;
		Theme t;
		Deck deck;
		List<Element> out;
		static final int BOTTOM = 640;

		void begin() {
			out = new ArrayList<>();
		}

		Slide finish(String layout) {
			return new Slide(layout, out);
		}

		abstract void cover(String title, String subtitle);

		abstract void agenda(List<String> titles);

		int agendaLimit() {
			return 6;
		}

		abstract boolean statement(Section s, int no);

		abstract boolean cards(Section s, int no);

		abstract boolean compare(Section s, int no);

		abstract boolean process(Section s, int no);

		abstract boolean list(Section s, int no);

		abstract void closing(Section s);

		abstract void footer(String source, int page);

		Slide content(Section s, int no, String source, int page, String previous) {
			for (String layout : candidates(s, previous)) {
				begin();
				boolean fits = switch (layout) {
					case "cards" -> cards(s, no);
					case "process" -> process(s, no);
					case "compare" -> compare(s, no);
					case "statement" -> statement(s, no);
					default -> list(s, no);
				};
				if (fits) {
					footer(source, page);
					return finish(layout);
				}
			}
			begin();
			plainList(s, no);
			footer(source, page);
			return finish("list");
		}

		// 계열 배치에 넘칠 때 쓰는 기본 목록. 가장 작은 글자에서도 들어가게 핵심 문장을 뺀다
		void plainList(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 44, 200, two(no), 19, 19, 1, 800, t.accentText(), "left", 1.3);
			int y = 80 + tx(64, 80, 1152, s.title(), 36, 26, 2, 800, t.ink(), "left", 1.25) + 24;
			int row = (BOTTOM - y) / s.points().size();
			for (int i = 0; i < s.points().size(); i++) {
				Point p = s.points().get(i);
				int top = y + i * row;
				rect(64, top, 1152, 1, t.line());
				tx(64, top + 12, 56, two(i + 1), 19, 19, 1, 800, t.accentText(), "left", 1.3);
				tx(128, top + 12, 300, p.head(), 20, 15, 2, 700, t.ink(), "left", 1.3);
				tx(460, top + 12, 756, p.body(), 17, 14, 3, 400, t.body(), "left", 1.45);
			}
		}

		// 항목이 많은 목차. 두 단으로 나눈다
		void longAgenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 60, 300, "CONTENTS", 19, 19, 1, 800, t.accentText(), "left", 1.3);
			dx(64, 96, 300, "목차", 56, 56, 1, t.ink(), "left", 1.2);
			int rows = (titles.size() + 1) / 2;
			int row = Math.min(64, 520 / rows);
			int top = (HEIGHT - row * rows) / 2;
			for (int i = 0; i < titles.size(); i++) {
				int x = 440 + i / rows * 400;
				int y = top + i % rows * row;
				tx(x, y + (row - 26) / 2, 48, two(i + 1), 19, 19, 1, 800, t.accentText(), "left", 1.3);
				Fit f = fit(titles.get(i), 320, 20, 14, 2, 600, false);
				put(x + 52, y + (row - f.height(1.3)) / 2, 320, titles.get(i), f, 600, t.ink(), "left", 1.3, false);
				rect(x, y + row - 1, 372, 1, t.line());
			}
		}

		// 요소

		void rect(int x, int y, int w, int h, String fill) {
			box(x, y, w, h, fill, 0, null, 0);
		}

		void rect(int x, int y, int w, int h, String fill, int radius) {
			box(x, y, w, h, fill, radius, null, 0);
		}

		void box(int x, int y, int w, int h, String fill, int radius, String stroke, int strokeWidth) {
			ObjectNode style = json.createObjectNode().put("fill", fill).put("stroke", stroke).put("strokeWidth",
					strokeWidth);
			if (radius > 0) {
				style.put("cornerRadius", Math.min(radius, Math.min(w, h) / 2));
			}
			out.add(new Element("shape", x, y, w, h, json.createObjectNode().put("shape", "rect"), style));
		}

		void oval(int x, int y, int w, int h, String fill) {
			ring(x, y, w, h, fill, null, 0);
		}

		void ring(int x, int y, int w, int h, String fill, String stroke, int strokeWidth) {
			ObjectNode style = json.createObjectNode().put("fill", fill).put("stroke", stroke).put("strokeWidth",
					strokeWidth);
			out.add(new Element("shape", x, y, w, h, json.createObjectNode().put("shape", "ellipse"), style));
		}

		// 본문 글꼴 글상자. 넣은 글상자 높이를 돌려준다
		int tx(int x, int y, int w, String value, int max, int min, int lines, int weight, String color, String align,
				double lh) {
			Fit f = fit(value, w, max, min, lines, weight, false);
			put(x, y, w, value, f, weight, color, align, lh, false);
			return f.height(lh);
		}

		// 제목 글꼴 글상자
		int dx(int x, int y, int w, String value, int max, int min, int lines, String color, String align, double lh) {
			Fit f = fit(value, w, max, min, lines, dweight(), true);
			put(x, y, w, value, f, dweight(), color, align, lh, true);
			return f.height(lh);
		}

		boolean display() {
			return !t.display().equals(t.font());
		}

		int dweight() {
			return display() ? 700 : 800;
		}

		Fit fit(String value, int w, int max, int min, int lines, int weight, boolean title) {
			return SlideLayouts.fit(value, w, max, min, lines, weight >= 600, title && display());
		}

		void put(int x, int y, int w, String value, Fit f, int weight, String color, String align, double lh,
				boolean title) {
			boolean wide = title && display();
			int used = title && display() ? 700 : weight;
			ObjectNode style = json.createObjectNode().put("fontFamily", wide ? t.display() : t.font())
				.put("fontSize", f.size()).put("fontWeight", used).put("color", color).put("align", align)
				.put("lineHeight", lh);
			String text = broken(value, w, f.size(), used >= 600, wide);
			out.add(new Element("text", x, y, w, f.height(lh), json.createObjectNode().put("text", text), style));
		}

		// 좁은 칸에 한 줄로 들어가는 수치만 쓰고, 안 들어가면 대신 쓸 글을 돌려준다
		String fits(String value, int w, int min, String fallback) {
			return !value.isEmpty() && fit(value, w, min, min, 1, 800, false).lines() == 1 ? value : fallback;
		}

		static String two(int number) {
			return String.format("%02d", number);
		}

		String footerTitle(String source) {
			return source != null ? source : deck.title();
		}

		// 꼬리말 글자색. 슬라이드 바탕과 대비를 맞춘다
		String footColor() {
			String fill = out.get(0).style().get("fill").asString();
			String fallback = Theme.guard(Theme.mix(Theme.readable(fill), fill, 0.3), 4.5, Theme.readable(fill), fill);
			return Theme.guard(t.muted(), 4.5, fallback, fill);
		}

		// 선으로 나눈 항목. 넘치면 글자를 줄이고, 그래도 넘치면 두 단으로 나눈다
		void ruled(List<Point> points, int x, int w, int top, int bottom, String rule, String head, String body,
				String mark) {
			int[][] sizes = { { 30, 20, 3 }, { 24, 17, 3 }, { 20, 15, 3 }, { 17, 13, 3 }, { 16, 12, 3 } };
			for (int columns = 1; columns <= 2; columns++) {
				int cw = columns == 1 ? w : (w - 32) / 2;
				int rows = (points.size() + columns - 1) / columns;
				for (int[] size : sizes) {
					int tallest = 0;
					for (int c = 0; c < columns; c++) {
						int total = 0;
						for (int i = c * rows; i < Math.min(points.size(), (c + 1) * rows); i++) {
							total += item(points.get(i), cw, size);
						}
						tallest = Math.max(tallest, total);
					}
					boolean last = columns == 2 && size == sizes[sizes.length - 1];
					if (tallest > bottom - top && !last) {
						continue;
					}
					int gap = Math.max(0, Math.min(48, (bottom - top - tallest) / rows));
					for (int c = 0; c < columns; c++) {
						int y = top;
						for (int i = c * rows; i < Math.min(points.size(), (c + 1) * rows); i++) {
							Point p = points.get(i);
							int cx = x + c * (cw + 32);
							rect(cx, y, cw, 2, rule);
							tx(cx, y + 18, 56, two(i + 1), 20, 16, 1, 900, mark, "left", 1.3);
							int h = tx(cx + 64, y + 16, cw - 64, p.head(), size[0], size[0] - 3, 1, 800, head, "left",
									1.3);
							int b = tx(cx + 64, y + 22 + h, cw - 64, p.body(), size[1], size[1], size[2], 500, body,
									"left", 1.5);
							y += 22 + h + b + gap;
						}
					}
					return;
				}
			}
		}

		int item(Point p, int w, int[] size) {
			return 22 + fit(p.head(), w - 64, size[0], size[0] - 3, 1, 800, false).height(1.3)
					+ fit(p.body(), w - 64, size[1], size[1], size[2], 500, false).height(1.5);
		}

		// 정리 카드에 들어가는지 본다
		boolean cardsFit(List<Point> points, int inner, int headSize, int headLines, int bodySize, int bodyMin,
				int bodyLines, int room) {
			for (Point p : points) {
				Fit head = fit(p.head(), inner, headSize, headSize - 8, headLines, 800, false);
				Fit body = fit(p.body(), inner, bodySize, bodyMin, bodyLines, 400, false);
				if (head.lines() > headLines || body.lines() > bodyLines
						|| head.height(1.3) + 8 + body.height(1.55) > room) {
					return false;
				}
			}
			return true;
		}
	}

	// 스튜디오 템플릿 공통. 피그마 PPT Templates 페이지의 본문 배치를 따른다
	// 위 왼쪽에 작은 라벨, 큰 제목과 설명, 아래 선 위에 출처와 쪽 번호를 둔다

	private abstract static class Studio extends Design {

		// 면 위의 옅은 선
		String lineOn(String fill) {
			return Theme.mix(fill, Theme.readable(fill), 0.2);
		}

		String label(int no) {
			return "SECTION " + two(no);
		}

		int header(Section s, int no) {
			tx(64, 48, 1100, label(no), 18, 16, 1, 600, t.accentText(), "left", 1.44);
			int y = 84 + dx(64, 84, 1152, s.title(), 48, 32, 2, t.ink(), "left", 1.29);
			if (!s.message().isEmpty()) {
				y += 12 + tx(64, y + 12, 1152, s.message(), 24, 17, 2, 400, t.muted(), "left", 1.5);
			}
			return y + 48;
		}

		// 번호, 선, 소제목, 설명을 단으로 놓는다
		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int w = (1152 - 48 * (n - 1)) / n;
			for (Point p : s.points()) {
				if (top + 110 + fit(p.head(), w, 30, 20, 2, 600, false).height(1.4) + 16
						+ fit(p.body(), w, 22, 15, 4, 400, false).height(1.5) > BOTTOM) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + 48);
				tx(x, top, w, two(i + 1), 64, 64, 1, 700, t.accentText(), "left", 1.08);
				rect(x, top + 86, w, 2, t.accent());
				int h = tx(x, top + 110, w, p.head(), 30, 20, 2, 600, t.ink(), "left", 1.4);
				tx(x, top + 126 + h, w, p.body(), 22, 15, 4, 400, t.body(), "left", 1.5);
			}
			return true;
		}

		// 한 줄 선 위에 단계 표시를 두고 단계마다 글을 단으로 놓는다
		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int w = (1152 - 32 * (n - 1)) / n;
			for (Point p : s.points()) {
				if (top + 82 + fit(p.head(), w, 28, 18, 2, 600, false).height(1.4) + 14
						+ fit(p.body(), w, 20, 14, 4, 400, false).height(1.5) > BOTTOM) {
					return false;
				}
			}
			rect(64, top + 8, 1152, 2, lineOn(t.bg()));
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + 32);
				rect(x, top, 18, 18, t.accent());
				tx(x, top + 44, w, "STEP " + two(i + 1), 18, 18, 1, 600, t.accentText(), "left", 1.44);
				int h = tx(x, top + 82, w, p.head(), 28, 18, 2, 600, t.ink(), "left", 1.4);
				tx(x, top + 96 + h, w, p.body(), 20, 14, 4, 400, t.body(), "left", 1.5);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			if (top > 420) {
				return false;
			}
			ruled(s.points(), 64, 1152, top, BOTTOM, lineOn(t.bg()), t.ink(), t.body(), t.accentText());
			return true;
		}

		// 왼쪽 굵은 선 옆에 핵심 문장, 오른쪽에 요점을 선으로 나눠 둔다
		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 48, 1100, label(no) + "  " + s.title(), 18, 15, 1, 600, t.accentText(), "left", 1.44);
			Fit f = fit(s.message(), 600, 48, 32, 5, 800, true);
			if (140 + f.height(1.29) > BOTTOM) {
				return false;
			}
			int y = 140;
			for (Point p : s.points()) {
				y += 26 + 12 + fit(p.body(), 460, 20, 14, 3, 400, false).height(1.5) + 32;
			}
			if (y > BOTTOM + 32) {
				return false;
			}
			rect(64, 140, 5, Math.max(80, f.height(1.29)), t.accent());
			put(102, 140, 600, s.message(), f, 800, t.ink(), "left", 1.29, true);
			y = 140;
			for (Point p : s.points()) {
				tx(756, y, 460, p.head(), 18, 14, 1, 600, t.accentText(), "left", 1.44);
				int b = tx(756, y + 38, 460, p.body(), 20, 14, 3, 400, t.ink(), "left", 1.5);
				rect(756, y + 38 + b + 16, 460, 1, lineOn(t.bg()));
				y += 38 + b + 32;
			}
			return true;
		}

		// 두 면을 나란히 놓고 각 면에 소제목과 설명을 둔다
		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			String[] fills = { t.soft(), t.accent() };
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int w = i == 0 ? 492 : 508;
				if (top + 32 + 26 + 20 + fit(p.head(), w, 40, 24, 3, 800, true).height(1.29) + 24
						+ fit(p.body(), w, 22, 15, 5, 400, false).height(1.5) + 32 > 616) {
					return false;
				}
			}
			rect(64, top, 556, 616 - top, fills[0]);
			rect(644, top, 572, 616 - top, fills[1]);
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 96 : 676;
				int w = i == 0 ? 492 : 508;
				String ink = Theme.readable(fills[i]);
				String mark = i == 0 ? Theme.guard(t.accentText(), 4.5, ink, fills[0]) : t.onAccentSoft();
				tx(x, top + 32, w, two(i + 1), 18, 18, 1, 600, mark, "left", 1.44);
				int h = dx(x, top + 78, w, p.head(), 40, 24, 3, ink, "left", 1.29);
				tx(x, top + 102 + h, w, p.body(), 22, 15, 5, 400, ink, "left", 1.5);
			}
			return true;
		}

		// 강조색 바탕에 큰 문장과 요점 단
		@Override
		void closing(Section s) {
			closingOn(t.accent(), t.onAccent(), t.onAccentSoft(), "SUMMARY");
			closingBody(s, t.accent(), t.onAccent(), t.onAccentSoft());
		}

		void closingOn(String fill, String ink, String soft, String label) {
			rect(0, 0, WIDTH, HEIGHT, fill);
			tx(64, 56, 1100, label, 18, 18, 1, 600, soft, "left", 1.44);
		}

		// 요점이 단으로 들어가면 큰 문장 아래에, 아니면 왼쪽 큰 문장과 오른쪽 목록으로 나눈다
		void closingBody(Section s, String fill, String ink, String soft) {
			String big = s.message().isEmpty() ? s.title() : s.message();
			String line = Theme.mix(fill, ink, 0.4);
			Fit wide = fit(big, 1152, 60, 30, 3, dweight(), true);
			int y = 140 + wide.height(1.3) + 48;
			if (s.points().isEmpty() || columnsFit(s.points(), y + 32, 690)) {
				put(64, 140, 1152, big, wide, dweight(), ink, "left", 1.3, true);
				if (!s.points().isEmpty()) {
					rect(64, y, 1152, 1, line);
					columns(s.points(), y + 32, ink, soft);
				}
				return;
			}
			dx(64, 140, 560, big, 56, 30, 5, ink, "left", 1.2);
			ruled(s.points(), 700, 516, 140, 690, line, ink, soft, soft);
		}

		boolean columnsFit(List<Point> points, int top, int bottom) {
			int w = (1152 - 32 * (points.size() - 1)) / points.size();
			for (Point p : points) {
				Fit h = fit(p.head(), w, 18, 14, 2, 600, false);
				Fit b = fit(p.body(), w, 24, 14, 8, 600, false);
				if (h.lines() > 2 || b.lines() > 8 || h.height(1.44) + 12 + b.height(1.4) > bottom - top) {
					return false;
				}
			}
			return true;
		}

		void columns(List<Point> points, int top, String ink, String soft) {
			int w = (1152 - 32 * (points.size() - 1)) / points.size();
			for (int i = 0; i < points.size(); i++) {
				Point p = points.get(i);
				int x = 64 + i * (w + 32);
				int h = tx(x, top, w, p.head(), 18, 14, 2, 600, soft, "left", 1.44);
				tx(x, top + 12 + h, w, p.body(), 24, 14, 8, 600, ink, "left", 1.4);
			}
		}

		@Override
		void footer(String source, int page) {
			String fill = out.get(0).style().get("fill").asString();
			rect(64, 650, 1152, 1, lineOn(fill));
			String color = footColor();
			if (source != null) {
				tx(64, 664, 900, source, 16, 12, 1, 400, color, "left", 1.44);
			}
			tx(1016, 664, 200, two(page), 16, 16, 1, 400, color, "right", 1.44);
		}
	}

	// 버건디 편집형. 아이보리 바탕, 버건디 선과 띠, 숫자를 크게 쓰는 보고서

	private static final class Burgundy extends Studio {

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 54, 1100, "TEAM REPORT", 18, 18, 1, 600, t.accentText(), "left", 1.44);
			rect(64, 100, 1152, 1, t.accent());
			Fit head = fit(title, 680, 72, 40, 4, 800, true);
			put(64, 140, 680, title, head, 800, t.accentText(), "left", 1.15, true);
			rect(776, 150, 1, 345, lineOn(t.bg()));
			tx(824, 160, 392, subtitle, 28, 18, 6, 400, t.body(), "left", 1.5);
			rect(0, 570, WIDTH, 150, t.accent());
			tx(64, 620, 1152, "TEAM PRESENTATION", 24, 24, 1, 600, t.onAccent(), "left", 1.4);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			dx(64, 86, 520, "Contents", 88, 88, 1, t.accentText(), "left", 1.14);
			tx(64, 232, 460, "발표 순서", 30, 30, 1, 600, t.ink(), "left", 1.4);
			int row = Math.min(125, 560 / titles.size());
			for (int i = 0; i < titles.size(); i++) {
				int y = 60 + i * row;
				Fit f = fit(titles.get(i), 456, 30, 18, 2, 600, false);
				tx(650, y + (row - 42) / 2, 100, two(i + 1), 30, 30, 1, 600, t.accentText(), "left", 1.4);
				put(760, y + (row - f.height(1.4)) / 2, 456, titles.get(i), f, 600, t.ink(), "left", 1.4, false);
				rect(650, y + row - 1, 566, 1, lineOn(t.bg()));
			}
		}

		// 수치가 있으면 왼쪽에 크게 쓰고 오른쪽에 문장과 요점을 둔다
		@Override
		boolean statement(Section s, int no) {
			String figure = s.figure().isEmpty() ? "" : fits(s.figure(), 490, 72, "");
			if (figure.isEmpty()) {
				return super.statement(s, no);
			}
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 54, 1100, label(no) + "  " + s.title(), 18, 15, 1, 600, t.accentText(), "left", 1.44);
			int y = 108 + dx(64, 108, 1120, s.message(), 48, 30, 2, t.ink(), "left", 1.29) + 48;
			if (y > 330) {
				return false;
			}
			int right = y;
			for (Point p : s.points()) {
				right += 52 + fit(p.body(), 570, 20, 14, 3, 400, false).height(1.5) + 16;
			}
			if (right > BOTTOM) {
				return false;
			}
			tx(64, y, 490, figure, 120, 72, 1, 400, t.accentText(), "left", 1.1);
			rect(602, y, 614, 1, lineOn(t.bg()));
			for (Point p : s.points()) {
				int h = tx(630, y + 16, 570, p.head(), 26, 18, 1, 600, t.ink(), "left", 1.4);
				int b = tx(630, y + 24 + h, 570, p.body(), 20, 14, 3, 400, t.muted(), "left", 1.5);
				y += 24 + h + b + 16;
			}
			return true;
		}

		// 흰 면을 오른쪽에 깔고 두 쪽을 나란히 비교한다
		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				if (top + 32 + fit(p.head(), 420, 30, 20, 2, 600, false).height(1.4) + 32
						+ fit(p.body(), 420, 24, 15, 6, 400, false).height(1.5) > 600) {
					return false;
				}
			}
			rect(700, top, 516, 600 - top + 24, Theme.LIGHT);
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 64 : 732;
				String fill = i == 0 ? t.bg() : Theme.LIGHT;
				String head = Theme.guard(i == 0 ? t.muted() : t.accentText(), 4.5, t.ink(), fill);
				int h = tx(x, top + 24, 420, p.head(), 30, 20, 2, 600, head, "left", 1.4);
				rect(x, top + 40 + h, 420, 1, lineOn(t.bg()));
				tx(x, top + 56 + h, 420, p.body(), 24, 15, 6, 400, t.ink(), "left", 1.5);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			closingOn(t.accent(), t.onAccent(), t.onAccentSoft(), "NEXT STEP");
			closingBody(s, t.accent(), t.onAccent(), t.onAccentSoft());
		}
	}

	// 테크 피치. 차콜 바탕, 라임 강조, 큰 숫자와 단계 흐름

	private static final class Signal extends Studio {

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 52, 1100, "TEAM PRESENTATION / PITCH", 18, 18, 1, 600, t.muted(), "left", 1.44);
			int y = 150 + dx(64, 150, 1100, title, 80, 44, 3, t.ink(), "left", 1.12) + 40;
			tx(64, y, 900, subtitle, 36, 22, Math.max(1, Math.min(3, (580 - y) / 46)), 700, t.accentText(), "left",
					1.29);
			rect(64, 594, 1152, 2, lineOn(t.bg()));
			tx(64, 630, 900, "함께 만든 발표", 20, 20, 1, 400, t.muted(), "left", 1.5);
		}

		// 네 개까지는 한 줄, 그보다 많으면 세 개씩 두 줄로 놓는다
		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			dx(64, 64, 1152, "오늘 이야기할 것", 48, 48, 1, t.ink(), "left", 1.29);
			int n = titles.size();
			int perRow = n <= 4 ? n : 3;
			boolean single = n <= 4;
			int w = (1152 - 52 * (perRow - 1)) / perRow;
			for (int i = 0; i < n; i++) {
				int x = 64 + i % perRow * (w + 52);
				int y = single ? 266 : 190 + i / perRow * 230;
				int number = single ? 80 : 56;
				tx(x, y, w, two(i + 1), number, number, 1, 700, t.accentText(), "left", 1.08);
				rect(x, y + number + 32, w, 1, lineOn(t.bg()));
				tx(x, y + number + 56, w, titles.get(i), single ? 30 : 24, 16, single ? 4 : 2, 600, t.ink(), "left",
						1.4);
			}
		}

		// 왼쪽은 바탕에, 오른쪽은 라임 면에 두고 수치가 있으면 크게 쓴다
		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				String figure = fits(p.figure(), 470, 48, "");
				int need = 28 + fit(p.head(), 470, 30, 20, 2, 600, false).height(1.4) + 24
						+ (figure.isEmpty() ? 0 : 112) + fit(p.body(), 470, 24, 15, 5, 400, false).height(1.5) + 28;
				if (top + need > 616) {
					return false;
				}
			}
			rect(656, top, 560, 616 - top, t.accent());
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 64 : 696;
				String ink = i == 0 ? t.ink() : t.onAccent();
				String soft = i == 0 ? t.muted() : t.onAccentSoft();
				int y = top + 28;
				y += tx(x, y, 470, p.head(), 30, 20, 2, 600, soft, "left", 1.4) + 24;
				String figure = fits(p.figure(), 470, 48, "");
				if (!figure.isEmpty()) {
					y += tx(x, y, 470, figure, 96, 48, 1, 400, ink, "left", 1.12) + 12;
				}
				tx(x, y, 470, p.body(), 24, 15, 5, 400, ink, "left", 1.5);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			closingOn(t.accent(), t.onAccent(), t.onAccentSoft(), "LET’S BUILD TOGETHER");
			closingBody(s, t.accent(), t.onAccent(), t.onAccentSoft());
		}
	}

	// 오픈 캠퍼스. 코발트와 코랄, 큰 제목의 수업 발표

	private static final class Campus extends Studio {

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			rect(944, 0, 336, 720, t.accent2());
			tx(64, 52, 800, "CAMPUS PROJECT", 18, 18, 1, 600, t.onAccentSoft(), "left", 1.44);
			int y = 180 + dx(64, 180, 840, title, 80, 44, 3, t.onAccent(), "left", 1.25) + 48;
			tx(64, y, 820, subtitle, 30, 20, Math.max(1, Math.min(3, (630 - y) / 42)), 600, t.onAccent(), "left",
					1.4);
			tx(992, 78, 240, "TEAM", 18, 18, 1, 600, t.onAccent2(), "left", 1.44);
			tx(992, 150, 240, String.valueOf(deck.topics().size()), 140, 140, 1, 400, t.onAccent2(), "left", 1.12);
			tx(992, 320, 240, "가지 주제", 24, 24, 1, 400, t.onAccent2(), "left", 1.5);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			dx(64, 74, 470, "발표 순서", 64, 64, 1, t.ink(), "left", 1.28);
			tx(64, 270, 430, "우리 팀이 던진 질문과\n찾은 답을 소개합니다.", 24, 24, 2, 400, t.ink(), "left", 1.5);
			int row = Math.min(132, 540 / titles.size());
			for (int i = 0; i < titles.size(); i++) {
				int y = 70 + i * row;
				rect(568, y + 8, 6, row - 24, i % 2 == 0 ? t.accent() : t.accent2());
				tx(602, y + (row - 16 - 42) / 2, 88, two(i + 1), 30, 30, 1, 600, t.accentText(), "left", 1.4);
				Fit f = fit(titles.get(i), 496, 30, 18, 2, 600, false);
				put(720, y + (row - 16 - f.height(1.4)) / 2, 496, titles.get(i), f, 600, t.ink(), "left", 1.4, false);
				rect(600, y + row - 1, 616, 1, t.soft());
			}
		}

		// 위에 코랄 띠, 큰 문장, 아래 코발트 띠에 요점. 요점이 띠에 넘치면 공통 마무리를 쓴다
		@Override
		void closing(Section s) {
			if (!s.points().isEmpty() && !columnsFit(s.points(), 506, 696)) {
				super.closing(s);
				return;
			}
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(0, 0, WIDTH, 22, t.accent2());
			tx(64, 70, 1100, "OUR NEXT STEP", 18, 18, 1, 600, t.accentText(), "left", 1.44);
			String big = s.message().isEmpty() ? s.title() : s.message();
			dx(64, 150, 1152, big, 72, 36, 3, t.accentText(), "left", 1.25);
			rect(0, 470, WIDTH, 250, t.accent());
			if (s.points().isEmpty()) {
				tx(64, 586, 1152, "질문과 의견을 듣겠습니다.", 30, 30, 1, 600, t.onAccent(), "left", 1.4);
				return;
			}
			columns(s.points(), 506, t.onAccent(), t.onAccentSoft());
		}
	}

	// 필드 노트. 포레스트와 아이보리, 인터뷰와 비교표의 리서치 발표

	private static final class Fieldnotes extends Studio {

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(0, 0, 26, 720, t.accent());
			tx(64, 52, 1100, "RESEARCH JOURNAL", 18, 18, 1, 600, t.accentText(), "left", 1.44);
			rect(64, 102, 1152, 1, lineOn(t.bg()));
			dx(64, 150, 540, title, 64, 34, 5, t.accentText(), "left", 1.2);
			rect(638, 148, 1, 430, lineOn(t.bg()));
			tx(690, 174, 526, subtitle, 28, 18, 7, 400, t.ink(), "left", 1.5);
			tx(64, 654, 900, "관찰, 기록, 해석, 제안", 20, 20, 1, 400, t.ink(), "left", 1.5);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(0, 0, 416, 720, t.accent());
			dx(56, 110, 320, "발표의\n흐름", 56, 56, 2, t.onAccent(), "left", 1.36);
			tx(56, 472, 320, "질문에서\n제안까지", 24, 24, 2, 400, t.onAccentSoft(), "left", 1.5);
			int row = Math.min(140, 600 / titles.size());
			for (int i = 0; i < titles.size(); i++) {
				int y = 60 + i * row;
				tx(480, y + (row - 42) / 2, 100, two(i + 1), 30, 30, 1, 600, t.accentText(), "left", 1.4);
				Fit f = fit(titles.get(i), 616, 30, 18, 2, 600, false);
				put(600, y + (row - f.height(1.4)) / 2, 616, titles.get(i), f, 600, t.ink(), "left", 1.4, false);
				rect(480, y + row - 1, 736, 1, lineOn(t.bg()));
			}
		}

		// 진한 머리 행 아래에 두 대안을 같은 칸으로 놓는 비교표
		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int heads = 0;
			int bodies = 0;
			for (Point p : s.points().subList(0, 2)) {
				heads = Math.max(heads, fit(p.head(), 528, 24, 16, 2, 600, false).height(1.44));
				bodies = Math.max(bodies, fit(p.body(), 528, 22, 15, 7, 400, false).height(1.5));
			}
			if (top + 36 + heads + 28 + bodies + 16 > BOTTOM) {
				return false;
			}
			rect(64, top, 1152, heads + 36, t.accent());
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = 88 + i * 576;
				tx(x, top + 18, 528, p.head(), 24, 16, 2, 600, t.onAccent(), "left", 1.44);
				tx(x, top + heads + 64, 528, p.body(), 22, 15, 7, 400, t.ink(), "left", 1.5);
				rect(64 + i * 576, top + heads + 64 + bodies + 16, 576, 1, lineOn(t.bg()));
			}
			rect(640, top + heads + 36, 1, bodies + 44, lineOn(t.bg()));
			return true;
		}

		@Override
		void closing(Section s) {
			String fill = t.accent();
			closingOn(fill, t.onAccent(), t.onAccentSoft(), "FROM RESEARCH TO ACTION");
			closingBody(s, fill, t.onAccent(), t.onAccentSoft());
		}
	}

	// 블루 포커스. 기하학 원과 강조색 면

	private static final class Focus extends Design {

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(744, 0, 536, 720, t.accent());
			oval(860, 120, 420, 420, t.deep());
			oval(1010, 330, 360, 360, Theme.mix(t.accent(), Theme.LIGHT, 0.12));
			ring(820, 470, 170, 170, null, t.soft(), 3);
			rect(72, 72, 48, 8, t.accent());
			tx(72, 104, 600, "TEAM PRESENTATION", 16, 16, 1, 700, t.accentText(), "left", 1.4);
			Fit head = fit(title, 620, 72, 44, 3, 800, true);
			Fit sub = fit(subtitle, 600, 24, 18, 3, 400, false);
			int top = Math.max(160, (HEIGHT - head.height(1.2) - 36 - sub.height(1.5)) / 2);
			put(72, top, 620, title, head, 800, t.ink(), "left", 1.2, true);
			put(72, top + head.height(1.2) + 36, 600, subtitle, sub, 400, t.body(), "left", 1.5, false);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(0, 0, 440, 720, t.accent());
			oval(-120, 440, 420, 420, t.deep());
			tx(72, 96, 300, "CONTENTS", 18, 18, 1, 700, t.onAccentSoft(), "left", 1.4);
			dx(72, 128, 300, "목차", 72, 72, 1, t.onAccent(), "left", 1.2);
			int row = Math.min(100, 560 / titles.size());
			int top = (HEIGHT - row * titles.size()) / 2 + 10;
			for (int i = 0; i < titles.size(); i++) {
				int y = top + i * row;
				tx(520, y + (row - 16 - 52) / 2, 90, two(i + 1), 40, 30, 1, 800, t.accentText(), "left", 1.2);
				Fit f = fit(titles.get(i), 560, 28, 18, 2, 700, false);
				put(620, y + (row - 16 - f.height(1.3)) / 2, 560, titles.get(i), f, 700, t.ink(), "left", 1.3, false);
				if (i < titles.size() - 1) {
					rect(520, y + row - 8, 688, 1, t.line());
				}
			}
		}

		int header(Section s, int no) {
			tx(72, 56, 400, "SECTION " + two(no), 16, 16, 1, 700, t.accentText(), "left", 1.4);
			int y = 84 + dx(72, 84, 1100, s.title(), 44, 32, 2, t.ink(), "left", 1.25);
			if (!s.message().isEmpty()) {
				y += 8 + tx(72, y + 8, 1100, s.message(), 22, 18, 2, 400, t.muted(), "left", 1.45);
			}
			return y + 40;
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			oval(880, -180, 560, 560, Theme.mix(t.accent(), Theme.LIGHT, 0.12));
			tx(72, 56, 760, "SECTION " + two(no) + "  " + s.title(), 16, 14, 1, 700, t.onAccentSoft(), "left", 1.4);
			tx(72, 104, 160, "\u201C", 140, 140, 1, 800, t.onAccentSoft(), "left", 1.0);
			int y = 250;
			int bottom = y + dx(72, y, 780, s.message(), 60, 36, 3, t.onAccent(), "left", 1.25);
			return columns(s.points(), 72, 1136, bottom + 40, t.onAccentSoft(), t.onAccent(), t.onAccentSoft());
		}

		// 아래쪽에 선과 키워드, 설명을 단으로 놓는다
		boolean columns(List<Point> points, int x0, int width, int limit, String rule, String head, String body) {
			if (points.isEmpty()) {
				return true;
			}
			int gap = 40;
			int w = (width - gap * (points.size() - 1)) / points.size();
			int tallest = 0;
			for (Point p : points) {
				tallest = Math.max(tallest, fit(p.head(), w, 24, 18, 1, 700, false).height(1.3) + 8
						+ fit(p.body(), w, 19, 15, 3, 400, false).height(1.5));
			}
			int top = 664 - 24 - tallest;
			if (top < limit) {
				return false;
			}
			for (int i = 0; i < points.size(); i++) {
				int x = x0 + i * (w + gap);
				rect(x, top, w, 2, rule);
				int h = tx(x, top + 20, w, points.get(i).head(), 24, 18, 1, 700, head, "left", 1.3);
				tx(x, top + 28 + h, w, points.get(i).body(), 19, 15, 3, 400, body, "left", 1.5);
			}
			return true;
		}

		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.surface());
			int top = header(s, no);
			int n = s.points().size();
			int gap = 28;
			int w = (1136 - gap * (n - 1)) / n;
			int pad = n == 4 ? 28 : 36;
			int inner = w - pad * 2;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), inner, 30, 22, 2, 800, false).height(1.3) + 20
						+ fit(p.body(), inner, 20, 15, 5, 400, false).height(1.55));
			}
			int needed = 8 + pad + 64 + 32 + tallest + pad;
			if (top + needed > BOTTOM) {
				return false;
			}
			int h = Math.max(needed, Math.min(BOTTOM - top, 360));
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 72 + i * (w + gap);
				rect(x, top, w, h, t.bg(), 20);
				rect(x, top, w, 8, t.accent());
				oval(x + pad, top + 8 + pad, 64, 64, t.accent());
				tx(x + pad, top + 8 + pad + 19, 64, two(i + 1), 22, 22, 1, 800, t.onAccent(), "center", 1.2);
				int y = top + 8 + pad + 64 + 32;
				y += tx(x + pad, y, inner, p.head(), 30, 22, 2, 800, t.ink(), "left", 1.3);
				rect(x + pad, y + 8, 40, 3, t.accent());
				tx(x + pad, y + 20, inner, p.body(), 20, 15, 5, 400, t.body(), "left", 1.55);
			}
			return true;
		}

		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int w = 548;
			int pad = 40;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), w - pad * 2, 34, 24, 2, 800, false).height(1.3) + 16
						+ fit(p.body(), w - pad * 2, 21, 15, 5, 400, false).height(1.55));
			}
			if (top + tallest + pad * 2 > BOTTOM) {
				return false;
			}
			int h = Math.max(tallest + pad * 2, Math.min(BOTTOM - top, 320));
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 72 : 660;
				boolean filled = i == 1;
				rect(x, top, w, h, filled ? t.accent() : t.surface(), 24);
				int y = top + (h - tallest) / 2;
				y += tx(x + pad, y, w - pad * 2, p.head(), 34, 24, 2, 800, filled ? t.onAccent() : t.ink(), "left", 1.3);
				tx(x + pad, y + 16, w - pad * 2, p.body(), 21, 15, 5, 400, filled ? t.onAccentSoft() : t.body(), "left",
						1.55);
			}
			ring(608, top + h / 2 - 32, 64, 64, t.bg(), t.accent(), 4);
			tx(608, top + h / 2 - 15, 64, "VS", 22, 22, 1, 800, t.accentText(), "center", 1.3);
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int col = 1136 / n;
			int inner = col - 28;
			int tallest = 0;
			for (Point p : s.points()) {
				int label = fits(p.figure(), inner, 13, "").isEmpty() ? 0 : 28;
				tallest = Math.max(tallest, label + fit(p.head(), inner, 28, 20, 2, 800, false).height(1.3) + 8
						+ fit(p.body(), inner, 19, 14, 4, 400, false).height(1.55));
			}
			int line = top + 80;
			int needed = line + 52 + tallest;
			if (needed > BOTTOM) {
				return false;
			}
			line += Math.max(0, (BOTTOM - needed) / 3);
			rect(72, line + 14, 1136, 4, t.surface());
			int half = (n + 1) / 2;
			rect(72, line + 14, col * half - col / 2, 4, t.accent());
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 72 + i * col;
				boolean done = i < half;
				tx(x, line - 76, inner, two(i + 1), 56, 56, 1, 800, done ? t.soft() : t.surface(), "left", 1.1);
				ring(x, line, 32, 32, done ? t.accent() : t.bg(), done ? null : t.soft(), done ? 0 : 4);
				int y = line + 52;
				String figure = fits(p.figure(), inner, 13, "");
				if (!figure.isEmpty()) {
					y += tx(x, y, inner, figure, 16, 13, 1, 700, t.accentText(), "left", 1.4) + 4;
				}
				y += tx(x, y, inner, p.head(), 28, 20, 2, 800, t.ink(), "left", 1.3);
				tx(x, y + 8, inner, p.body(), 19, 14, 4, 400, t.body(), "left", 1.55);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(0, 0, 460, 640, t.surface());
			tx(72, 72, 340, "SECTION " + two(no), 16, 16, 1, 700, t.accentText(), "left", 1.4);
			int y = 100 + dx(72, 100, 340, s.title(), 48, 30, 3, t.ink(), "left", 1.25);
			rect(72, y + 20, 56, 6, t.accent());
			if (!s.message().isEmpty()) {
				tx(72, y + 52, 330, s.message(), 20, 15, 5, 400, t.body(), "left", 1.55);
			}
			return rows(s.points(), 520, 688, 72, 640);
		}

		boolean rows(List<Point> points, int x, int w, int top, int bottom) {
			int[][] sizes = { { 28, 20 }, { 24, 17 }, { 20, 15 } };
			for (int[] size : sizes) {
				int total = 0;
				for (Point p : points) {
					total += Math.max(56, fit(p.head(), w - 80, size[0], size[0] - 4, 2, 800, false).height(1.3) + 8
							+ fit(p.body(), w - 80, size[1], size[1], 3, 400, false).height(1.55));
				}
				int pad = Math.min(36, (bottom - top - total) / (points.size() * 2));
				if (pad < 8) {
					continue;
				}
				int y = top + (bottom - top - total - pad * 2 * points.size()) / 2;
				for (int i = 0; i < points.size(); i++) {
					Point p = points.get(i);
					int row = y + pad;
					rect(x, row, 56, 56, t.accent(), 16);
					tx(x, row + 15, 56, two(i + 1), 22, 22, 1, 800, t.onAccent(), "center", 1.2);
					int h = tx(x + 80, row, w - 80, p.head(), size[0], size[0] - 4, 2, 800, t.ink(), "left", 1.3);
					int b = tx(x + 80, row + h + 8, w - 80, p.body(), size[1], size[1], 3, 400, t.body(), "left", 1.55);
					y = row + Math.max(56, h + 8 + b) + pad;
					if (i < points.size() - 1) {
						rect(x, y, w, 1, t.line());
					}
				}
				return true;
			}
			return false;
		}

		@Override
		void closing(Section s) {
			rect(0, 0, WIDTH, HEIGHT, t.dark());
			oval(980, -160, 460, 460, t.accent());
			tx(72, 96, 400, "SUMMARY", 18, 18, 1, 700, t.darkMark(), "left", 1.4);
			int y = 128 + dx(72, 128, 820, s.title(), 72, 40, 1, t.onDark(), "left", 1.2);
			if (!s.message().isEmpty()) {
				y += 16 + tx(72, y + 16, 820, s.message(), 30, 20, 2, 700, t.onDarkSoft(), "left", 1.4);
			}
			int n = s.points().size();
			int w = (1136 - 24 * (n - 1)) / n;
			if (!cardsFit(s.points(), w - 48, 24, 2, 17, 13, 4, 240 - 52 - 24)) {
				ruled(s.points(), 72, 1136, Math.max(300, y + 24), 650, Theme.mix(t.dark(), t.onDark(), 0.25), t.onDark(),
						t.onDarkSoft(), t.darkMark());
				return;
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 72 + i * (w + 24);
				rect(x, 400, w, 240, t.dark2(), 20);
				tx(x + 24, 424, 80, two(i + 1), 30, 30, 1, 800, t.darkMark(), "left", 1.2);
				int h = tx(x + 24, 476, w - 48, p.head(), 24, 16, 2, 800, t.onDark(), "left", 1.3);
				tx(x + 24, 484 + h, w - 48, p.body(), 17, 13, 4, 400, t.onDarkSoft(), "left", 1.5);
			}
		}

		@Override
		void footer(String source, int page) {
			String color = footColor();
			tx(72, 668, 900, footerTitle(source), 14, 12, 1, 500, color, "left", 1.4);
			tx(1008, 668, 200, two(page), 14, 14, 1, 700, color, "right", 1.4);
		}
	}

	// 모노 에디토리얼. 종이색 바탕, 검은 선, 큰 글자

	private static final class Editorial extends Design {

		String faded() {
			return Theme.mix(t.bg(), t.ink(), 0.14);
		}

		String accentOn(String background) {
			return Theme.contrast(background, t.accent()) >= 3.4 ? t.accent() : Theme.readable(background);
		}

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 56, 500, "THE NEXT CHAPTER", 18, 18, 1, 800, t.ink(), "left", 1.4);
			tx(716, 56, 500, "PRESENTATION", 18, 18, 1, 600, t.ink(), "right", 1.4);
			rect(64, 92, 1152, 3, t.ink());
			int y = 160 + dx(64, 160, 880, title, 96, 52, 3, t.ink(), "left", 1.08);
			rect(64, y + 28, 120, 12, t.accent());
			tx(64, y + 68, 760, subtitle, 22, 16, 2, 500, t.body(), "left", 1.5);
			oval(980, 330, 236, 236, t.accent());
			tx(980, 416, 236, "TEAM\nREPORT", 22, 22, 2, 800, t.onAccent(), "center", 1.3);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			dx(64, 56, 700, "Contents", 88, 88, 1, t.ink(), "left", 1.1);
			rect(64, 170, 1152, 3, t.ink());
			int perColumn = (titles.size() + 1) / 2;
			int row = Math.min(150, 450 / perColumn);
			for (int i = 0; i < titles.size(); i++) {
				int x = 64 + i / perColumn * 592;
				int y = 216 + i % perColumn * row;
				rect(x, y, 560, 1, t.ink());
				tx(x, y + 16, 120, two(i + 1), 52, 52, 1, 900, t.accentText(), "left", 1.1);
				Fit f = fit(titles.get(i), 420, 28, 18, 2, 700, false);
				put(x + 130, y + 30, 420, titles.get(i), f, 700, t.ink(), "left", 1.3, false);
			}
		}

		int header(Section s, int no) {
			tx(64, 48, 70, two(no), 20, 20, 1, 800, t.accentText(), "left", 1.3);
			tx(140, 50, 900, deck.title(), 16, 13, 1, 500, t.muted(), "left", 1.4);
			rect(64, 84, 1152, 3, t.ink());
			int h = dx(64, 112, 740, s.title(), 52, 34, 2, t.ink(), "left", 1.2);
			int m = s.message().isEmpty() ? 0 : tx(840, 122, 376, s.message(), 20, 15, 4, 500, t.body(), "left", 1.5);
			return 112 + Math.max(h, m + 10) + 40;
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 56, 1000, two(no) + "  " + s.title(), 18, 15, 1, 800, t.ink(), "left", 1.4);
			rect(64, 92, 1152, 3, t.ink());
			Fit f = fit(s.message(), 1060, 80, 44, 4, 900, true);
			rect(64, 160, 16, Math.min(340, f.height(1.12)), t.accent());
			put(120, 150, 1060, s.message(), f, 900, t.ink(), "left", 1.12, true);
			int bottom = 150 + f.height(1.12);
			int n = s.points().size();
			if (n == 0) {
				return true;
			}
			int w = 280;
			int x0 = 1216 - n * w - (n - 1) * 16;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, 14 + fit(p.head(), w, 18, 14, 2, 800, false).height(1.4) + 6
						+ fit(p.body(), w, 16, 13, 4, 500, false).height(1.5));
			}
			int top = 650 - tallest;
			if (top < bottom + 24) {
				return false;
			}
			for (int i = 0; i < n; i++) {
				int x = x0 + i * (w + 16);
				rect(x, top, w, 2, t.ink());
				int h = tx(x, top + 14, w, s.points().get(i).head(), 18, 14, 2, 800, t.accentText(), "left", 1.4);
				tx(x, top + 20 + h, w, s.points().get(i).body(), 16, 13, 4, 500, t.body(), "left", 1.5);
			}
			return true;
		}

		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int col = 1152 / n;
			int inner = col - 48;
			for (Point p : s.points()) {
				if (top + 120 + fit(p.head(), inner, 32, 22, 2, 800, false).height(1.3) + 16
						+ fit(p.body(), inner, 20, 14, 6, 500, false).height(1.6) > 650) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * col;
				if (i > 0) {
					rect(x - 24, top + 4, 1, 650 - top - 4, t.ink());
				}
				tx(x, top, inner, two(i + 1), 96, 64, 1, 900, i == 0 ? t.accentText() : faded(), "left", 1.0);
				int y = top + 120;
				y += tx(x, y, inner, p.head(), 32, 22, 2, 800, t.ink(), "left", 1.3);
				tx(x, y + 16, inner, p.body(), 20, 14, 6, 500, t.body(), "left", 1.6);
			}
			return true;
		}

		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(640, 0, 640, 720, t.ink());
			String paper = t.bg();
			String paperSoft = Theme.guard(Theme.mix(paper, t.ink(), 0.22), 4.5, paper, t.ink());
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				Fit head = fit(p.head(), 512, 64, 32, 2, 800, true);
				Fit body = fit(p.body(), 480, 22, 15, 5, 500, false);
				if (210 + head.height(1.12) + 64 + body.height(1.6) > 650) {
					return false;
				}
			}
			tx(64, 48, 520, two(no) + "  " + s.title(), 18, 14, 2, 800, t.ink(), "left", 1.4);
			if (!s.message().isEmpty()) {
				tx(704, 48, 512, s.message(), 18, 14, 2, 600, paperSoft, "left", 1.4);
			}
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 64 : 704;
				String ink = i == 0 ? t.ink() : paper;
				tx(x, 170, 300, two(i + 1), 22, 22, 1, 800, i == 0 ? t.muted() : accentOn(t.ink()), "left", 1.4);
				int h = dx(x, 210, 512, p.head(), 64, 32, 2, ink, "left", 1.12);
				rect(x, 210 + h + 24, 80, 8, i == 0 ? t.ink() : t.accent());
				tx(x, 210 + h + 64, 480, p.body(), 22, 15, 5, 500, i == 0 ? t.body() : paperSoft, "left", 1.6);
			}
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int row = (650 - top) / n;
			for (Point p : s.points()) {
				if (Math.max(fit(p.head(), 320, 32, 18, 2, 800, false).height(1.3),
						fit(p.body(), 496, 20, 14, 3, 500, false).height(1.5)) + 24 > row) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int y = top + i * row;
				rect(64, y, 1152, 1, t.ink());
				tx(64, y + 16, 120, "STEP " + (i + 1), 18, 15, 1, 800, t.accentText(), "left", 1.4);
				String figure = fits(p.figure(), 160, 14, "");
				if (!figure.isEmpty()) {
					tx(200, y + 16, 160, figure, 20, 14, 1, 700, t.muted(), "left", 1.4);
				}
				tx(380, y + 12, 320, p.head(), 32, 18, 2, 800, t.ink(), "left", 1.3);
				tx(720, y + 16, 496, p.body(), 20, 14, 3, 500, t.body(), "left", 1.5);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 48, 600, two(no), 18, 18, 1, 800, t.accentText(), "left", 1.4);
			rect(64, 84, 1152, 3, t.ink());
			int y = 120 + dx(64, 120, 480, s.title(), 64, 34, 3, t.ink(), "left", 1.12);
			if (!s.message().isEmpty()) {
				tx(64, y + 28, 440, s.message(), 22, 15, 5, 500, t.body(), "left", 1.6);
			}
			ruled(s.points(), 600, 616, 124, 650, t.ink(), t.ink(), t.body(), t.accentText());
			return true;
		}

		@Override
		void closing(Section s) {
			rect(0, 0, WIDTH, HEIGHT, t.ink());
			String paper = t.bg();
			String soft = Theme.guard(Theme.mix(paper, t.ink(), 0.25), 4.5, paper, t.ink());
			tx(64, 56, 600, "SUMMARY", 18, 18, 1, 800, accentOn(t.ink()), "left", 1.4);
			rect(64, 92, 1152, 3, Theme.mix(t.ink(), paper, 0.2));
			String big = s.message().isEmpty() ? s.title() : s.message();
			dx(64, 140, 560, big, 64, 30, 4, paper, "left", 1.12);
			ruled(s.points(), 700, 516, 140, 650, Theme.mix(t.ink(), paper, 0.3), paper, soft, accentOn(t.ink()));
		}

		@Override
		void footer(String source, int page) {
			boolean split = out.stream().anyMatch(e -> e.type().equals("shape") && e.x() == 640 && e.width() == 640);
			if (source != null) {
				tx(64, 676, split ? 540 : 900, source, 13, 12, 1, 500, t.muted(), "left", 1.4);
			}
			String color = split ? Theme.guard(Theme.mix(t.bg(), t.ink(), 0.35), 4.5, t.bg(), t.ink()) : t.muted();
			tx(1000, 676, 216, two(page), 14, 14, 1, 700, color, "right", 1.4);
		}
	}

	// 팝 피치. 크림 바탕, 둥근 도형, 굵은 제목

	private static final class Pop extends Design {

		String peach() {
			return Theme.mix(t.accent(), Theme.LIGHT, 0.75);
		}

		String mint() {
			return "#CDEFE3";
		}

		String card() {
			return Theme.mix(t.bg(), Theme.LIGHT, 0.7);
		}

		String accentOnCard() {
			return Theme.contrast(card(), t.accent()) >= 4.5 ? t.accent() : t.ink();
		}

		void pill(int x, int y, int w, String text, String fill, String ink) {
			rect(x, y, w, 40, fill, 20);
			tx(x + 8, y + 10, w - 16, text, 16, 11, 1, 800, ink, "center", 1.2);
		}

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			oval(700, -120, 700, 700, t.accent2());
			oval(1040, 420, 340, 340, t.accent());
			ring(760, 470, 120, 120, null, t.ink(), 4);
			pill(64, 72, 240, "PROJECT PROPOSAL", t.ink(), Theme.readable(t.ink()));
			int y = 190 + dx(64, 190, 620, title, 76, 40, 3, t.ink(), "left", 1.25);
			tx(64, y + 28, 600, subtitle, 24, 17, 3, 500, t.body(), "left", 1.5);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			oval(-160, 380, 520, 520, t.accent2());
			dx(64, 72, 400, "오늘의\n이야기", 64, 64, 2, t.ink(), "left", 1.25);
			int row = Math.min(116, 600 / titles.size());
			int h = row - 24;
			int top = (HEIGHT - row * titles.size() + 24) / 2;
			for (int i = 0; i < titles.size(); i++) {
				int x = 520 + i % 2 * 40;
				int y = top + i * row;
				boolean first = i == 0;
				box(x, y, 640, h, first ? t.accent() : card(), h / 2, first ? null : t.ink(), first ? 0 : 2);
				int d = h - 32;
				oval(x + 16, y + 16, d, d, first ? card() : t.accent2());
				dx(x + 16, y + 16 + (d - 32) / 2, d, String.valueOf(i + 1), 24, 18, 1, Theme.DARK, "center", 1.1);
				Fit f = fit(titles.get(i), 500, 26, 16, 1, 800, false);
				put(x + d + 40, y + (h - f.height(1.3)) / 2, 500, titles.get(i), f, 800, first ? t.onAccent() : t.ink(),
						"left", 1.3, false);
			}
		}

		int header(Section s, int no) {
			oval(64, 52, 48, 48, t.accent());
			tx(64, 64, 48, String.valueOf(no), 18, 18, 1, 800, t.onAccent(), "center", 1.2);
			int y = 52 + dx(128, 52, 1088, s.title(), 44, 28, 1, t.ink(), "left", 1.25);
			if (!s.message().isEmpty()) {
				y += 4 + tx(128, y + 4, 1088, s.message(), 20, 15, 2, 500, t.body(), "left", 1.5);
			}
			return y + 36;
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.accent2());
			oval(-80, -80, 260, 260, t.accent());
			oval(1080, 520, 320, 320, card());
			pill(390, 72, 500, two(no) + "  " + s.title(), t.ink(), Theme.readable(t.ink()));
			int y = 180 + dx(140, 180, 1000, s.message(), 68, 36, 3, t.onAccent2(), "center", 1.3);
			int n = s.points().size();
			if (n == 0) {
				return true;
			}
			int w = n == 3 ? 300 : 400;
			int gap = 20;
			int x0 = (WIDTH - n * w - (n - 1) * gap) / 2;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, 24 + fit(p.head(), w - 56, 22, 16, 2, 800, false).height(1.3) + 6
						+ fit(p.body(), w - 56, 18, 13, 3, 500, false).height(1.5) + 20);
			}
			int top = Math.max(y + 32, 460);
			if (top + tallest > 650) {
				return false;
			}
			for (int i = 0; i < n; i++) {
				int x = x0 + i * (w + gap);
				rect(x, top, w, tallest, card(), 28);
				int h = tx(x + 28, top + 24, w - 56, s.points().get(i).head(), 22, 16, 2, 800, accentOnCard(), "left",
						1.3);
				tx(x + 28, top + 30 + h, w - 56, s.points().get(i).body(), 18, 13, 3, 500, t.body(), "left", 1.5);
			}
			return true;
		}

		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int gap = 24;
			int w = (1152 - gap * (n - 1)) / n;
			String[] fills = { t.accent2(), peach(), mint(), Theme.mix(t.accent2(), Theme.LIGHT, 0.5) };
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), w - 64, 32, 20, 2, 700, true).height(1.25) + 14
						+ fit(p.body(), w - 64, 20, 14, 5, 500, false).height(1.6));
			}
			int needed = 32 + 88 + 40 + tallest + 32;
			if (top + needed + 28 > 660) {
				return false;
			}
			int h = Math.max(needed, Math.min(660 - top - 28, 400));
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + gap);
				int y = top + i % 2 * 28;
				rect(x, y, w, h, fills[i], 40);
				oval(x + 32, y + 32, 88, 88, card());
				dx(x + 32, y + 58, 88, two(i + 1), 30, 30, 1, Theme.DARK, "center", 1.1);
				int hy = y + 160;
				hy += dx(x + 32, hy, w - 64, p.head(), 32, 20, 2, Theme.DARK, "left", 1.25);
				tx(x + 32, hy + 14, w - 64, p.body(), 20, 14, 5, 500, "#3A3530", "left", 1.6);
			}
			return true;
		}

		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int w = 540;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), w - 80, 40, 24, 2, 700, true).height(1.3) + 20
						+ fit(p.body(), w - 80, 20, 14, 5, 500, false).height(1.6));
			}
			if (top + tallest + 80 > BOTTOM) {
				return false;
			}
			int h = Math.max(tallest + 80, Math.min(BOTTOM - top, 420));
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 64 : 676;
				boolean filled = i == 1;
				box(x, top, w, h, filled ? t.accent() : card(), 40, filled ? null : t.ink(), filled ? 0 : 2);
				int y = top + (h - tallest) / 2;
				y += dx(x + 40, y, w - 80, p.head(), 40, 24, 2, filled ? t.onAccent() : t.ink(), "left", 1.3);
				tx(x + 40, y + 20, w - 80, p.body(), 20, 14, 5, 500, filled ? t.onAccentSoft() : t.body(), "left", 1.6);
			}
			oval(584, top + h / 2 - 56, 112, 112, t.accent2());
			dx(584, top + h / 2 - 20, 112, "VS", 32, 32, 1, t.onAccent2(), "center", 1.1);
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int span = 1000;
			int step = span / (n - 1);
			int w = Math.min(260, step + 60);
			int line = top + (BOTTOM - top) / 2 - 3;
			int cardH = (BOTTOM - top) / 2 - 46;
			for (Point p : s.points()) {
				if (68 + fit(p.head(), w - 40, 26, 16, 1, 700, true).height(1.25) + 6
						+ fit(p.body(), w - 40, 16, 12, 3, 500, false).height(1.5) + 12 > cardH) {
					return false;
				}
			}
			rect(140, line, span, 6, t.ink(), 3);
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int cx = 140 + i * step;
				boolean on = i == 1;
				ring(cx - 26, line - 23, 52, 52, on ? t.accent() : t.accent2(), t.ink(), 3);
				int by = i % 2 == 0 ? line - 40 - cardH : line + 46;
				int x = Math.max(16, Math.min(WIDTH - 16 - w, cx - w / 2));
				box(x, by, w, cardH, on ? t.accent() : card(), 32, on ? null : t.ink(), on ? 0 : 2);
				String label = fits(p.figure(), 94, 11, "STEP " + (i + 1));
				pill(x + 20, by + 20, 110, label, on ? card() : t.accent2(), Theme.DARK);
				int y = by + 68;
				y += dx(x + 20, y, w - 40, p.head(), 26, 16, 1, on ? t.onAccent() : t.ink(), "left", 1.25);
				tx(x + 20, y + 6, w - 40, p.body(), 16, 12, 3, 500, on ? t.onAccentSoft() : t.body(), "left", 1.5);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(0, 0, 480, 650, t.accent());
			oval(260, 420, 220, 220, t.accent2());
			pill(64, 72, 64, two(no), card(), Theme.DARK);
			int y = 140 + dx(64, 140, 380, s.title(), 52, 30, 3, t.onAccent(), "left", 1.25);
			if (!s.message().isEmpty() && y + 24 < 470) {
				tx(64, y + 24, 360, s.message(), 20, 14, 3, 500, t.onAccentSoft(), "left", 1.6);
			}
			int n = s.points().size();
			int gap = 20;
			int h = (600 - gap * (n - 1)) / n;
			String[] fills = { t.accent2(), peach(), mint(), t.accent2(), peach() };
			for (Point p : s.points()) {
				if (fit(p.head(), 520, 28, 16, 1, 700, true).height(1.25) + 8
						+ fit(p.body(), 520, 19, 13, 2, 500, false).height(1.5) + 20 > h) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				Fit head = fit(p.head(), 520, 28, 16, 1, 700, true);
				Fit body = fit(p.body(), 520, 19, 13, 2, 500, false);
				int top = 60 + i * (h + gap);
				box(540, top, 676, h, card(), Math.min(32, h / 2), t.ink(), 2);
				int d = Math.min(64, h - 24);
				oval(568, top + (h - d) / 2, d, d, fills[i]);
				dx(568, top + (h - 30) / 2, d, String.valueOf(i + 1), 24, 18, 1, Theme.DARK, "center", 1.1);
				int ty = top + (h - head.height(1.25) - 8 - body.height(1.5)) / 2;
				put(660, ty, 520, p.head(), head, 700, t.ink(), "left", 1.25, true);
				put(660, ty + head.height(1.25) + 8, 520, p.body(), body, 500, t.body(), "left", 1.5, false);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			rect(0, 0, WIDTH, HEIGHT, t.ink());
			oval(900, -200, 560, 560, t.accent());
			oval(1100, 460, 300, 300, t.accent2());
			pill(64, 72, 140, "SUMMARY", t.accent2(), t.onAccent2());
			String onInk = Theme.readable(t.ink());
			String big = s.message().isEmpty() ? s.title() : s.message();
			dx(64, 150, 800, big, 60, 32, 2, onInk, "left", 1.25);
			int n = s.points().size();
			int w = (1040 - 20 * (n - 1)) / n;
			String panel = Theme.mix(t.ink(), onInk, 0.1);
			if (!cardsFit(s.points(), w - 48, 24, 2, 16, 12, 4, 240 - 76 - 24)) {
				ruled(s.points(), 64, 760, 300, 650, panel, onInk, Theme.guard(Theme.mix(onInk, t.ink(), 0.25), 4.5,
						onInk, t.ink()), Theme.guard(t.accent2(), 3, onInk, t.ink()));
				return;
			}
			String soft = Theme.guard(Theme.mix(onInk, panel, 0.25), 4.5, onInk, panel);
			String mark = Theme.guard(t.accent2(), 3, onInk, panel);
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + 20);
				boolean first = i == 0;
				rect(x, 400, w, 240, first ? t.accent2() : panel, 32);
				String ink = first ? t.onAccent2() : onInk;
				dx(x + 24, 424, 80, two(i + 1), 30, 30, 1, first ? ink : mark, "left", 1.1);
				int h = dx(x + 24, 476, w - 48, p.head(), 24, 15, 2, ink, "left", 1.25);
				tx(x + 24, 484 + h, w - 48, p.body(), 16, 12, 4, 500, first ? ink : soft, "left", 1.5);
			}
		}

		@Override
		void footer(String source, int page) {
			if (source != null) {
				tx(64, 670, 900, source, 13, 12, 1, 500, footColor(), "left", 1.4);
			}
			rect(1140, 660, 76, 32, t.ink(), 16);
			tx(1140, 667, 76, two(page), 15, 15, 1, 800, Theme.readable(t.ink()), "center", 1.2);
		}
	}

	// 데이터 브리핑. 짙은 남색, 격자, 타일

	private static final class Data extends Design {

		void grid() {
			String c = Theme.mix(t.bg(), Theme.LIGHT, 0.05);
			for (int x = 80; x < WIDTH; x += 80) {
				rect(x, 0, 1, HEIGHT, c);
			}
			for (int y = 80; y < HEIGHT; y += 80) {
				rect(0, y, WIDTH, 1, c);
			}
		}

		String panel2() {
			return Theme.mix(t.bg(), Theme.LIGHT, 0.12);
		}

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			grid();
			int[][] bars = { { 860, 180 }, { 960, 280 }, { 1060, 400 }, { 1160, 520 } };
			for (int i = 0; i < bars.length; i++) {
				rect(bars[i][0], 600 - bars[i][1], 64, bars[i][1], i == 3 ? t.accent2() : t.accent(), 4);
			}
			rect(820, 600, 420, 2, t.body());
			tx(64, 96, 600, "DATA BRIEF", 18, 18, 1, 800, t.accentText(), "left", 1.4);
			int y = 200 + dx(64, 200, 720, title, 68, 38, 3, t.ink(), "left", 1.2);
			tx(64, y + 28, 640, subtitle, 22, 16, 3, 500, t.body(), "left", 1.55);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			tx(64, 60, 600, "AGENDA", 18, 18, 1, 800, t.accentText(), "left", 1.4);
			dx(64, 96, 600, "목차", 56, 56, 1, t.ink(), "left", 1.2);
			int rows = titles.size() > 3 ? 2 : 1;
			int h = rows == 2 ? 200 : 300;
			for (int i = 0; i < titles.size(); i++) {
				int x = 64 + i % 3 * 384;
				int y = 230 + i / 3 * 224;
				boolean first = i == 0;
				rect(x, y, 360, h, first ? t.accent() : t.surface(), 16);
				tx(x + 28, y + 24, 200, two(i + 1), 44, 44, 1, 800, first ? t.onAccent() : t.accentText(), "left", 1.1);
				Fit f = fit(titles.get(i), 304, 24, 16, 2, 700, false);
				put(x + 28, y + h - 28 - f.height(1.3), 304, titles.get(i), f, 700, first ? t.onAccent() : t.ink(),
						"left", 1.3, false);
			}
		}

		int header(Section s, int no) {
			tx(84, 48, 600, "BRIEF " + two(no), 15, 15, 1, 800, t.accentText(), "left", 1.4);
			int y = 78 + dx(84, 78, 1100, s.title(), 40, 28, 2, t.ink(), "left", 1.25);
			rect(64, 56, 4, y - 56, t.accent());
			if (!s.message().isEmpty()) {
				y += 6 + tx(84, y + 6, 1100, s.message(), 19, 15, 2, 500, t.muted(), "left", 1.5);
			}
			return y + 36;
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			grid();
			String hi = Theme.guard(t.accent2(), 3, t.accentText(), t.bg());
			tx(64, 64, 760, "KEY FINDING  " + two(no) + "  " + s.title(), 18, 13, 1, 800, hi, "left", 1.4);
			int y = 120;
			if (!s.figure().isEmpty()) {
				y += tx(64, y, 760, s.figure(), 200, 96, 1, 900, hi, "left", 1.0) + 20;
				y += tx(64, y, 760, s.message(), 44, 26, 3, 800, t.ink(), "left", 1.3);
			}
			else {
				y += 40 + dx(64, y + 40, 760, s.message(), 60, 32, 4, t.ink(), "left", 1.25);
			}
			if (y > 650) {
				return false;
			}
			int n = s.points().size();
			if (n == 0) {
				return true;
			}
			// 패널 안쪽 172에서 596까지 항목 높이를 실제 키워드와 설명 높이로 더한다
			int total = 0;
			for (Point p : s.points()) {
				total += 20 + fit(p.head(), 276, 22, 16, 2, 800, false).height(1.3) + 8
						+ fit(p.body(), 276, 17, 13, 4, 500, false).height(1.6);
			}
			int room = 596 - 172;
			if (total > room) {
				return false;
			}
			int gap = n > 1 ? Math.min(40, (room - total) / (n - 1)) : 0;
			rect(880, 140, 336, 472, t.surface(), 16);
			int top = 172;
			for (int i = 0; i < n; i++) {
				rect(912, top, 32, 4, t.accent());
				int h = tx(912, top + 20, 276, s.points().get(i).head(), 22, 16, 2, 800, t.ink(), "left", 1.3);
				int b = tx(912, top + 28 + h, 276, s.points().get(i).body(), 17, 13, 4, 500, t.body(), "left", 1.6);
				top += 28 + h + b + gap;
			}
			return true;
		}

		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int gap = 24;
			int w = (1152 - gap * (n - 1)) / n;
			boolean figures = s.points().stream().allMatch(p -> !fits(p.figure(), w - 56, 28, "").isEmpty());
			String hi = Theme.guard(t.accent2(), 3.4, t.accentText(), t.surface());
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), w - 56, 26, 18, 2, 800, false).height(1.3) + 10
						+ fit(p.body(), w - 56, 18, 13, 5, 500, false).height(1.6));
			}
			int needed = 160 + tallest + 28;
			if (top + needed > BOTTOM) {
				return false;
			}
			int h = Math.max(needed, Math.min(BOTTOM - top, 340));
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + gap);
				rect(x, top, w, h, t.surface(), 16);
				rect(x, top, w, 4, i == 0 ? t.accent2() : t.accent());
				tx(x + 28, top + 28, w - 56, "POINT " + two(i + 1), 14, 14, 1, 800, t.muted(), "left", 1.4);
				tx(x + 28, top + 56, w - 56, figures ? p.figure() : two(i + 1), 52, 28, 1, 800,
						i == 0 ? hi : t.accentText(), "left", 1.1);
				rect(x + 28, top + 140, w - 56, 1, t.line());
				int y = top + 160;
				y += tx(x + 28, y, w - 56, p.head(), 26, 18, 2, 800, t.ink(), "left", 1.3);
				tx(x + 28, y + 10, w - 56, p.body(), 18, 13, 5, 500, t.body(), "left", 1.6);
			}
			return true;
		}

		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int each = (BOTTOM - top - 24) / 2;
			String hi = Theme.guard(t.accent2(), 3.4, t.accentText(), t.surface());
			for (Point p : s.points()) {
				if (Math.max(52 + fit(p.head(), 440, 30, 18, 2, 800, false).height(1.3),
						fit(p.body(), 560, 18, 13, 4, 500, false).height(1.6)) + 24 > each) {
					return false;
				}
			}
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int y = top + i * (each + 24);
				Fit body = fit(p.body(), 560, 18, 13, 4, 500, false);
				rect(64, y, 1152, each, t.surface(), 16);
				rect(64, y, 8, each, i == 1 ? t.accent2() : t.muted());
				tx(104, y + 20, 300, i == 0 ? "A" : "B", 16, 16, 1, 800, i == 1 ? hi : t.muted(), "left", 1.4);
				tx(104, y + 48, 440, p.head(), 30, 18, 2, 800, t.ink(), "left", 1.3);
				put(600, y + (each - body.height(1.6)) / 2, 560, p.body(), body, 500, t.body(), "left", 1.6, false);
			}
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int col = 1152 / n;
			String hi = Theme.guard(t.accent2(), 3.4, t.accentText(), t.surface());
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, 48 + fit(p.head(), col - 72, 28, 17, 2, 800, false).height(1.3) + 10
						+ fit(p.body(), col - 72, 17, 13, 5, 500, false).height(1.6) + 24);
			}
			if (top + 48 + 24 + tallest > 650) {
				return false;
			}
			rect(64, top, 1152, 48, t.surface(), 8);
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * col;
				boolean on = i == 1;
				rect(x + 4, top + 4, col - 8, 40, on ? t.accent2() : t.accent(), 6);
				String label = fits(p.figure(), col - 32, 11, "STEP " + (i + 1));
				tx(x + 16, top + 14, col - 32, label, 15, 11, 1, 800, on ? t.onAccent2() : t.onAccent(), "left", 1.3);
				int y = top + 48 + 24;
				rect(x + 4, y, col - 28, tallest, t.surface(), 16);
				tx(x + 28, y + 20, col - 72, "STEP " + (i + 1), 14, 14, 1, 800, on ? hi : t.accentText(), "left", 1.4);
				int h = tx(x + 28, y + 48, col - 72, p.head(), 28, 17, 2, 800, t.ink(), "left", 1.3);
				tx(x + 28, y + 58 + h, col - 72, p.body(), 17, 13, 5, 500, t.body(), "left", 1.6);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int each = (650 - top - 64) / n;
			for (Point p : s.points()) {
				if (Math.max(fit(p.head(), 290, 24, 15, 2, 800, false).height(1.3),
						fit(p.body(), 640, 18, 13, 3, 500, false).height(1.6)) + 16 > each - 8) {
					return false;
				}
			}
			rect(64, top, 1152, 48, panel2(), 8);
			tx(96, top + 14, 100, "NO", 14, 14, 1, 800, t.muted(), "left", 1.4);
			tx(200, top + 14, 280, "항목", 14, 14, 1, 800, t.muted(), "left", 1.4);
			tx(520, top + 14, 600, "내용", 14, 14, 1, 800, t.muted(), "left", 1.4);
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				Fit head = fit(p.head(), 290, 24, 15, 2, 800, false);
				Fit body = fit(p.body(), 640, 18, 13, 3, 500, false);
				int y = top + 64 + i * each;
				rect(64, y, 1152, each - 8, i % 2 == 0 ? t.surface() : t.bg(), 8);
				int mid = y + (each - 8) / 2;
				tx(96, mid - 18, 80, two(i + 1), 24, 24, 1, 800, t.accentText(), "left", 1.3);
				put(200, mid - head.height(1.3) / 2, 290, p.head(), head, 800, t.ink(), "left", 1.3, false);
				put(520, mid - body.height(1.6) / 2, 640, p.body(), body, 500, t.body(), "left", 1.6, false);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			rect(0, 0, WIDTH, HEIGHT, t.dark());
			rect(0, 0, WIDTH, 8, t.accent2());
			String mark = Theme.guard(Theme.mix(t.accent(), "#000000", 0.45), 4.5, t.bg(), t.dark());
			tx(64, 72, 600, "SUMMARY", 18, 18, 1, 800, mark, "left", 1.4);
			String big = s.message().isEmpty() ? s.title() : s.message();
			dx(64, 104, 1100, big, 52, 30, 2, t.bg(), "left", 1.25);
			int n = s.points().size();
			int w = (1152 - 24 * (n - 1)) / n;
			String hi = Theme.guard(t.accent2(), 3, t.accentText(), t.bg());
			if (!cardsFit(s.points(), w - 48, 26, 2, 18, 12, 4, 310 - 106 - 24)) {
				ruled(s.points(), 64, 1152, 260, 650, Theme.mix(t.dark(), t.bg(), 0.3), t.bg(),
						Theme.guard(Theme.mix(t.bg(), t.dark(), 0.2), 4.5, t.bg(), t.dark()), mark);
				return;
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + 24);
				rect(x, 330, w, 310, t.bg(), 16);
				tx(x + 24, 354, 200, two(i + 1), 44, 44, 1, 800, i == 0 ? hi : t.accentText(), "left", 1.1);
				int h = tx(x + 24, 436, w - 48, p.head(), 26, 16, 2, 800, t.ink(), "left", 1.3);
				tx(x + 24, 446 + h, w - 48, p.body(), 18, 12, 4, 500, t.body(), "left", 1.55);
			}
		}

		@Override
		void footer(String source, int page) {
			rect(64, 664, 1152, 1, t.line());
			tx(64, 676, 900, source != null ? source : "DATA BRIEF", 12, 11, 1, 700, footColor(), "left", 1.4);
			tx(1016, 676, 200, two(page), 12, 12, 1, 800, t.accentText(), "right", 1.4);
		}
	}

	// 그린 내추럴. 귀리색 바탕, 아치와 원

	private static final class Natural extends Design {

		String sage() {
			return Theme.mix(t.accent(), Theme.LIGHT, 0.55);
		}

		String sageLight() {
			return Theme.mix(t.accent(), Theme.LIGHT, 0.8);
		}

		String card() {
			return Theme.mix(t.bg(), Theme.LIGHT, 0.6);
		}

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			oval(780, 180, 620, 620, sage());
			oval(940, -120, 420, 420, t.accent());
			oval(760, 520, 140, 140, t.accent2());
			rect(860, 250, 280, 420, card(), 140);
			tx(912, 432, 176, "REPORT", 20, 20, 1, 700, Theme.guard(t.accent(), 3, t.ink(), card()), "center", 1.3);
			tx(72, 96, 500, "PRESENTATION", 16, 16, 1, 700, t.accentText(), "left", 1.4);
			int y = 220 + dx(72, 220, 660, title, 64, 36, 3, t.ink(), "left", 1.25);
			tx(72, y + 28, 600, subtitle, 22, 16, 3, 400, t.body(), "left", 1.6);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(72, 72, 420, 576, t.accent(), 210);
			oval(172, 420, 220, 220, sage());
			oval(312, 168, 96, 96, t.accent2());
			dx(112, 250, 340, "목차", 60, 60, 1, t.onAccent(), "center", 1.2);
			tx(112, 330, 340, "CONTENTS", 16, 16, 1, 700, t.onAccentSoft(), "center", 1.2);
			int row = Math.min(104, 560 / titles.size());
			int top = (HEIGHT - row * titles.size()) / 2;
			for (int i = 0; i < titles.size(); i++) {
				int y = top + i * row;
				boolean first = i == 0;
				String fill = first ? t.accent() : sageLight();
				oval(572, y + (row - 44) / 2 - 8, 44, 44, fill);
				tx(572, y + (row - 44) / 2 + 4, 44, String.valueOf(i + 1), 18, 18, 1, 700,
						first ? t.onAccent() : Theme.guard(t.accent(), 3, Theme.DARK, fill), "center", 1.2);
				Fit f = fit(titles.get(i), 560, 26, 18, 2, 600, false);
				put(640, y + (row - f.height(1.3)) / 2 - 8, 560, titles.get(i), f, 600, t.ink(), "left", 1.3, false);
				if (i < titles.size() - 1) {
					rect(640, y + row - 4, 568, 1, t.line());
				}
			}
		}

		int header(Section s, int no) {
			oval(72, 64, 14, 14, t.accent2());
			tx(96, 58, 600, two(no), 16, 16, 1, 700, t.accentText(), "left", 1.4);
			int y = 92 + dx(72, 92, 1100, s.title(), 46, 30, 2, t.ink(), "left", 1.25);
			if (!s.message().isEmpty()) {
				y += 4 + tx(72, y + 4, 1100, s.message(), 20, 15, 2, 400, t.body(), "left", 1.5);
			}
			return y + 40;
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			oval(-260, 200, 400, 400, t.deep());
			ring(1000, -120, 380, 380, null, sage(), 3);
			oval(1180, 540, 80, 80, t.accent2());
			tx(160, 72, 960, two(no) + "  " + s.title(), 16, 13, 1, 700, t.onAccentSoft(), "center", 1.4);
			int y = 180 + dx(240, 180, 800, s.message(), 56, 32, 3, t.onAccent(), "center", 1.3);
			rect(608, y + 28, 64, 2, t.onAccentSoft());
			int n = s.points().size();
			if (n == 0) {
				return true;
			}
			int w = n == 3 ? 280 : 360;
			int gap = 36;
			int x0 = (WIDTH - n * w - (n - 1) * gap) / 2;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), w, 22, 16, 2, 700, false).height(1.4) + 6
						+ fit(p.body(), w, 18, 13, 3, 400, false).height(1.6));
			}
			int top = y + 64;
			if (top + tallest > 650) {
				return false;
			}
			for (int i = 0; i < n; i++) {
				int x = x0 + i * (w + gap);
				int h = tx(x, top, w, s.points().get(i).head(), 22, 16, 2, 700, t.onAccent(), "center", 1.4);
				tx(x, top + h + 6, w, s.points().get(i).body(), 18, 13, 3, 400, t.onAccentSoft(), "center", 1.6);
			}
			return true;
		}

		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int gap = 40;
			int w = Math.min(320, (1136 - gap * (n - 1)) / n);
			int x0 = (WIDTH - n * w - (n - 1) * gap) / 2;
			int inner = w - 72;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), inner, 28, 18, 2, 700, false).height(1.3) + 12
						+ fit(p.body(), inner, 18, 13, 5, 400, false).height(1.6));
			}
			int needed = 140 + tallest + w / 3;
			if (top + needed > 656) {
				return false;
			}
			int h = Math.max(needed, Math.min(656 - top, 420));
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = x0 + i * (w + gap);
				boolean on = i % 2 == 1;
				rect(x, top, w, h, on ? sageLight() : card(), w / 2);
				String dot = on ? t.accent() : sage();
				oval(x + w / 2 - 40, top + 40, 80, 80, dot);
				tx(x + w / 2 - 40, top + 66, 80, two(i + 1), 22, 22, 1, 700, Theme.readable(dot), "center", 1.2);
				int y = top + 140;
				y += tx(x + 36, y, inner, p.head(), 28, 18, 2, 700, t.ink(), "center", 1.3);
				tx(x + 36, y + 12, inner, p.body(), 18, 13, 5, 400, t.body(), "center", 1.6);
			}
			return true;
		}

		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int d = Math.min(420, BOTTOM + 10 - top);
			int inner = (int) (d * 0.7);
			for (Point p : s.points()) {
				if (fit(p.head(), inner, 34, 20, 2, 700, false).height(1.3) + 16
						+ fit(p.body(), inner, 18, 13, 3, 400, false).height(1.6) > d * 0.66) {
					return false;
				}
			}
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				Fit head = fit(p.head(), inner, 34, 20, 2, 700, false);
				Fit body = fit(p.body(), inner, 18, 13, 3, 400, false);
				int content = head.height(1.3) + 16 + body.height(1.6);
				int x = i == 0 ? 640 - 76 - d : 716;
				boolean on = i == 1;
				oval(x, top, d, d, on ? t.accent() : sageLight());
				int y = top + (d - content) / 2;
				put(x + (d - inner) / 2, y, inner, p.head(), head, 700, on ? t.onAccent() : t.ink(), "center", 1.3,
						false);
				put(x + (d - inner) / 2, y + head.height(1.3) + 16, inner, p.body(), body, 400,
						on ? t.onAccentSoft() : t.body(), "center", 1.6, false);
			}
			oval(600, top + d / 2 - 40, 80, 80, t.accent2());
			tx(600, top + d / 2 - 14, 80, "VS", 20, 20, 1, 800, Theme.readable(t.accent2()), "center", 1.3);
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int step = 1016 / (n - 1);
			int line = top + 70;
			int inner = Math.min(280, step - 20);
			for (Point p : s.points()) {
				if (line + 82 + fit(p.head(), inner, 26, 16, 1, 700, false).height(1.3) + 6
						+ fit(p.body(), inner, 17, 12, 3, 400, false).height(1.6) > 650) {
					return false;
				}
			}
			rect(132, line - 1, 1016, 2, sage());
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int cx = 132 + i * step;
				int size = 56 + i * 64 / Math.max(1, n - 1);
				boolean last = i == n - 1;
				String fill = last ? t.accent() : i == 0 ? sageLight() : sage();
				oval(cx - size / 2, line - size / 2, size, size, fill);
				String label = fits(p.figure(), size - 8, 10, String.valueOf(i + 1));
				tx(cx - size / 2 + 4, line - 11, size - 8, label, 16, 10, 1, 700, Theme.readable(fill), "center", 1.3);
				int x = Math.max(16, Math.min(WIDTH - 16 - inner, cx - inner / 2));
				int y = line + 82;
				y += tx(x, y, inner, p.head(), 26, 16, 1, 700, t.ink(), "center", 1.3);
				tx(x, y + 6, inner, p.body(), 17, 12, 3, 400, t.body(), "center", 1.6);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int gap = 14;
			int h = Math.min(120, (650 - top - gap * (n - 1)) / n);
			for (Point p : s.points()) {
				if (fit(p.head(), 880, 26, 16, 1, 700, false).height(1.3) + 4
						+ fit(p.body(), 880, 18, 13, 2, 400, false).height(1.5) + 12 > h) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				Fit head = fit(p.head(), 880, 26, 16, 1, 700, false);
				Fit body = fit(p.body(), 880, 18, 13, 2, 400, false);
				int content = head.height(1.3) + 4 + body.height(1.5);
				int y = top + i * (h + gap);
				boolean first = i == 0;
				rect(72, y, 1136, h, card(), h / 2);
				int ph = Math.min(64, h - 24);
				String fill = first ? t.accent() : sageLight();
				rect(96, y + (h - ph) / 2, 120, ph, fill, ph / 2);
				tx(96, y + (h - 26) / 2, 120, two(i + 1), 20, 18, 1, 700,
						first ? t.onAccent() : Theme.guard(t.accent(), 3, Theme.DARK, fill), "center", 1.2);
				int cy = y + (h - content) / 2;
				put(248, cy, 880, p.head(), head, 700, t.ink(), "left", 1.3, false);
				put(248, cy + head.height(1.3) + 4, 880, p.body(), body, 400, t.body(), "left", 1.5, false);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			int n = s.points().size();
			int gap = 20;
			int w = Math.min(288, (1136 - gap * (n - 1)) / n);
			boolean arches = cardsFit(s.points(), w - 60, 22, 2, 15, 12, 4, 310 - 100 - 50);
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			oval(860, -180, 560, 560, t.deep());
			if (arches) {
				oval(-100, 520, 300, 300, sage());
			}
			tx(72, 96, 500, "SUMMARY", 16, 16, 1, 700, t.onAccentSoft(), "left", 1.4);
			String big = s.message().isEmpty() ? s.title() : s.message();
			dx(72, 128, 760, big, 52, 30, 2, t.onAccent(), "left", 1.3);
			int x0 = WIDTH - 72 - n * w - (n - 1) * gap;
			String mark = Theme.guard(t.accent2(), 3, t.accentText(), t.bg());
			if (!arches) {
				ruled(s.points(), 72, 1136, 300, 650, t.onAccentSoft(), t.onAccent(), t.onAccentSoft(), t.onAccent());
				return;
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = x0 + i * (w + gap);
				rect(x, 330, w, 310, t.bg(), w / 2);
				tx(x + 24, 384, w - 48, two(i + 1), 26, 26, 1, 700, mark, "center", 1.2);
				int h = tx(x + 24, 430, w - 48, p.head(), 22, 15, 2, 700, t.ink(), "center", 1.3);
				tx(x + 30, 438 + h, w - 60, p.body(), 15, 12, 4, 400, t.body(), "center", 1.5);
			}
		}

		@Override
		void footer(String source, int page) {
			String color = footColor();
			tx(72, 672, 900, footerTitle(source), 14, 12, 1, 500, color, "left", 1.4);
			tx(1108, 672, 100, two(page), 14, 14, 1, 700, color, "right", 1.4);
		}
	}

	// 스위스 그리드. 흰 바탕, 빨강과 검정, 격자와 큰 글자

	private static final class Swiss extends Design {

		static int col(int c) {
			return 48 + c * 100;
		}

		String gray() {
			return Theme.mix(t.bg(), t.ink(), 0.07);
		}

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(col(0), 48, 120, 120, t.accent());
			List<Section> sections = deck.topics();
			for (int i = 0; i < Math.min(2, sections.size()); i++) {
				tx(col(4 + i * 4), 48, 380, two(i + 1) + "\n" + sections.get(i).title(), 16, 12, 3, 700, t.ink(), "left",
						1.5);
			}
			rect(48, 300, 1184, 2, t.ink());
			int size = 104;
			Fit f = fit(title, 1184, size, 48, 3, 900, true);
			while (f.size() > 48 && f.height(1.05) > 250) {
				f = new Fit(f.size() - 4, lines(title, 1184, f.size() - 4, true, display()));
			}
			put(col(0), 330, 1184, title, f, 900, t.ink(), "left", 1.05, true);
			tx(col(8), 600, 384, subtitle, 18, 13, 3, 500, t.body(), "left", 1.5);
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			dx(col(0), 48, 800, "Index", 120, 120, 1, t.ink(), "left", 1.0);
			rect(48, 200, 1184, 2, t.ink());
			int w = 1184 / titles.size();
			for (int i = 0; i < titles.size(); i++) {
				int x = 48 + i * w;
				if (i > 0) {
					rect(x - 8, 240, 1, 400, t.ink());
				}
				tx(x, 240, w - 20, two(i + 1), 72, 48, 1, 900, i == 0 ? t.accentText() : t.ink(), "left", 1.0);
				tx(x, 348, w - 30, titles.get(i), 24, 15, 5, 800, t.ink(), "left", 1.3);
			}
		}

		int header(Section s, int no) {
			rect(col(0), 48, 28, 28, t.accent());
			tx(col(1), 50, 280, two(no), 16, 16, 1, 800, t.ink(), "left", 1.4);
			tx(col(4), 50, 780, deck.title(), 16, 12, 1, 500, t.muted(), "left", 1.4);
			rect(48, 96, 1184, 2, t.ink());
			int h = dx(col(0), 124, 780, s.title(), 56, 32, 2, t.ink(), "left", 1.15);
			int m = s.message().isEmpty() ? 0 : tx(col(8), 136, 384, s.message(), 18, 13, 4, 500, t.body(), "left", 1.5);
			return 124 + Math.max(h, m + 12) + 40;
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			tx(col(0), 48, 1000, two(no) + "  " + s.title(), 16, 13, 1, 800, t.onAccent(), "left", 1.4);
			rect(48, 96, 1184, 2, t.onAccent());
			int bottom = 130 + dx(col(0), 130, 1100, s.message(), 84, 40, 3, t.onAccent(), "left", 1.08);
			int n = s.points().size();
			if (n == 0) {
				return true;
			}
			int w = 284;
			int x0 = col(12 - n * 3);
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, 16 + fit(p.head(), w, 18, 13, 2, 800, false).height(1.4) + 6
						+ fit(p.body(), w, 15, 12, 4, 500, false).height(1.5));
			}
			int top = 650 - tallest;
			if (top < bottom + 24) {
				return false;
			}
			for (int i = 0; i < n; i++) {
				int x = x0 + i * 300;
				rect(x, top, w, 2, t.onAccent());
				int h = tx(x, top + 16, w, s.points().get(i).head(), 18, 13, 2, 800, t.onAccent(), "left", 1.4);
				tx(x, top + 22 + h, w, s.points().get(i).body(), 15, 12, 4, 500, t.onAccent(), "left", 1.5);
			}
			return true;
		}

		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int w = (1184 - 16 * (n - 1)) / n;
			int tallest = 0;
			for (Point p : s.points()) {
				tallest = Math.max(tallest, fit(p.head(), w - 48, 30, 18, 2, 900, false).height(1.3) + 14
						+ fit(p.body(), w - 48, 18, 13, 5, 500, false).height(1.6));
			}
			if (top + 72 + 56 + tallest > 650) {
				return false;
			}
			int h = Math.max(56 + tallest, Math.min(650 - top - 72, 280));
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 48 + i * (w + 16);
				String band = i == 0 ? t.accent() : t.ink();
				rect(x, top, w, 72, band);
				tx(x + 24, top + 18, 100, two(i + 1), 28, 28, 1, 900, Theme.readable(band), "left", 1.3);
				rect(x, top + 72, w, h, gray());
				int y = top + 100;
				y += tx(x + 24, y, w - 48, p.head(), 30, 18, 2, 900, t.ink(), "left", 1.3);
				tx(x + 24, y + 14, w - 48, p.body(), 18, 13, 5, 500, t.body(), "left", 1.6);
			}
			return true;
		}

		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int h = 650 - top;
			for (Point p : s.points()) {
				if (88 + fit(p.head(), 520, 48, 26, 2, 900, true).height(1.1) + 24
						+ fit(p.body(), 500, 18, 13, 5, 500, false).height(1.6) > h) {
					return false;
				}
			}
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = col(i * 6);
				String fill = i == 0 ? gray() : t.ink();
				String ink = Theme.readable(fill);
				rect(x, top, 584, h, fill);
				String label = i == 0 ? t.body() : Theme.guard(t.accent(), 4.5, ink, fill);
				tx(x + 32, top + 32, 400, "0" + (i + 1), 16, 16, 1, 800, label, "left", 1.4);
				int hh = dx(x + 32, top + 64, 520, p.head(), 48, 26, 2, ink, "left", 1.1);
				tx(x + 32, top + 88 + hh, 500, p.body(), 18, 13, 5, 500,
						i == 0 ? t.body() : Theme.guard(Theme.mix(ink, fill, 0.25), 4.5, ink, fill), "left", 1.6);
			}
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int w = (1184 - 16 * (n - 1)) / n;
			int text = 0;
			for (Point p : s.points()) {
				text = Math.max(text, fit(p.head(), w, 26, 16, 1, 900, false).height(1.3) + 6
						+ fit(p.body(), w - 14, 16, 12, 3, 500, false).height(1.5));
			}
			int maxBar = 640 - (top + text + 16);
			if (maxBar < 110) {
				return false;
			}
			int stepUp = n > 1 ? Math.min(60, (maxBar - 110) / (n - 1)) : 0;
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 48 + i * (w + 16);
				int bar = 110 + i * stepUp;
				String fill = i == n - 1 ? t.accent() : t.ink();
				rect(x, 640 - bar, w, bar, fill);
				String label = fits(p.figure(), w - 40, 12, "STEP " + (i + 1));
				tx(x + 20, 640 - bar + 16, w - 40, label, 16, 12, 1, 800, Theme.readable(fill), "left", 1.4);
				int y = 640 - bar - 16 - text;
				y += tx(x, y, w, p.head(), 26, 16, 1, 900, t.ink(), "left", 1.3);
				tx(x, y + 6, w - 14, p.body(), 16, 12, 3, 500, t.body(), "left", 1.5);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int top = header(s, no);
			int n = s.points().size();
			int row = (650 - top) / n;
			for (Point p : s.points()) {
				if (Math.max(fit(p.head(), 480, 30, 18, 2, 900, false).height(1.2),
						fit(p.body(), 584, 18, 13, 3, 500, false).height(1.5)) + 30 > row) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int y = top + i * row;
				rect(48, y, 1184, i == 0 ? 6 : 2, i == 0 ? t.accent() : t.ink());
				tx(col(0), y + 22, 90, two(i + 1), 32, 32, 1, 900, i == 0 ? t.accentText() : t.ink(), "left", 1.1);
				tx(col(1), y + 22, 480, p.head(), 30, 18, 2, 900, t.ink(), "left", 1.2);
				tx(col(6), y + 26, 584, p.body(), 18, 13, 3, 500, t.body(), "left", 1.5);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			rect(0, 0, WIDTH, HEIGHT, t.ink());
			String paper = Theme.readable(t.ink());
			String dim = Theme.guard(Theme.mix(paper, t.ink(), 0.28), 4.5, paper, t.ink());
			String mark = Theme.guard(t.accent(), 3, paper, t.ink());
			rect(col(0), 48, 28, 28, t.accent());
			tx(col(1), 50, 400, "SUMMARY", 16, 16, 1, 800, paper, "left", 1.4);
			rect(48, 96, 1184, 2, paper);
			int y = 124 + dx(col(0), 124, 560, s.title(), 120, 48, 2, paper, "left", 1.0);
			if (!s.message().isEmpty()) {
				tx(col(0), y + 24, 520, s.message(), 30, 18, 3, 800, dim, "left", 1.35);
			}
			int n = s.points().size();
			int row = 510 / n;
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int top = 136 + i * row;
				rect(col(6), top, 584, 2, i == 0 ? t.accent() : Theme.mix(t.ink(), paper, 0.25));
				tx(col(6), top + 16, 90, two(i + 1), 28, 22, 1, 900, mark, "left", 1.1);
				int h = tx(col(7), top + 16, 484, p.head(), 26, 16, 1, 900, paper, "left", 1.2);
				tx(col(7), top + 22 + h, 484, p.body(), 16, 12, Math.max(1, (row - 50 - h) / 22), 500, dim, "left", 1.4);
			}
		}

		@Override
		void footer(String source, int page) {
			String fill = out.get(0).style().get("fill").asString();
			boolean plain = fill.equals(t.bg());
			String color = plain ? t.ink() : Theme.readable(fill);
			if (source != null) {
				tx(col(0), 672, 900, source, 13, 12, 1, 500, plain ? t.muted() : color, "left", 1.4);
			}
			tx(col(10), 672, 184, two(page), 14, 14, 1, 800, color, "right", 1.4);
		}
	}

	// 벤토 파스텔. 크기가 다른 파스텔 타일

	private static final class Bento extends Design {

		static final String WHITE = "#FFFFFF";
		static final String INK = "#1E1830";
		static final String SOFT = "#4B4560";
		static final String[] PASTELS = { "#C4EEDF", "#FFF0A6", "#FFDCCB" };

		String lavender() {
			return Theme.mix(t.accent(), Theme.LIGHT, 0.78);
		}

		String accentOnWhite() {
			return Theme.guard(t.accent(), 3.4, INK, WHITE);
		}

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(48, 48, 760, 624, t.accent(), 32);
			tx(96, 96, 500, "IDEA NOTE", 16, 16, 1, 800, t.onAccentSoft(), "left", 1.4);
			int y = 220 + dx(96, 220, 680, title, 64, 36, 3, t.onAccent(), "left", 1.2);
			tx(96, y + 28, 620, subtitle, 20, 15, 3, 500, t.onAccentSoft(), "left", 1.6);
			int[][] tiles = { { 832, 48, 400, 300 }, { 832, 372, 188, 300 }, { 1044, 372, 188, 300 } };
			List<Section> sections = deck.topics();
			for (int i = 0; i < 3; i++) {
				int[] r = tiles[i];
				rect(r[0], r[1], r[2], r[3], PASTELS[i], 32);
				tx(r[0] + 24, r[1] + 28, 140, "PART " + two(i + 1), 14, 14, 1, 800, INK, "left", 1.4);
				if (i < sections.size()) {
					int w = i == 0 ? 220 : r[2] - 48;
					tx(r[0] + 24, r[1] + 56, w, sections.get(i).title(), 26, 15, 4, 800, INK, "left", 1.25);
				}
			}
			oval(1100, 210, 110, 110, WHITE);
		}

		@Override
		int agendaLimit() {
			return 5;
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(48, 48, 400, 624, t.ink(), 32);
			String onInk = Theme.readable(t.ink());
			tx(88, 88, 300, "CONTENTS", 16, 16, 1, 800, Theme.guard(lavender(), 4.5, onInk, t.ink()), "left", 1.4);
			dx(88, 120, 320, "목차", 64, 64, 1, onInk, "left", 1.2);
			oval(228, 500, 220, 220, t.accent());
			int[][] five = { { 472, 48, 760, 200 }, { 472, 272, 368, 192 }, { 864, 272, 368, 192 },
					{ 472, 488, 368, 184 }, { 864, 488, 368, 184 } };
			int[][] four = { { 472, 48, 760, 296 }, { 472, 368, 240, 304 }, { 732, 368, 240, 304 },
					{ 992, 368, 240, 304 } };
			int[][] three = { { 472, 48, 760, 296 }, { 472, 368, 368, 304 }, { 864, 368, 368, 304 } };
			int[][] pos = titles.size() == 5 ? five : titles.size() == 4 ? four : three;
			String[] fills = { lavender(), PASTELS[0], PASTELS[1], PASTELS[2], lavender() };
			for (int i = 0; i < titles.size(); i++) {
				int[] r = pos[i];
				rect(r[0], r[1], r[2], r[3], fills[i], 28);
				tx(r[0] + 28, r[1] + 24, 100, two(i + 1), i == 0 ? 48 : 36, 36, 1, 800, INK, "left", 1.1);
				Fit f = fit(titles.get(i), r[2] - 56, i == 0 ? 32 : 24, 15, 2, 800, false);
				put(r[0] + 28, r[1] + r[3] - 24 - f.height(1.3), r[2] - 56, titles.get(i), f, 800, INK, "left", 1.3,
						false);
			}
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			rect(48, 48, 1184, 420, t.accent(), 32);
			oval(900, 150, 280, 280, t.deep());
			tx(96, 88, 760, two(no) + "  " + s.title(), 16, 13, 1, 800, t.onAccentSoft(), "left", 1.4);
			Fit f = fit(s.message(), 760, 56, 30, 4, 800, true);
			if (150 + f.height(1.25) > 450) {
				return false;
			}
			put(96, 150, 760, s.message(), f, 800, t.onAccent(), "left", 1.25, true);
			int n = s.points().size();
			if (n == 0) {
				return true;
			}
			int w = (1184 - 24 * (n - 1)) / n;
			for (Point p : s.points()) {
				if (24 + fit(p.head(), w - 64, 24, 16, 2, 800, false).height(1.3) + 6
						+ fit(p.body(), w - 64, 18, 13, 3, 500, false).height(1.6) + 16 > 180) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				int x = 48 + i * (w + 24);
				rect(x, 492, w, 180, PASTELS[i], 28);
				int h = tx(x + 32, 516, w - 64, s.points().get(i).head(), 24, 16, 2, 800, INK, "left", 1.3);
				tx(x + 32, 522 + h, w - 64, s.points().get(i).body(), 18, 13, 3, 500, SOFT, "left", 1.6);
			}
			return true;
		}

		void headTile(int x, int y, int w, int h, Section s, int no) {
			rect(x, y, w, h, WHITE, 28);
			tx(x + 32, y + 28, 200, two(no), 15, 15, 1, 800, accentOnWhite(), "left", 1.4);
			int th = dx(x + 32, y + 54, w - 64, s.title(), 40, 24, 2, INK, "left", 1.2);
			if (!s.message().isEmpty()) {
				Fit m = fit(s.message(), w - 64, 18, 13, 3, 500, false);
				if (y + 64 + th + m.height(1.5) <= y + h - 16) {
					put(x + 32, y + 64 + th, w - 64, s.message(), m, 500, SOFT, "left", 1.5, false);
				}
			}
		}

		@Override
		boolean cards(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			List<Point> ps = s.points();
			int n = ps.size();
			int rest = n - 1;
			int w = (1184 - 24 * (rest - 1)) / rest;
			for (int i = 0; i < n; i++) {
				int width = i == 0 ? 536 : w;
				int height = i == 0 ? 280 : 320;
				if (32 + 50 + 24 + fit(ps.get(i).head(), width - 64, 30, 18, 2, 800, false).height(1.2) + 10
						+ fit(ps.get(i).body(), width - 64, 18, 13, 3, 500, false).height(1.6) + 28 > height) {
					return false;
				}
			}
			headTile(48, 48, 624, 280, s, no);
			tile(696, 48, 536, 280, ps.get(0), 0, t.accent(), t.onAccent(), t.onAccentSoft());
			for (int i = 1; i < n; i++) {
				tile(48 + (i - 1) * (w + 24), 352, w, 320, ps.get(i), i, PASTELS[(i - 1) % 3], INK, SOFT);
			}
			return true;
		}

		void tile(int x, int y, int w, int h, Point p, int index, String fill, String ink, String soft) {
			rect(x, y, w, h, fill, 28);
			tx(x + 32, y + 32, 100, two(index + 1), 36, 36, 1, 800, ink, "left", 1.1);
			Fit head = fit(p.head(), w - 64, 30, 18, 2, 800, false);
			Fit body = fit(p.body(), w - 64, 18, 13, 3, 500, false);
			int top = y + h - 28 - body.height(1.6) - 10 - head.height(1.2);
			put(x + 32, top, w - 64, p.head(), head, 800, ink, "left", 1.2, false);
			put(x + 32, top + head.height(1.2) + 10, w - 64, p.body(), body, 500, soft, "left", 1.6, false);
		}

		void banner(Section s, int no) {
			rect(48, 48, 1184, 150, WHITE, 28);
			tx(80, 76, 200, two(no), 15, 15, 1, 800, accentOnWhite(), "left", 1.4);
			dx(80, 102, 640, s.title(), 40, 24, 1, INK, "left", 1.2);
			if (!s.message().isEmpty()) {
				tx(740, 96, 460, s.message(), 18, 13, 3, 500, SOFT, "right", 1.5);
			}
		}

		@Override
		boolean compare(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			for (Point p : s.points()) {
				if (76 + fit(p.head(), 460, 40, 22, 2, 800, true).height(1.25) + 24
						+ fit(p.body(), 460, 19, 13, 5, 500, false).height(1.6) + 32 > 450) {
					return false;
				}
			}
			banner(s, no);
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 48 : 692;
				boolean on = i == 1;
				rect(x, 222, 540, 450, on ? t.accent() : WHITE, 28);
				tx(x + 40, 262, 300, "0" + (i + 1), 16, 16, 1, 800, on ? t.onAccentSoft() : accentOnWhite(), "left", 1.4);
				int h = dx(x + 40, 296, 460, p.head(), 40, 22, 2, on ? t.onAccent() : INK, "left", 1.25);
				tx(x + 40, 320 + h, 460, p.body(), 19, 13, 5, 500, on ? t.onAccentSoft() : SOFT, "left", 1.6);
			}
			rect(612, 382, 56, 130, PASTELS[1], 28);
			tx(612, 434, 56, "VS", 18, 18, 1, 800, INK, "center", 1.3);
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int n = s.points().size();
			int w = (1184 - 24 * (n - 1)) / n;
			for (Point p : s.points()) {
				if (108 + fit(p.head(), w - 48, 28, 16, 2, 800, false).height(1.25) + 10
						+ fit(p.body(), w - 48, 17, 12, 5, 500, false).height(1.6) + 24 > 450) {
					return false;
				}
			}
			banner(s, no);
			String[] fills = { lavender(), t.accent(), PASTELS[0], PASTELS[2], PASTELS[1] };
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 48 + i * (w + 24);
				boolean on = i == 1;
				String ink = on ? t.onAccent() : INK;
				rect(x, 222, w, 450, fills[i], 28);
				int pw = Math.min(130, w - 48);
				rect(x + 24, 246, pw, 40, on ? WHITE : INK, 20);
				String label = fits(p.figure(), pw - 16, 11, "STEP " + (i + 1));
				tx(x + 32, 256, pw - 16, label, 15, 11, 1, 800, on ? accentOnWhite() : WHITE, "center", 1.3);
				int y = 330;
				y += tx(x + 24, y, w - 48, p.head(), 28, 16, 2, 800, ink, "left", 1.25);
				tx(x + 24, y + 10, w - 48, p.body(), 17, 12, 5, 500, on ? t.onAccentSoft() : SOFT, "left", 1.6);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			int n = s.points().size();
			int h = (624 - 24 * (n - 1)) / n;
			for (Point p : s.points()) {
				if (fit(p.head(), 580, 28, 16, 1, 800, false).height(1.25) + 6
						+ fit(p.body(), 580, 18, 13, 2, 500, false).height(1.5) + 24 > h) {
					return false;
				}
			}
			rect(48, 48, 420, 624, lavender(), 28);
			tx(88, 88, 300, two(no), 16, 16, 1, 800, INK, "left", 1.4);
			int y = 118 + dx(88, 118, 340, s.title(), 48, 26, 3, INK, "left", 1.25);
			if (!s.message().isEmpty()) {
				Fit m = fit(s.message(), 340, 18, 13, 4, 500, false);
				if (y + 24 + m.height(1.6) < 440) {
					put(88, y + 24, 340, s.message(), m, 500, SOFT, "left", 1.6, false);
				}
			}
			oval(248, 452, 200, 200, t.accent());
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				Fit head = fit(p.head(), 580, 28, 16, 1, 800, false);
				Fit body = fit(p.body(), 580, 18, 13, 2, 500, false);
				int content = head.height(1.25) + 6 + body.height(1.5);
				int top = 48 + i * (h + 24);
				rect(492, top, 740, h, WHITE, Math.min(28, h / 2));
				rect(516, top + 12, 72, h - 24, PASTELS[i % 3], 20);
				tx(516, top + h / 2 - 16, 72, two(i + 1), 24, 18, 1, 800, INK, "center", 1.3);
				int cy = top + (h - content) / 2;
				put(616, cy, 580, p.head(), head, 800, INK, "left", 1.25, false);
				put(616, cy + head.height(1.25) + 6, 580, p.body(), body, 500, SOFT, "left", 1.5, false);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			rect(0, 0, WIDTH, HEIGHT, t.ink());
			rect(48, 48, 1184, 260, t.accent(), 28);
			tx(96, 88, 300, "SUMMARY", 16, 16, 1, 800, t.onAccentSoft(), "left", 1.4);
			String big = s.message().isEmpty() ? s.title() : s.message();
			dx(96, 118, 1000, big, 48, 28, 2, t.onAccent(), "left", 1.25);
			int n = s.points().size();
			int w = (1184 - 20 * (n - 1)) / n;
			if (!cardsFit(s.points(), w - 56, 26, 2, 16, 12, 4, 340 - 138 - 24)) {
				String onInk = Theme.readable(t.ink());
				ruled(s.points(), 48, 1184, 330, 670, Theme.mix(t.ink(), onInk, 0.25), onInk,
						Theme.guard(Theme.mix(onInk, t.ink(), 0.25), 4.5, onInk, t.ink()),
						Theme.guard(lavender(), 4.5, onInk, t.ink()));
				return;
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 48 + i * (w + 20);
				rect(x, 332, w, 340, PASTELS[i % 3], 28);
				tx(x + 28, 360, 100, two(i + 1), 36, 36, 1, 800, INK, "left", 1.1);
				int h = tx(x + 28, 470, w - 56, p.head(), 26, 15, 2, 800, INK, "left", 1.25);
				tx(x + 28, 480 + h, w - 56, p.body(), 16, 12, 4, 500, SOFT, "left", 1.55);
			}
		}

		@Override
		void footer(String source, int page) {
			// 타일이 캔버스를 채우므로 출처와 쪽 번호는 아래 여백에 작게 적는다
			String fill = out.get(0).style().get("fill").asString();
			String color = Theme.guard(t.muted(), 4.5, Theme.readable(fill), fill);
			if (source != null) {
				tx(48, 682, 900, source, 12, 11, 1, 500, color, "left", 1.4);
			}
			tx(1032, 682, 200, two(page), 12, 12, 1, 800, color, "right", 1.4);
		}
	}

	// 코퍼레이트. 남색과 금색, 제목 띠

	private static final class Corporate extends Design {

		String gray() {
			return Theme.mix(t.bg(), t.ink(), 0.05);
		}

		// 금색 글자. 작은 글자도 읽히게 4.5 대비를 맞춘다
		String goldOn(String background) {
			return Theme.guard(t.accent2(), 4.5, Theme.readable(background), background);
		}

		@Override
		void cover(String title, String subtitle) {
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			rect(0, 560, WIDTH, 160, t.bg());
			rect(0, 556, WIDTH, 4, t.accent2());
			rect(64, 120, 80, 6, t.accent2());
			int y = 150 + dx(64, 150, 900, title, 64, 36, 3, t.onAccent(), "left", 1.25);
			Fit sub = fit(subtitle, 900, 22, 15, 2, 400, false);
			if (y + 24 + sub.height(1.6) < 540) {
				put(64, y + 24, 900, subtitle, sub, 400, t.onAccentSoft(), "left", 1.6, false);
			}
			tx(64, 596, 400, "구성", 14, 14, 1, 700, t.muted(), "left", 1.4);
			tx(64, 620, 600, "본문 " + deck.topics().size() + "개 주제와 정리", 18, 18, 1, 700, t.ink(), "left", 1.4);
			rect(1040, 600, 176, 64, t.accent());
			tx(1040, 620, 176, "REPORT", 18, 18, 1, 800, goldOn(t.accent()), "center", 1.3);
		}

		void bar(String title, String label) {
			rect(0, 0, WIDTH, 112, t.accent());
			rect(64, 36, 40, 40, t.accent2());
			tx(64, 44, 40, label, 18, 12, 1, 800, t.onAccent2(), "center", 1.2);
			Fit f = fit(title, 1000, 36, 22, 1, 700, true);
			put(128, (112 - f.height(1.3)) / 2, 1000, title, f, 700, t.onAccent(), "left", 1.3, true);
			rect(0, 112, WIDTH, 4, t.accent2());
		}

		@Override
		void agenda(List<String> titles) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			bar("목차", "00");
			int row = Math.min(96, 500 / titles.size());
			for (int i = 0; i < titles.size(); i++) {
				int y = 148 + i * row;
				boolean first = i == 0;
				int h = row - 16;
				rect(64, y, 1152, h, i % 2 == 0 ? gray() : t.bg());
				rect(64, y, h, h, first ? t.accent2() : t.accent());
				tx(64, y + (h - 32) / 2, h, two(i + 1), 24, 16, 1, 800, first ? t.onAccent2() : t.onAccent(), "center",
						1.3);
				Fit f = fit(titles.get(i), 760, 24, 15, 1, 700, false);
				put(h + 96, y + (h - f.height(1.3)) / 2, 760, titles.get(i), f, 700, t.ink(), "left", 1.3, false);
				tx(1016, y + (h - 22) / 2, 176, "p. " + two(i + 3), 16, 13, 1, 600, t.muted(), "right", 1.4);
			}
		}

		int header(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.bg());
			bar(s.title(), String.valueOf(no));
			if (s.message().isEmpty()) {
				return 156;
			}
			return 148 + tx(64, 148, 1152, s.message(), 20, 15, 2, 500, t.body(), "left", 1.5) + 28;
		}

		@Override
		boolean statement(Section s, int no) {
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			tx(64, 64, 900, "KEY MESSAGE  " + two(no) + "  " + s.title(), 16, 13, 1, 800, goldOn(t.accent()), "left",
					1.4);
			Fit f = fit(s.message(), 1060, 56, 32, 3, 700, true);
			rect(64, 170, 8, f.height(1.3), t.accent2());
			put(104, 170, 1060, s.message(), f, 700, t.onAccent(), "left", 1.3, true);
			int n = s.points().size();
			if (n == 0) {
				return true;
			}
			String panel = Theme.mix(t.accent(), Theme.LIGHT, 0.08);
			String ink = Theme.readable(panel);
			String soft = Theme.guard(Theme.mix(ink, panel, 0.25), 4.5, ink, panel);
			int w = (1152 - 24 * (n - 1)) / n;
			int top = Math.max(170 + f.height(1.3) + 50, 470);
			for (Point p : s.points()) {
				if (top + 28 + fit(p.head(), w - 56, 22, 16, 2, 700, false).height(1.3) + 8
						+ fit(p.body(), w - 56, 18, 13, 3, 400, false).height(1.6) + 16 > 640) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				int x = 64 + i * (w + 24);
				rect(x, top, w, 640 - top, panel);
				rect(x, top, w, 4, t.accent2());
				int h = tx(x + 28, top + 28, w - 56, s.points().get(i).head(), 22, 16, 2, 700, ink, "left", 1.3);
				tx(x + 28, top + 36 + h, w - 56, s.points().get(i).body(), 18, 13, 3, 400, soft, "left", 1.6);
			}
			return true;
		}

		@Override
		boolean cards(Section s, int no) {
			int top = header(s, no);
			int n = s.points().size();
			int w = (1152 - 24 * (n - 1)) / n;
			int tallest = 0;
			for (Point p : s.points()) {
				if (fit(p.head(), w - 104, 24, 15, 1, 700, false).lines() > 1) {
					return false;
				}
				tallest = Math.max(tallest, fit(p.body(), w - 48, 20, 14, 6, 400, false).height(1.7));
			}
			if (top + 100 + tallest + 48 > 650) {
				return false;
			}
			int h = Math.max(100 + tallest + 48, Math.min(650 - top, 300));
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + 24);
				box(x, top, w, h, t.bg(), 0, t.line(), 1);
				rect(x, top, w, 72, t.accent());
				tx(x + 24, top + 20, 48, two(i + 1), 24, 24, 1, 800, goldOn(t.accent()), "left", 1.3);
				tx(x + 80, top + 22, w - 104, p.head(), 24, 15, 1, 700, t.onAccent(), "left", 1.3);
				tx(x + 24, top + 100, w - 48, p.body(), 20, 14, 6, 400, t.body(), "left", 1.7);
				rect(x + 24, top + h - 36, 40, 3, t.accent2());
			}
			return true;
		}

		@Override
		boolean compare(Section s, int no) {
			int top = header(s, no);
			String goldLight = Theme.mix(t.accent2(), Theme.LIGHT, 0.75);
			String goldPale = Theme.mix(t.accent2(), Theme.LIGHT, 0.9);
			for (Point p : s.points()) {
				if (top + 104 + fit(p.body(), 500, 21, 14, 6, 500, false).height(1.6) + 24 > 650) {
					return false;
				}
			}
			for (int i = 0; i < 2; i++) {
				Point p = s.points().get(i);
				int x = i == 0 ? 64 : 652;
				Fit head = fit(p.head(), 500, 24, 15, 1, 800, false);
				rect(x, top, 564, 72, i == 0 ? gray() : goldLight);
				String headInk = i == 0 ? t.ink() : Theme.guard(t.accent(), 4.5, t.ink(), goldLight);
				put(x + 32, top + (72 - head.height(1.3)) / 2, 500, p.head(), head, 800, headInk, "left", 1.3, false);
				rect(x, top + 72, 564, 650 - top - 72, i == 0 ? t.bg() : goldPale);
				box(x, top, 564, 650 - top, null, 0, t.line(), 1);
				tx(x + 32, top + 104, 500, p.body(), 21, 14, 6, 500, i == 0 ? t.body() : t.ink(), "left", 1.6);
			}
			return true;
		}

		@Override
		boolean process(Section s, int no) {
			int top = header(s, no);
			int n = s.points().size();
			int w = (1152 - 20 * (n - 1)) / n;
			for (Point p : s.points()) {
				int extra = fits(p.figure(), w - 48, 14, "").isEmpty() ? 0
						: fit(p.head(), w - 10, 26, 16, 2, 700, false).height(1.3) + 10;
				if (top + 128 + extra + fit(p.body(), w - 20, 18, 13, 5, 400, false).height(1.7) > 650) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + 20);
				boolean last = i == n - 1;
				String fill = last ? t.accent2() : i % 2 == 0 ? t.accent() : Theme.mix(t.accent(), Theme.LIGHT, 0.1);
				String ink = Theme.readable(fill);
				rect(x, top, w, 96, fill);
				tx(x + 24, top + 16, w - 48, "STEP " + (i + 1), 14, 12, 1, 800, last ? ink : goldOn(fill), "left", 1.4);
				String figure = fits(p.figure(), w - 48, 14, "");
				tx(x + 24, top + 42, w - 48, figure.isEmpty() ? p.head() : figure, 26, 14, 1, 700, ink, "left", 1.3);
				if (i < n - 1) {
					rect(x + w, top + 44, 20, 8, t.accent2());
				}
				int y = top + 128;
				if (!figure.isEmpty()) {
					y += tx(x, y, w - 10, p.head(), 26, 16, 2, 700, t.ink(), "left", 1.3) + 10;
				}
				tx(x, y, w - 20, p.body(), 18, 13, 5, 400, t.body(), "left", 1.7);
			}
			return true;
		}

		@Override
		boolean list(Section s, int no) {
			int top = header(s, no);
			int n = s.points().size();
			int gap = 16;
			int h = Math.min(120, (650 - top - gap * (n - 1)) / n);
			for (Point p : s.points()) {
				if (fit(p.head(), 1000, 26, 16, 1, 700, false).height(1.3) + 4
						+ fit(p.body(), 1000, 19, 13, 2, 400, false).height(1.5) + 16 > h) {
					return false;
				}
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				Fit head = fit(p.head(), 1000, 26, 16, 1, 700, false);
				Fit body = fit(p.body(), 1000, 19, 13, 2, 400, false);
				int content = head.height(1.3) + 4 + body.height(1.5);
				int y = top + i * (h + gap);
				rect(64, y, 1152, h, gray());
				rect(64, y, 8, h, i == 0 ? t.accent2() : t.accent());
				tx(104, y + (h - 36) / 2, 60, two(i + 1), 28, 20, 1, 800, t.accentText(), "left", 1.2);
				int cy = y + (h - content) / 2;
				put(184, cy, 1000, p.head(), head, 700, t.ink(), "left", 1.3, false);
				put(184, cy + head.height(1.3) + 4, 1000, p.body(), body, 400, t.body(), "left", 1.5, false);
			}
			return true;
		}

		@Override
		void closing(Section s) {
			rect(0, 0, WIDTH, HEIGHT, t.accent());
			tx(64, 72, 400, "CONCLUSION", 16, 16, 1, 800, goldOn(t.accent()), "left", 1.4);
			String big = s.message().isEmpty() ? s.title() : s.message();
			dx(64, 104, 1152, big, 44, 26, 1, t.onAccent(), "left", 1.3);
			rect(64, 180, 1152, 2, t.accent2());
			int n = s.points().size();
			int w = (1152 - 20 * (n - 1)) / n;
			String panel = Theme.mix(t.accent(), Theme.LIGHT, 0.08);
			String ink = Theme.readable(panel);
			String soft = Theme.guard(Theme.mix(ink, panel, 0.25), 4.5, ink, panel);
			if (!cardsFit(s.points(), w - 56, 26, 2, 18, 13, 6, 400 - 132 - 28)) {
				ruled(s.points(), 64, 1152, 230, 660, t.accent2(), t.onAccent(), t.onAccentSoft(), goldOn(t.accent()));
				return;
			}
			for (int i = 0; i < n; i++) {
				Point p = s.points().get(i);
				int x = 64 + i * (w + 20);
				box(x, 240, w, 400, panel, 0, t.accent2(), 1);
				tx(x + 28, 268, 120, two(i + 1), 48, 48, 1, 800, goldOn(panel), "left", 1.1);
				int h = tx(x + 28, 372, w - 56, p.head(), 26, 16, 2, 700, ink, "left", 1.3);
				tx(x + 28, 382 + h, w - 56, p.body(), 18, 13, 6, 400, soft, "left", 1.6);
			}
		}

		@Override
		void footer(String source, int page) {
			boolean filled = out.get(0).style().get("fill").asString().equals(t.accent());
			if (!filled) {
				rect(64, 664, 1152, 1, t.line());
			}
			String color = footColor();
			tx(64, 676, 900, footerTitle(source), 13, 12, 1, 500, color, "left", 1.4);
			tx(1016, 676, 200, two(page), 13, 13, 1, 800, filled ? color : t.accentText(), "right", 1.4);
		}
	}

	// 글자 맞춤

	record Fit(int size, int lines) {

		int height(double lineHeight) {
			return (int) Math.ceil(size * lineHeight * lines) + 4;
		}
	}

	// 줄 수가 maxLines 안에 드는 가장 큰 글자 크기. 끝까지 안 맞으면 가장 작은 크기와 그때의 줄 수
	static Fit fit(String text, int width, int maxSize, int minSize, int maxLines, boolean bold, boolean wide) {
		for (int size = maxSize; size > minSize; size--) {
			int lines = lines(text, width, size, bold, wide);
			if (lines <= maxLines) {
				return new Fit(size, lines);
			}
		}
		return new Fit(minSize, lines(text, width, minSize, bold, wide));
	}

	static int lines(String text, int width, int size, boolean bold, boolean wide) {
		return text.lines().mapToInt(line -> wrap(line, width, size, bold, wide).size()).sum();
	}

	// 띄어쓰기에서 줄을 바꾸고 한 줄보다 긴 낱말만 글자 단위로 나눈다. 보는 프로그램마다 다른 폭을 감안해 4%를 남긴다
	// wide는 폭이 넓은 제목 글꼴(Gmarket Sans)이다
	static List<String> wrap(String text, int width, int size, boolean bold, boolean wide) {
		double limit = width * 0.96;
		double scale = size * (bold ? 1.04 : 1.0) * (wide ? 1.12 : 1.0);
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
				if (used > 0 && used + advance > limit) {
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
	static String broken(String text, int width, int size, boolean bold, boolean wide) {
		List<String> result = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			List<String> lines = wrap(paragraph, width, size, bold, wide);
			List<String> best = lines;
			for (int narrower = width - width / 20; lines.size() > 1 && narrower > width / 2; narrower -= width / 20) {
				List<String> next = wrap(paragraph, narrower, size, bold, wide);
				if (next.size() != lines.size()) {
					break;
				}
				if (!dangling(next) || dangling(best)) {
					best = next;
				}
			}
			result.addAll(carry(best, width, size, bold, wide));
		}
		return String.join("\n", result);
	}

	// 줄 끝에 남은 한 글자 낱말을 다음 줄 앞으로 옮긴다. 옮긴 줄이 폭을 넘으면 그대로 둔다
	private static List<String> carry(List<String> lines, int width, int size, boolean bold, boolean wide) {
		List<String> result = new ArrayList<>(lines);
		for (int index = 0; index < result.size() - 1; index++) {
			String line = result.get(index);
			int space = line.lastIndexOf(' ');
			if (space < 0 || line.codePointCount(space + 1, line.length()) != 1) {
				continue;
			}
			String moved = line.substring(space + 1) + " " + result.get(index + 1);
			if (wrap(moved, width, size, bold, wide).size() == 1) {
				result.set(index, line.substring(0, space));
				result.set(index + 1, moved);
			}
		}
		return result;
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
			return 0.7;
		}
		if (cp >= '0' && cp <= '9') {
			return 0.64;
		}
		if (cp >= 'a' && cp <= 'z') {
			return 0.57;
		}
		if (cp == '%') {
			return 1.0;
		}
		if (cp < 128) {
			return 0.42;
		}
		return 0.9;
	}

}
