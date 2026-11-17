package com.teamplespace.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Dimension;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;

import com.teamplespace.contracts.ContractSchemas;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 공유 문서 예제를 실제 PPTX와 PDF 파일로 만들어 보는 실험
// Pretendard 글꼴 파일이 있어야 실행되고 없으면 건너뛴다. 결과는 experiments/export/README.md에 기록한다
class ExportExperimentTests {

	static {
		System.setProperty("java.awt.headless", "true");
	}

	static final Path EXAMPLES = ContractSchemas.CONTRACTS.resolve("examples");

	static final Path OUTPUT = Path.of("build", "export-experiment");

	@Test
	void sharedExampleBecomesPptxAndPdf() throws Exception {
		Path fonts = ExportFonts.directory();
		assumeTrue(ExportFonts.available(fonts), "Pretendard 글꼴 파일이 없어 출력 실험을 건너뛴다");
		ObjectNode presentation = (ObjectNode) new ObjectMapper().readTree(Files.readString(EXAMPLES.resolve("presentation.json")));
		Path assets = EXAMPLES.resolve("assets");
		Files.createDirectories(OUTPUT);
		Path pptxFile = OUTPUT.resolve("presentation.pptx");
		Path pdfFile = OUTPUT.resolve("presentation.pdf");

		try (XMLSlideShow pptx = PptxWriter.write(presentation, assets);
				OutputStream out = Files.newOutputStream(pptxFile)) {
			pptx.write(out);
		}
		try (PDDocument pdf = PdfWriter.write(presentation, assets, fonts)) {
			pdf.save(pdfFile.toFile());
		}
		// 뷰어의 미리보기는 첫 장만 보여 주므로 장마다 따로 저장해 둔다
		for (int i = 0; i < presentation.get("slideIds").size(); i++) {
			ObjectNode single = presentation.deepCopy();
			single.putArray("slideIds").add(presentation.get("slideIds").get(i));
			try (XMLSlideShow pptx = PptxWriter.write(single, assets);
					OutputStream out = Files.newOutputStream(OUTPUT.resolve("pptx-slide-" + (i + 1) + ".pptx"))) {
				pptx.write(out);
			}
		}

		// 만든 파일을 다시 열어 구조와 글자를 확인한다
		try (InputStream in = Files.newInputStream(pptxFile); XMLSlideShow reopened = new XMLSlideShow(in)) {
			assertThat(reopened.getPageSize()).isEqualTo(new Dimension(960, 540));
			assertThat(reopened.getSlides()).hasSize(2);
			assertThat(reopened.getSlides().get(0).getShapes()).hasSize(4);
			assertThat(reopened.getSlides().get(1).getShapes()).hasSize(8);
			String text = text(reopened);
			assertThat(text).contains("팀플 발표 자료를 함께 만드는 방법");
			assertThat(text).contains("출처: 대학생 협업 실태 조사 보고서 12쪽");
			XSLFPictureShape picture = reopened.getSlides()
				.get(1)
				.getShapes()
				.stream()
				.filter(XSLFPictureShape.class::isInstance)
				.map(XSLFPictureShape.class::cast)
				.findFirst()
				.orElseThrow();
			assertThat(picture.getClipping().left).isEqualTo(5000);
		}
		try (PDDocument reopened = Loader.loadPDF(pdfFile.toFile())) {
			assertThat(reopened.getNumberOfPages()).isEqualTo(2);
			assertThat(reopened.getPage(0).getMediaBox().getWidth()).isEqualTo(960f);
			assertThat(reopened.getPage(0).getMediaBox().getHeight()).isEqualTo(540f);
			// 글자가 도형이 아니라 선택하고 검색할 수 있는 글자로 들어갔는지 본다
			String text = new PDFTextStripper().getText(reopened);
			assertThat(text).contains("팀플 발표 자료를 함께 만드는 방법");
			assertThat(text).contains("출처: 대학생 협업 실태 조사 보고서 12쪽");
			List<String> fontNames = new ArrayList<>();
			for (PDPage page : reopened.getPages()) {
				for (var name : page.getResources().getFontNames()) {
					PDFont font = page.getResources().getFont(name);
					assertThat(font.isEmbedded()).as(font.getName()).isTrue();
					fontNames.add(font.getName());
				}
			}
			assertThat(fontNames).isNotEmpty();
			render(reopened, "pdf-page-");
		}
		// PowerPoint에서 PPTX를 열어 PDF로 내보낸 파일을 이 위치에 두면 비교용 그림을 만든다
		Path fromPowerPoint = OUTPUT.resolve("powerpoint-export.pdf");
		if (Files.exists(fromPowerPoint)) {
			try (PDDocument exported = Loader.loadPDF(fromPowerPoint.toFile())) {
				render(exported, "powerpoint-page-");
			}
		}
	}

	static void render(PDDocument pdf, String prefix) throws Exception {
		PDFRenderer renderer = new PDFRenderer(pdf);
		for (int i = 0; i < pdf.getNumberOfPages(); i++) {
			ImageIO.write(renderer.renderImageWithDPI(i, 96), "png", OUTPUT.resolve(prefix + (i + 1) + ".png").toFile());
		}
	}

	static String text(XMLSlideShow slides) {
		StringBuilder text = new StringBuilder();
		for (XSLFSlide slide : slides.getSlides()) {
			for (XSLFShape shape : slide.getShapes()) {
				if (shape instanceof XSLFTextShape textShape) {
					text.append(textShape.getText()).append('\n');
				}
			}
		}
		return text.toString();
	}

}
