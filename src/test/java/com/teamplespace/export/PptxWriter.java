package com.teamplespace.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.sl.usermodel.TextShape.TextAutofit;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGeomGuide;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPresetGeometry2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTRelativeRect;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

import tools.jackson.databind.JsonNode;

// 공유 문서 형식을 PowerPoint에서 편집할 수 있는 PPTX로 바꾼다
final class PptxWriter {

	// 캔버스 1px을 0.75pt로 본다. 1280x720 캔버스가 16대 9 기본 슬라이드 크기가 된다
	static final double PT_PER_PX = 0.75;

	private PptxWriter() {
	}

	static XMLSlideShow write(JsonNode presentation, Path assets) throws IOException {
		XMLSlideShow ppt = new XMLSlideShow();
		JsonNode canvas = presentation.get("canvas");
		ppt.setPageSize(new Dimension((int) Math.round(canvas.get("width").asDouble() * PT_PER_PX),
				(int) Math.round(canvas.get("height").asDouble() * PT_PER_PX)));
		Map<String, JsonNode> slides = new HashMap<>();
		presentation.get("slides").forEach(slide -> slides.put(slide.get("id").asString(), slide));
		for (JsonNode slideId : presentation.get("slideIds")) {
			XSLFSlide target = ppt.createSlide();
			for (JsonNode element : inDrawingOrder(slides.get(slideId.asString()))) {
				switch (element.get("type").asString()) {
					case "text" -> text(target, element);
					case "shape" -> shape(target, element);
					case "image" -> image(ppt, target, element, assets);
					default -> throw new IllegalArgumentException("지원하지 않는 요소");
				}
			}
		}
		return ppt;
	}

	static List<JsonNode> inDrawingOrder(JsonNode slide) {
		List<JsonNode> elements = new ArrayList<>();
		slide.get("elements").forEach(elements::add);
		elements.sort(Comparator.comparingInt(element -> element.get("geometry").get("z").asInt()));
		return elements;
	}

