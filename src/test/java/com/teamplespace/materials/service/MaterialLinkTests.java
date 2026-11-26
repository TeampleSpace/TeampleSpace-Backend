package com.teamplespace.materials.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

class MaterialLinkTests {

	@Test
	void pdfLinkAnnotationsKeepTheirTextAndAddress() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(72, 700);
				content.showText("Report guide");
				content.endText();
			}
			page.getAnnotations().add(link(new PDRectangle(70, 695, 80, 16), "https://example.com/guide"));
			page.getAnnotations().add(link(new PDRectangle(300, 300, 50, 16), "https://example.com/empty"));
			page.getAnnotations().add(link(new PDRectangle(70, 695, 80, 16), "https://example.com/guide"));
			page.getAnnotations().add(link(new PDRectangle(300, 400, 50, 16), "javascript:alert(1)"));
			PDAnnotationLink script = new PDAnnotationLink();
			script.setAction(new PDActionJavaScript("app.alert(1)"));
			page.getAnnotations().add(script);

			assertThat(MaterialAnalysis.links(page, "Report guide"))
				.isEqualTo("Report guide (https://example.com/guide)\nhttps://example.com/empty");
			// 본문에 이미 보이는 주소는 다시 쓰지 않는다
			assertThat(MaterialAnalysis.links(page, "see https://example.com/guide"))
				.isEqualTo("https://example.com/empty");
		}
	}

	@Test
	void onlyWebAndMailAddressesAreKept() {
		assertThat(MaterialAnalysis.linkTarget(" https://example.com/a ")).isEqualTo("https://example.com/a");
		assertThat(MaterialAnalysis.linkTarget("mailto:team@example.com")).isEqualTo("mailto:team@example.com");
		assertThat(MaterialAnalysis.linkTarget("javascript:alert(1)")).isNull();
		assertThat(MaterialAnalysis.linkTarget("file:///etc/passwd")).isNull();
		assertThat(MaterialAnalysis.linkTarget("https://")).isNull();
		assertThat(MaterialAnalysis.linkTarget("https://a.example/x y")).isNull();
	}

	private static PDAnnotationLink link(PDRectangle rect, String uri) {
		PDAnnotationLink link = new PDAnnotationLink();
		link.setRectangle(rect);
		PDActionURI action = new PDActionURI();
		action.setURI(uri);
		link.setAction(action);
		return link;
	}
}
