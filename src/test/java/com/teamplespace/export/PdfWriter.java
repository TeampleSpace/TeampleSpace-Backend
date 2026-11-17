package com.teamplespace.export;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import tools.jackson.databind.JsonNode;

// 공유 문서 형식을 모양이 고정된 PDF로 바꾼다. 줄바꿈은 여기서 직접 계산한다
final class PdfWriter {

	private static final float PT_PER_PX = (float) PptxWriter.PT_PER_PX;

	// 원을 베지어 곡선 네 개로 그릴 때 쓰는 값
	private static final float CURVE = 0.5522848f;

	private final PDDocument pdf = new PDDocument();

	private final Map<String, PDFont> fonts = new HashMap<>();

	private final Path assets;

	private final Path fontDirectory;

	private PdfWriter(Path assets, Path fontDirectory) {
		this.assets = assets;
		this.fontDirectory = fontDirectory;
	}

	static PDDocument write(JsonNode presentation, Path assets, Path fontDirectory) throws IOException {
		PdfWriter writer = new PdfWriter(assets, fontDirectory);
		float width = (float) presentation.get("canvas").get("width").asDouble();
		float height = (float) presentation.get("canvas").get("height").asDouble();
		Map<String, JsonNode> slides = new HashMap<>();
		presentation.get("slides").forEach(slide -> slides.put(slide.get("id").asString(), slide));
		for (JsonNode slideId : presentation.get("slideIds")) {
			PDPage page = new PDPage(new PDRectangle(width * PT_PER_PX, height * PT_PER_PX));
			writer.pdf.addPage(page);
			try (PDPageContentStream out = new PDPageContentStream(writer.pdf, page)) {
				// 캔버스 좌표를 그대로 쓰도록 원점을 왼쪽 위로 옮기고 아래 방향을 양수로 둔다
				out.transform(new Matrix(PT_PER_PX, 0, 0, -PT_PER_PX, 0, height * PT_PER_PX));
				for (JsonNode element : PptxWriter.inDrawingOrder(slides.get(slideId.asString()))) {
					writer.element(out, element);
				}
			}
		}
		return writer.pdf;
	}

	private void element(PDPageContentStream out, JsonNode element) throws IOException {
		JsonNode geometry = element.get("geometry");
		float x = (float) geometry.get("x").asDouble();
		float y = (float) geometry.get("y").asDouble();
		float width = (float) geometry.get("width").asDouble();
		float height = (float) geometry.get("height").asDouble();
		out.saveGraphicsState();
		double rotation = geometry.get("rotation").asDouble();
		if (rotation != 0) {
			// 요소 중심을 기준으로 시계 방향으로 돌린다
			float centerX = x + width / 2;
			float centerY = y + height / 2;
			out.transform(Matrix.getRotateInstance(Math.toRadians(rotation), centerX, centerY));
			out.transform(Matrix.getTranslateInstance(-centerX, -centerY));
		}
		switch (element.get("type").asString()) {
			case "text" -> text(out, element, x, y, width);
			case "shape" -> shape(out, element, x, y, width, height);
			case "image" -> image(out, element, x, y, width, height);
			default -> throw new IllegalArgumentException("지원하지 않는 요소");
		}
		out.restoreGraphicsState();
	}

