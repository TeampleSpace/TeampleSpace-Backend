package com.teamplespace.materials.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.teamplespace.materials.infra.FileStorage;
import com.teamplespace.materials.infra.LinkFetcher;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.processing.service.JobFailure;
import com.teamplespace.processing.service.JobHandler;
import com.teamplespace.processing.service.JobQueue.Job;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 자료 분석 작업. 보관한 원본에서 쪽수와 본문을 추출해 새 개정과 본문 조각으로 저장한다
@Component
public class MaterialAnalysis implements JobHandler {

	public static final String TYPE = "material_analysis";

	record Chunk(String text, Integer page) {
	}

	// 실행 결과. 저장은 완료 트랜잭션에서 한다
	record Analysis(UUID fileId, byte[] contentHash, Integer pageCount, String text, List<Chunk> chunks,
			String extractor, String sourceUrl, String sourceTitle) {
	}

	private static final int CHUNK_CHARS = 4000;

	// ponytail: 큰 문서는 앞부분만 추출한다. 파서 격리 실행과 함께 한도를 다시 정한다
	private static final int MAX_PAGES = 2000;

	private static final int MAX_TEXT_CHARS = 2_000_000;

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final FileStorage storage;

	private final MaterialService materials;

	private final LinkFetcher links;

	public MaterialAnalysis(JdbcClient jdbc, ObjectMapper json, FileStorage storage, MaterialService materials,
			LinkFetcher links) {
		this.links = links;
		this.jdbc = jdbc;
		this.json = json;
		this.storage = storage;
		this.materials = materials;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Object execute(Job job) {
		Map<String, Object> source = jdbc.sql("""
				SELECT m.kind, m.deleted_at, m.source_url, BIN_TO_UUID(f.id) AS file_id, f.bucket_key, f.checksum
				FROM materials m LEFT JOIN file_objects f ON f.id = m.source_file_id
				WHERE m.id = UUID_TO_BIN(?)
				""").param(job.targetId().toString()).query().listOfRows().stream().findFirst().orElse(null);
		if (source == null || source.get("deleted_at") != null) {
			throw JobFailure.targetGone();
		}
		if (source.get("kind").equals("file")) {
			return pdf(UUID.fromString((String) source.get("file_id")), (String) source.get("bucket_key"),
					(byte[]) source.get("checksum"));
		}
		if (source.get("kind").equals("link")) {
			LinkFetcher.Page page = links.fetch((String) source.get("source_url"));
			// 로그인 화면이나 빈 껍데기는 준비 완료로 두지 않아 발표 구성의 근거 후보에서 빠진다
			if (!readable(page.text())) {
				throw noContent();
			}
			List<Chunk> chunks = new ArrayList<>();
			String text = page.text().substring(0, Math.min(page.text().length(), MAX_TEXT_CHARS));
			for (int start = 0; start < text.length(); start += CHUNK_CHARS) {
				chunks.add(new Chunk(text.substring(start, Math.min(text.length(), start + CHUNK_CHARS)), null));
			}
			return new Analysis(null, CanonicalJson.sha256(text), null, text, chunks, "jsoup-1", page.url(), page.title());
		}
		throw new JobFailure("ANALYSIS_FAILED", "자료를 분석하지 못했어요.", false);
	}

	@Override
	public ObjectNode publish(Job job, Object output) {
		Analysis analysis = (Analysis) output;
		MaterialService.Row row = materials.find(job.targetId(), true);
		if (row.deleted()) {
			throw JobFailure.targetGone();
		}
		Chunk lead = analysis.chunks()
			.stream()
			.filter(chunk -> MaterialService.excerpt(chunk.text()) != null && chunk.text().strip().length() >= 40)
			.findFirst()
			.or(() -> analysis.chunks().stream().filter(chunk -> MaterialService.excerpt(chunk.text()) != null).findFirst())
			.orElse(null);
		Map<String, Object> columns = new LinkedHashMap<>();
		columns.put("sourceFile", analysis.fileId() == null ? null : analysis.fileId().toString());
		columns.put("extractedText", analysis.text());
		columns.put("pageCount", analysis.pageCount());
		columns.put("excerpt", lead == null ? null : MaterialService.excerpt(lead.text()));
		columns.put("excerptPage", lead == null ? null : lead.page());
		columns.put("contentHash", analysis.contentHash());
		columns.put("extractor", analysis.extractor());
		columns.put("sourceUrl", analysis.sourceUrl());
		columns.put("sourceTitle", analysis.sourceTitle());
		// 요약 모델이 정해지면 여기서 summary를 채운다
		UUID revisionId = materials.addRevision(row, job.requesterId(), columns);
		int ordinal = 0;
		for (Chunk chunk : analysis.chunks()) {
			jdbc.sql("""
					INSERT INTO material_chunks (id, project_id, revision_id, ordinal, page_start, page_end, text)
					VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)
					""")
				.params(UUID.randomUUID().toString(), row.projectId().toString(), revisionId.toString(), ordinal++,
						chunk.page(), chunk.page(), chunk.text())
				.update();
		}
		// 제목을 비워 등록한 링크는 수집한 문서 제목을 쓴다. 그사이 사용자가 바꾼 이름은 그대로 둔다
		if (analysis.sourceTitle() != null && row.kind().equals("link")) {
			jdbc.sql("""
					UPDATE materials SET title = ?
					WHERE id = UUID_TO_BIN(?) AND title = SUBSTRING_INDEX(SUBSTRING_INDEX(SUBSTRING_INDEX(source_url, '://', -1), '/', 1), ':', 1)
					""").params(analysis.sourceTitle(), row.id().toString()).update();
		}
		materials.markReady(row.id());
		ObjectNode result = json.createObjectNode();
		result.put("materialId", row.id().toString());
		result.put("revisionId", revisionId.toString());
		return result;
	}

	@Override
	public void settle(Job job, String status) {
		materials.markFailed(job.targetId());
	}

	// 쪽마다 본문을 뽑는다. 글이 없는 스캔 문서도 쪽수는 저장한다
	private Analysis pdf(UUID fileId, String key, byte[] checksum) {
		try (PDDocument document = Loader.loadPDF(storage.path(key).toFile())) {
			if (document.isEncrypted()) {
				throw unreadable();
			}
			int pages = document.getNumberOfPages();
			if (pages < 1) {
				throw unreadable();
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			List<Chunk> chunks = new ArrayList<>();
			StringBuilder all = new StringBuilder();
			for (int page = 1; page <= Math.min(pages, MAX_PAGES) && all.length() < MAX_TEXT_CHARS; page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				String text = clean(stripper.getText(document));
				String linked = links(document.getPage(page - 1), text);
				text = text.isEmpty() ? linked : linked.isEmpty() ? text : text + "\n\n" + linked;
				if (text.isEmpty()) {
					continue;
				}
				text = text.substring(0, Math.min(text.length(), MAX_TEXT_CHARS - all.length()));
				all.append(text).append("\n\n");
				for (int start = 0; start < text.length(); start += CHUNK_CHARS) {
					chunks.add(new Chunk(text.substring(start, Math.min(text.length(), start + CHUNK_CHARS)), page));
				}
			}
			return new Analysis(fileId, checksum, pages, all.toString().strip(), chunks, "pdfbox-3", null, null);
		}
		catch (JobFailure failure) {
			throw failure;
		}
		catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException ex) {
			// 보관소를 일시적으로 읽지 못한 경우는 다시 시도한다
			throw new JobFailure("ANALYSIS_FAILED", "자료를 분석하지 못했어요.", true);
		}
		catch (IOException | RuntimeException ex) {
			// 손상됐거나 암호가 걸린 파일은 다시 시도해도 같다
			throw unreadable();
		}
	}

	// 쪽의 링크 주석을 글자와 주소 한 줄씩으로 만든다. 본문에 이미 보이는 주소는 빼고 같은 주소는 한 번만 쓴다
	static String links(PDPage page, String pageText) throws IOException {
		// 주소마다 첫 링크 영역만 쓰고 영역을 모두 등록한 뒤 쪽을 한 번만 해석한다
		Map<String, PDRectangle> targets = new LinkedHashMap<>();
		for (PDAnnotation annotation : page.getAnnotations()) {
			if (!(annotation instanceof PDAnnotationLink link) || !(link.getAction() instanceof PDActionURI action)) continue;
			String url = linkTarget(action.getURI());
			if (url != null && !targets.containsKey(url) && !shown(pageText, url)) targets.put(url, link.getRectangle());
		}
		if (targets.isEmpty()) return "";
		PDFTextStripperByArea area = new PDFTextStripperByArea();
		area.setSortByPosition(true);
		List<String> urls = new ArrayList<>(targets.keySet());
		for (int index = 0; index < urls.size(); index++) {
			PDRectangle rect = targets.get(urls.get(index));
			if (rect != null) area.addRegion("link" + index, region(rect, page.getCropBox(), page.getRotation()));
		}
		if (!area.getRegions().isEmpty()) area.extractRegions(page);
		List<String> lines = new ArrayList<>();
		for (int index = 0; index < urls.size(); index++) {
			String url = urls.get(index);
			String text = area.getRegions().contains("link" + index) ? area.getTextForRegion("link" + index) : null;
			String label = text == null ? "" : clean(text).replaceAll("\\s+", " ");
			lines.add(label.isEmpty() || label.equals(url) ? url : label + " (" + url + ")");
		}
		return String.join("\n", lines);
	}


	// 주석 영역을 글자 위치와 같은 좌표로 바꾼다. 글자 위치는 쪽 회전을 반영하고 위가 원점이다
	static java.awt.geom.Rectangle2D region(PDRectangle rect, PDRectangle box, int rotation) {
		float width = box.getWidth();
		float height = box.getHeight();
		float[][] corners = { { rect.getLowerLeftX(), rect.getLowerLeftY() }, { rect.getUpperRightX(), rect.getUpperRightY() } };
		java.awt.geom.Rectangle2D.Float area = null;
		for (float[] corner : corners) {
			float x = corner[0] - box.getLowerLeftX();
			float y = corner[1] - box.getLowerLeftY();
			float[] point = switch (((rotation % 360) + 360) % 360) {
				case 90 -> new float[] { y, x };
				case 180 -> new float[] { width - x, y };
				case 270 -> new float[] { height - y, width - x };
				default -> new float[] { x, height - y };
			};
			if (area == null) area = new java.awt.geom.Rectangle2D.Float(point[0], point[1], 0, 0);
			else area.add(point[0], point[1]);
		}
		return area;
	}

	// 본문에 주소 전체가 그대로 보이는지 확인한다. 더 긴 주소의 앞부분인 경우는 다른 주소로 본다
	static boolean shown(String text, String url) {
		return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(url) + "(?=$|[\\s)\\]>\"']|[.,;:!?](?:$|\\s))")
			.matcher(text).find();
	}