	private static void text(XSLFSlide slide, JsonNode element) {
		JsonNode style = element.get("style");
		ExportFonts font = ExportFonts.of(style);
		double size = style.get("fontSize").asDouble() * PT_PER_PX;
		XSLFTextBox box = slide.createTextBox();
		box.setAnchor(anchor(element));
		rotate(box, element);
		box.setWordWrap(true);
		box.setTextAutofit(TextAutofit.NONE);
		box.setInsets(new Insets2D(0, 0, 0, 0));
		box.setVerticalAlignment(VerticalAlignment.TOP);
		// 문서의 개행마다 문단을 나눈다. 글상자에 처음부터 있는 빈 문단을 첫 줄로 쓴다
		String[] lines = element.get("content").get("text").asString().split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			XSLFTextParagraph paragraph = i == 0 ? box.getTextParagraphs().get(0) : box.addNewTextParagraph();
			paragraph.setTextAlign(TextAlign.valueOf(style.get("align").asString().toUpperCase()));
			// 음수는 배수가 아니라 포인트 고정 줄 간격이다
			paragraph.setLineSpacing(-style.get("lineHeight").asDouble() * size);
			paragraph.setSpaceBefore(0d);
			paragraph.setSpaceAfter(0d);
			// 처음부터 있는 빈 글자 묶음이 있으면 그것을 쓴다
			XSLFTextRun run = paragraph.getTextRuns().isEmpty() ? paragraph.addNewTextRun()
					: paragraph.getTextRuns().get(0);
			run.setText(lines[i]);
			// 한글에는 동아시아 글꼴 지정이 쓰이므로 둘 다 지정한다
			run.setFontFamily(font.family(), FontGroup.LATIN);
			run.setFontFamily(font.family(), FontGroup.EAST_ASIAN);
			run.setFontSize(size);
			run.setBold(font.bold());
			run.setFontColor(Color.decode(style.get("color").asString()));
		}
	}

	private static void shape(XSLFSlide slide, JsonNode element) {
		JsonNode style = element.get("style");
		JsonNode geometry = element.get("geometry");
		boolean ellipse = element.get("content").get("shape").asString().equals("ellipse");
		double radius = style.has("cornerRadius") ? style.get("cornerRadius").asDouble() : 0;
		XSLFAutoShape shape = slide.createAutoShape();
		shape.setShapeType(ellipse ? ShapeType.ELLIPSE : radius > 0 ? ShapeType.ROUND_RECT : ShapeType.RECT);
		shape.setAnchor(anchor(element));
		rotate(shape, element);
		shape.setFillColor(style.get("fill").isNull() ? null : Color.decode(style.get("fill").asString()));
		if (style.get("stroke").isNull() || style.get("strokeWidth").asDouble() == 0) {
			shape.setLineColor(null);
		}
		else {
			shape.setLineColor(Color.decode(style.get("stroke").asString()));
			shape.setLineWidth(style.get("strokeWidth").asDouble() * PT_PER_PX);
		}
		if (!ellipse && radius > 0) {
			// 둥근 모서리는 짧은 변에 대한 비율로 적는다
			double shorter = Math.min(geometry.get("width").asDouble(), geometry.get("height").asDouble());
			CTPresetGeometry2D preset = ((CTShape) shape.getXmlObject()).getSpPr().getPrstGeom();
			CTGeomGuide guide = (preset.isSetAvLst() ? preset.getAvLst() : preset.addNewAvLst()).addNewGd();
			guide.setName("adj");
			guide.setFmla("val " + Math.min(50000, Math.round(radius / shorter * 100000)));
		}
	}

	private static void image(XMLSlideShow ppt, XSLFSlide slide, JsonNode element, Path assets) throws IOException {
		JsonNode content = element.get("content");
		byte[] bytes = Files.readAllBytes(assets.resolve(content.get("fileObjectId").asString() + ".png"));
		XSLFPictureData data = ppt.addPicture(bytes, PictureData.PictureType.PNG);
		XSLFPictureShape picture = slide.createPicture(data);
		picture.setAnchor(anchor(element));
		rotate(picture, element);
		CTPicture xml = (CTPicture) picture.getXmlObject();
		xml.getNvPicPr().getCNvPr().setDescr(content.get("alt").asString());
		if (content.has("crop")) {
			// 자르기는 네 변에서 잘라 낼 비율을 10만분율로 적는다
			JsonNode crop = content.get("crop");
			CTRelativeRect source = xml.getBlipFill().isSetSrcRect() ? xml.getBlipFill().getSrcRect()
					: xml.getBlipFill().addNewSrcRect();
			double right = 1 - crop.get("x").asDouble() - crop.get("width").asDouble();
			double bottom = 1 - crop.get("y").asDouble() - crop.get("height").asDouble();
			source.setL((int) Math.round(crop.get("x").asDouble() * 100000));
			source.setT((int) Math.round(crop.get("y").asDouble() * 100000));
			source.setR((int) Math.round(right * 100000));
			source.setB((int) Math.round(bottom * 100000));
		}
	}

	private static void rotate(XSLFSimpleShape shape, JsonNode element) {
		double rotation = element.get("geometry").get("rotation").asDouble();
		if (rotation != 0) {
			// PowerPoint가 쓰는 0도 이상 360도 미만 값으로 맞춘다
			shape.setRotation(((rotation % 360) + 360) % 360);
		}
	}

	private static Rectangle2D anchor(JsonNode element) {
		JsonNode geometry = element.get("geometry");
		return new Rectangle2D.Double(geometry.get("x").asDouble() * PT_PER_PX,
				geometry.get("y").asDouble() * PT_PER_PX, geometry.get("width").asDouble() * PT_PER_PX,
				geometry.get("height").asDouble() * PT_PER_PX);
	}

}