	private void text(PDPageContentStream out, JsonNode element, float x, float y, float width) throws IOException {
		JsonNode style = element.get("style");
		PDFont font = font(ExportFonts.of(style).fileName());
		float size = (float) style.get("fontSize").asDouble();
		float lineHeight = (float) style.get("lineHeight").asDouble() * size;
		float ascent = font.getFontDescriptor().getAscent() / 1000 * size;
		float descent = -font.getFontDescriptor().getDescent() / 1000 * size;
		// 줄 높이에서 글자 높이를 뺀 나머지를 위아래로 반씩 나눈다
		float baseline = (lineHeight - (ascent + descent)) / 2 + ascent;
		Color color = Color.decode(style.get("color").asString());
		String align = style.get("align").asString();
		List<String> lines = wrap(element.get("content").get("text").asString(), font, size, width);
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			float lineWidth = width(font, size, line);
			float left = align.equals("center") ? x + (width - lineWidth) / 2
					: align.equals("right") ? x + width - lineWidth : x;
			out.beginText();
			out.setFont(font, size);
			out.setNonStrokingColor(color);
			// 뒤집힌 좌표에서 글자가 바로 서도록 글자만 다시 뒤집는다
			out.setTextMatrix(new Matrix(1, 0, 0, -1, left, y + i * lineHeight + baseline));
			out.showText(line);
			out.endText();
		}
	}

	// 띄어쓰기에서 줄을 바꾸고 한 줄보다 긴 낱말만 글자 단위로 나눈다
	static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
		List<String> lines = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			String current = "";
			for (String word : paragraph.split(" ", -1)) {
				String candidate = current.isEmpty() ? word : current + " " + word;
				if (width(font, size, candidate) <= maxWidth) {
					current = candidate;
					continue;
				}
				if (!current.isEmpty()) {
					lines.add(current);
				}
				while (width(font, size, word) > maxWidth && word.length() > 1) {
					int end = 1;
					while (end < word.length() && width(font, size, word.substring(0, end + 1)) <= maxWidth) {
						end++;
					}
					lines.add(word.substring(0, end));
					word = word.substring(end);
				}
				current = word;
			}
			lines.add(current);
		}
		return lines;
	}

	private static float width(PDFont font, float size, String text) throws IOException {
		return font.getStringWidth(text) / 1000 * size;
	}

	private void shape(PDPageContentStream out, JsonNode element, float x, float y, float width, float height)
			throws IOException {
		JsonNode style = element.get("style");
		boolean ellipse = element.get("content").get("shape").asString().equals("ellipse");
		float radius = ellipse ? 0 : style.has("cornerRadius") ? (float) style.get("cornerRadius").asDouble() : 0;
		float rx = ellipse ? width / 2 : Math.min(radius, Math.min(width, height) / 2);
		float ry = ellipse ? height / 2 : rx;
		if (rx == 0) {
			out.addRect(x, y, width, height);
		}
		else {
			roundedPath(out, x, y, width, height, rx, ry);
		}
		boolean fill = !style.get("fill").isNull();
		boolean stroke = !style.get("stroke").isNull() && style.get("strokeWidth").asDouble() > 0;
		if (fill) {
			out.setNonStrokingColor(Color.decode(style.get("fill").asString()));
		}
		if (stroke) {
			out.setStrokingColor(Color.decode(style.get("stroke").asString()));
			out.setLineWidth((float) style.get("strokeWidth").asDouble());
		}
		if (fill && stroke) {
			out.fillAndStroke();
		}
		else if (fill) {
			out.fill();
		}
		else if (stroke) {
			out.stroke();
		}
	}

	// 모서리 반지름이 변의 절반이면 타원이 된다
	private static void roundedPath(PDPageContentStream out, float x, float y, float width, float height, float rx,
			float ry) throws IOException {
		float cx = rx * CURVE;
		float cy = ry * CURVE;
		float right = x + width;
		float bottom = y + height;
		out.moveTo(x + rx, y);
		out.lineTo(right - rx, y);
		out.curveTo(right - rx + cx, y, right, y + ry - cy, right, y + ry);
		out.lineTo(right, bottom - ry);
		out.curveTo(right, bottom - ry + cy, right - rx + cx, bottom, right - rx, bottom);
		out.lineTo(x + rx, bottom);
		out.curveTo(x + rx - cx, bottom, x, bottom - ry + cy, x, bottom - ry);
		out.lineTo(x, y + ry);
		out.curveTo(x, y + ry - cy, x + rx - cx, y, x + rx, y);
		out.closePath();
	}

	private void image(PDPageContentStream out, JsonNode element, float x, float y, float width, float height)
			throws IOException {
		JsonNode content = element.get("content");
		PDImageXObject image = PDImageXObject
			.createFromFile(assets.resolve(content.get("fileObjectId").asString() + ".png").toString(), pdf);
		float cropX = 0;
		float cropY = 0;
		float cropWidth = 1;
		float cropHeight = 1;
		if (content.has("crop")) {
			cropX = (float) content.get("crop").get("x").asDouble();
			cropY = (float) content.get("crop").get("y").asDouble();
			cropWidth = (float) content.get("crop").get("width").asDouble();
			cropHeight = (float) content.get("crop").get("height").asDouble();
		}
		// 요소 영역 밖은 가리고 잘라 낸 부분이 영역을 채우도록 원본을 키워서 그린다
		out.addRect(x, y, width, height);
		out.clip();
		float fullWidth = width / cropWidth;
		float fullHeight = height / cropHeight;
		float top = y - cropY * fullHeight;
		// 이미지가 뒤집히지 않도록 이미지 영역 안에서만 위아래를 다시 뒤집는다
		out.transform(new Matrix(1, 0, 0, -1, 0, 2 * top + fullHeight));
		out.drawImage(image, x - cropX * fullWidth, top, fullWidth, fullHeight);
	}

	private PDFont font(String fileName) throws IOException {
		PDFont font = fonts.get(fileName);
		if (font == null) {
			// 쓰인 글자만 PDF 안에 넣는다
			font = PDType0Font.load(pdf, fontDirectory.resolve(fileName).toFile());
			fonts.put(fileName, font);
		}
		return font;
	}

}