	// 본문에 남길 링크 주소. 웹 주소와 메일 주소만 받는다
	public static String linkTarget(String raw) {
		if (raw == null || raw.isBlank() || raw.strip().length() > 2048) return null;
		String url = raw.strip();
		String lower = url.toLowerCase(java.util.Locale.ROOT);
		boolean web = (lower.startsWith("http://") || lower.startsWith("https://")) && url.length() > lower.indexOf("//") + 2;
		boolean mail = lower.startsWith("mailto:") && url.length() > 7;
		return (web || mail) && url.chars().noneMatch(c -> c < ' ' || c == 127 || Character.isWhitespace(c)) ? url : null;
	}

	private static final int MIN_READABLE_CHARS = 100;

	private static final int LOGIN_WALL_CHARS = 500;

	private static final java.util.regex.Pattern ADDRESSES = java.util.regex.Pattern.compile("(https?://|mailto:)\\S+");

	private static final java.util.regex.Pattern LOGIN = java.util.regex.Pattern
		.compile("\\b(log ?in|sign ?(in|up))\\b|로그인|회원가입", java.util.regex.Pattern.CASE_INSENSITIVE);

	// 주소를 뺀 글자가 100자 미만이거나, 500자 미만인데 로그인 안내가 있으면 읽지 못한 링크로 본다
	// ponytail: 글자 수와 문구만 보는 어림이다. 짧은 정상 글은 막고 화면 틀 글자만 많은 페이지는 통과한다
	static boolean readable(String text) {
		String plain = ADDRESSES.matcher(text).replaceAll("");
		int letters = plain.replaceAll("\\s+", "").length();
		return letters >= MIN_READABLE_CHARS && (letters >= LOGIN_WALL_CHARS || !LOGIN.matcher(plain).find());
	}

	public static JobFailure noContent() {
		return new JobFailure("LINK_NO_CONTENT", "읽을 수 있는 본문이 없어요.", false);
	}

	private static JobFailure unreadable() {
		return new JobFailure("MATERIAL_UNREADABLE", "파일을 읽을 수 없어요.", false);
	}

	// 저장할 수 없는 제어 문자를 지우고 줄 끝 공백을 정리한다
	public static String clean(String text) {
		StringBuilder cleaned = new StringBuilder(text.length());
		int lineEnd = 0;
		for (int index = 0; index < text.length(); index++) {
			char value = text.charAt(index);
			if (value == '\n') {
				cleaned.setLength(lineEnd);
				cleaned.append(value);
				lineEnd = cleaned.length();
			}
			else if ((value >= ' ' && value != 127) || value == '\t') {
				cleaned.append(value);
				if (value != ' ' && value != '\t') lineEnd = cleaned.length();
			}
		}
		return cleaned.toString().strip();
	}

}
