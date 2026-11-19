package com.teamplespace.materials;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.processing.JobQueue;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

// PDF 업로드, 검사, 분석, 다시 시도, 원문 받기를 실제 파일과 HTTP 요청, MySQL로 확인
class FileMaterialHttpTests extends HttpIntegrationSupport {

	private static final String S = "stage-2.schema.json#/$defs/";

	// 파일 출력 실험이 만든 실제 한글 PDF
	private static final Path KOREAN_PDF = Path.of("experiments/export/output/presentation.pdf");

	@Autowired
	JobQueue queue;

	@Autowired
	FileStorage storage;

	private void assertMatches(String schema, JsonNode value) {
		assertThat(ContractSchemas.validate(schema, json.writeValueAsString(value))).as(value.toString()).isEmpty();
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	// 쪽마다 다른 글이 든 PDF를 만든다
	private static byte[] pdf(int pages) throws Exception {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			for (int number = 1; number <= pages; number++) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
					content.newLineAtOffset(72, 700);
					content.showText("Survey page " + number + " shows how many students return reusable cups on campus.");
					content.endText();
				}
			}
			document.save(output);
			return output.toByteArray();
		}
	}

	private Map<String, Object> uploadBody(byte[] bytes, String name) throws Exception {
		return Map.of("kind", "material_pdf", "fileName", name, "mimeType", "application/pdf", "sizeBytes", bytes.length,
				"checksumSha256", sha256(bytes));
	}

	private JsonNode prepare(Browser browser, String projectId, byte[] bytes, String name) throws Exception {
		JsonNode ticket = browser.request("POST", "/projects/" + projectId + "/uploads", uploadBody(bytes, name), 201);
		assertMatches(S + "UploadTicket", ticket);
		return ticket;
	}

	private int put(Browser browser, JsonNode ticket, byte[] bytes) throws Exception {
		// 전송에는 발급받은 헤더만 싣는다
		return browser.raw("PUT", ticket.get("uploadUrl").asString(), bytes, Map.of("Content-Type", "application/pdf"))
			.statusCode();
	}

	private JsonNode register(Browser browser, String projectId, byte[] bytes, String name) throws Exception {
		JsonNode ticket = prepare(browser, projectId, bytes, name);
		assertThat(put(browser, ticket, bytes)).isEqualTo(204);
		return browser.post("/projects/" + projectId + "/materials/file",
				Map.of("uploadId", ticket.get("uploadId").asString()), 201);
	}

	// 대기 중인 작업을 실행 풀 대신 차례로 처리한다
	private void runJobs() {
		for (var claim = queue.claim("test"); claim.isPresent(); claim = queue.claim("test")) {
			queue.run(claim.get());
		}
	}

	private int count(String sql, String id) {
		return jdbc.sql(sql).param(id).query(Integer.class).single();
	}

	@Test
	void realPdfIsUploadedAnalyzedAndReadBackWithItsPages() throws Exception {
		Browser owner = signup("PDF 소유자");
		String projectId = createProject(owner, "PDF 등록 " + UUID.randomUUID());
		byte[] bytes = Files.readAllBytes(KOREAN_PDF);
		int actualPages;
		try (PDDocument document = Loader.loadPDF(bytes)) {
			actualPages = document.getNumberOfPages();
		}

		JsonNode capabilities = owner.get("/capabilities");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/Capabilities", json.writeValueAsString(capabilities)))
			.isEmpty();
		assertThat(capabilities.get("uploadTypes").get(0).get("mimeType").asString()).isEqualTo("application/pdf");

		JsonNode ticket = prepare(owner, projectId, bytes, "교내 일회용품 조사.pdf");
		String registerPath = "/projects/" + projectId + "/materials/file";
		Map<String, Object> body = Map.of("uploadId", ticket.get("uploadId").asString());
		// 파일을 보내기 전에는 등록할 수 없다
		assertThat(owner.post(registerPath, body, 409).get("error").get("code").asString()).isEqualTo("UPLOAD_INCOMPLETE");
		assertThat(put(owner, ticket, bytes)).isEqualTo(204);

		String key = UUID.randomUUID().toString();
		JsonNode created = owner.request("POST", registerPath, body, 201, key);
		assertMatches(S + "MaterialCreated", created);
		// 응답을 받지 못해 같은 키로 다시 보내도 자료와 작업은 하나다
		assertThat(owner.request("POST", registerPath, body, 201, key)).isEqualTo(created);
		assertThat(owner.post(registerPath, body, 409).get("error").get("code").asString()).isEqualTo("UPLOAD_ALREADY_USED");
		String id = created.get("material").get("id").asString();
		String jobId = created.get("job").get("id").asString();
		assertThat(created.get("material").get("status").asString()).isEqualTo("processing");
		assertThat(created.get("material").get("title").asString()).isEqualTo("교내 일회용품 조사.pdf");
		assertThat(created.get("job").get("status").asString()).isEqualTo("queued");
		assertThat(created.get("job").get("type").asString()).isEqualTo("material_analysis");
		assertThat(count("SELECT COUNT(*) FROM materials WHERE project_id = UUID_TO_BIN(?)", projectId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM jobs WHERE project_id = UUID_TO_BIN(?)", projectId)).isEqualTo(1);
		// 분석 전에는 원문을 열 수 없다
		owner.request("GET", "/materials/" + id + "/source", null, 409);
		// 확정한 원본은 같은 전송 주소로 다시 보내도 바뀌지 않는다
		assertThat(put(owner, ticket, pdf(1))).isEqualTo(409);

		runJobs();
		JsonNode job = owner.get("/jobs/" + jobId);
		assertThat(job.get("status").asString()).isEqualTo("succeeded");
		JsonNode material = owner.get("/materials/" + id);
		assertMatches(S + "Material", material);
		assertThat(material.get("status").asString()).isEqualTo("ready");
		assertThat(material.get("pageCount").asInt()).isEqualTo(actualPages);
		assertThat(material.get("source").get("fileName").asString()).isEqualTo("교내 일회용품 조사.pdf");
		assertThat(material.get("source").get("sizeBytes").asInt()).isEqualTo(bytes.length);
		assertThat(material.get("createdBy").get("name").asString()).isEqualTo("PDF 소유자");
		JsonNode revision = material.get("currentRevision");
		assertThat(revision.get("sourceVersion").asInt()).isEqualTo(1);
		assertThat(revision.get("summary").isNull()).isTrue();
		// 발췌는 실제 파일에서 뽑은 한글 본문이고 쪽 번호가 실제 범위 안에 있다
		String excerpt = revision.get("excerpt").get("text").asString();
		assertThat(excerpt).matches("(?s).*[가-힣]{2,}.*");
		assertThat(revision.get("excerpt").get("page").asInt()).isBetween(1, actualPages);
		assertThat(job.get("resultRef").get("revisionId").asString()).isEqualTo(revision.get("id").asString());
		assertThat(count("SELECT COUNT(*) FROM material_chunks WHERE revision_id = UUID_TO_BIN(?)",
				revision.get("id").asString())).isPositive();
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").get(0).get("pageCount").asInt())
			.isEqualTo(actualPages);

		// 원문 주소로 받은 파일은 올린 파일과 같고 쪽수가 실제와 같다
		JsonNode source = owner.get("/materials/" + id + "/source");
		assertMatches(S + "MaterialSource", source);
		assertThat(source.get("file").get("pageCount").asInt()).isEqualTo(actualPages);
		String url = source.get("file").get("url").asString();
		HttpResponse<byte[]> file = owner.raw("GET", url, null, Map.of());
		assertThat(file.statusCode()).isEqualTo(200);
		assertThat(file.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/pdf");
		assertThat(sha256(file.body())).isEqualTo(sha256(bytes));
		HttpResponse<byte[]> range = owner.raw("GET", url, null, Map.of("Range", "bytes=0-4"));
		assertThat(range.statusCode()).isEqualTo(206);
		assertThat(new String(range.body(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
		// 새로고침한 것처럼 다시 조회해도 같은 자료다
		assertThat(owner.get("/materials/" + id).get("currentRevision")).isEqualTo(revision);
	}

	@Test
	void everyPageIsExtractedWithItsPageNumber() throws Exception {
		Browser owner = signup("쪽수 소유자");
		String projectId = createProject(owner, "쪽수 " + UUID.randomUUID());
		String id = register(owner, projectId, pdf(3), "three-pages.pdf").get("material").get("id").asString();
		runJobs();
		JsonNode material = owner.get("/materials/" + id);
		assertThat(material.get("pageCount").asInt()).isEqualTo(3);
		assertThat(material.get("currentRevision").get("excerpt").get("page").asInt()).isEqualTo(1);
		List<String> chunks = jdbc.sql("""
				SELECT CONCAT(c.page_start, ':', c.text) FROM material_chunks c
				JOIN materials m ON m.current_revision_id = c.revision_id
				WHERE m.id = UUID_TO_BIN(?) ORDER BY c.ordinal
				""").param(id).query(String.class).list();
		assertThat(chunks).hasSize(3);
		assertThat(chunks.get(2)).startsWith("3:Survey page 3");
	}

	@Test
	void uploadLimitsAndTamperedFilesAreRejected() throws Exception {
		Browser owner = signup("검사 소유자");
		String projectId = createProject(owner, "파일 검사 " + UUID.randomUUID());
		String prepare = "/projects/" + projectId + "/uploads";
		String register = "/projects/" + projectId + "/materials/file";
		byte[] bytes = pdf(1);

		Map<String, Object> wrongType = Map.of("kind", "material_pdf", "fileName", "사진.png", "mimeType", "image/png",
				"sizeBytes", 10, "checksumSha256", sha256(bytes));
		assertThat(owner.request("POST", prepare, wrongType, 415).get("error").get("code").asString())
			.isEqualTo("UPLOAD_TYPE_NOT_ALLOWED");
		Map<String, Object> tooLarge = Map.of("kind", "material_pdf", "fileName", "큰 파일.pdf", "mimeType",
				"application/pdf", "sizeBytes", 20_971_521, "checksumSha256", sha256(bytes));
		assertThat(owner.request("POST", prepare, tooLarge, 413).get("error").get("code").asString())
			.isEqualTo("UPLOAD_TOO_LARGE");
		Map<String, Object> badChecksum = Map.of("kind", "material_pdf", "fileName", "a.pdf", "mimeType",
				"application/pdf", "sizeBytes", 10, "checksumSha256", "ABC");
		owner.request("POST", prepare, badChecksum, 422);

		// 준비한 것과 다른 파일을 보내면 등록을 거절하고 그 업로드는 다시 쓸 수 없다
		JsonNode swapped = prepare(owner, projectId, bytes, "바꿔치기.pdf");
		byte[] other = bytes.clone();
		other[other.length - 2] ^= 1;
		assertThat(put(owner, swapped, other)).isEqualTo(204);
		String key = UUID.randomUUID().toString();
		Map<String, Object> swappedBody = Map.of("uploadId", swapped.get("uploadId").asString());
		JsonNode rejected = owner.request("POST", register, swappedBody, 422, key);
		assertThat(rejected.get("error").get("code").asString()).isEqualTo("UPLOAD_REJECTED");
		assertThat(rejected.get("error").get("fieldErrors").get(0).get("code").asString()).isEqualTo("CHECKSUM_MISMATCH");
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ErrorResponse", json.writeValueAsString(rejected)))
			.isEmpty();
		assertThat(owner.request("POST", register, swappedBody, 422, key).get("error").get("fieldErrors").get(0).get("code")
			.asString()).isEqualTo("CHECKSUM_MISMATCH");
		owner.post(register, swappedBody, 422);
		assertThat(put(owner, swapped, bytes)).isEqualTo(422);

		// 덜 보낸 파일
		JsonNode partial = prepare(owner, projectId, bytes, "덜 보냄.pdf");
		assertThat(put(owner, partial, java.util.Arrays.copyOf(bytes, bytes.length - 10))).isEqualTo(204);
		assertThat(owner.post(register, Map.of("uploadId", partial.get("uploadId").asString()), 422).get("error")
			.get("fieldErrors").get(0).get("code").asString()).isEqualTo("SIZE_MISMATCH");
		// 준비한 크기보다 큰 파일은 받지 않는다
		JsonNode oversize = prepare(owner, projectId, bytes, "더 보냄.pdf");
		assertThat(put(owner, oversize, java.util.Arrays.copyOf(bytes, bytes.length + 10))).isEqualTo(413);
		// 형식을 속인 파일
		byte[] fake = "PDF인 척하는 글 파일".getBytes(StandardCharsets.UTF_8);
		JsonNode disguised = prepare(owner, projectId, fake, "위장.pdf");
		assertThat(put(owner, disguised, fake)).isEqualTo(204);
		assertThat(owner.post(register, Map.of("uploadId", disguised.get("uploadId").asString()), 422).get("error")
			.get("fieldErrors").get(0).get("code").asString()).isEqualTo("NOT_PDF");

		// 만료된 준비는 전송과 등록을 모두 거절한다
		JsonNode expired = prepare(owner, projectId, bytes, "만료.pdf");
		jdbc.sql("UPDATE uploads SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = UUID_TO_BIN(?)")
			.param(expired.get("uploadId").asString())
			.update();
		assertThat(put(owner, expired, bytes)).isEqualTo(410);
		owner.post(register, Map.of("uploadId", expired.get("uploadId").asString()), 410);
		assertThat(owner.raw("PUT", "/v1/storage/uploads/invalid-token", bytes, Map.of("Content-Type", "application/pdf"))
			.statusCode()).isEqualTo(403);

		// 거절된 파일로는 자료와 확정 파일이 만들어지지 않는다
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").isEmpty()).isTrue();
		assertThat(count("SELECT COUNT(*) FROM file_objects WHERE project_id = UUID_TO_BIN(?)", projectId)).isZero();
	}

	@Test
	void failedAnalysisIsRetriedWithoutUploadingAgain() throws Exception {
		Browser owner = signup("재시도 소유자");
		String projectId = createProject(owner, "분석 재시도 " + UUID.randomUUID());
		JsonNode created = register(owner, projectId, pdf(2), "재시도.pdf");
		String id = created.get("material").get("id").asString();
		// 보관소를 읽지 못하는 상황을 만든다
		Path stored = storage.path(jdbc.sql("""
				SELECT f.bucket_key FROM file_objects f JOIN materials m ON m.source_file_id = f.id
				WHERE m.id = UUID_TO_BIN(?)
				""").param(id).query(String.class).single());
		Path moved = stored.resolveSibling(stored.getFileName() + ".moved");
		Files.move(stored, moved);

		runJobs();
		JsonNode failedJob = owner.get("/jobs/" + created.get("job").get("id").asString());
		assertThat(failedJob.get("status").asString()).isEqualTo("failed");
		assertThat(failedJob.get("attempt").asInt()).isEqualTo(3);
		assertThat(failedJob.get("error").get("code").asString()).isEqualTo("ANALYSIS_FAILED");
		assertThat(failedJob.get("error").get("retryable").asBoolean()).isTrue();
		JsonNode failed = owner.get("/materials/" + id);
		assertMatches(S + "Material", failed);
		assertThat(failed.get("status").asString()).isEqualTo("failed");
		// 실패해도 작성자와 출처는 남는다
		assertThat(failed.get("source").get("fileName").asString()).isEqualTo("재시도.pdf");
		assertThat(failed.get("createdBy").get("name").asString()).isEqualTo("재시도 소유자");
		owner.request("GET", "/materials/" + id + "/source", null, 409);

		Files.move(moved, stored);
		String key = UUID.randomUUID().toString();
		JsonNode retried = owner.request("POST", "/materials/" + id + "/reprocess", null, 201, key);
		assertMatches(S + "MaterialCreated", retried);
		assertThat(retried.get("material").get("status").asString()).isEqualTo("processing");
		assertThat(retried.get("job").get("id").asString()).isNotEqualTo(failedJob.get("id").asString());
		// 중복 클릭과 응답 유실 뒤 재시도로 작업이 늘지 않는다
		assertThat(owner.request("POST", "/materials/" + id + "/reprocess", null, 201, key)).isEqualTo(retried);
		owner.post("/materials/" + id + "/reprocess", null, 409);
		owner.request("POST", "/materials/" + id + "/reprocess", null, 400, null);

		runJobs();
		JsonNode ready = owner.get("/materials/" + id);
		assertThat(ready.get("status").asString()).isEqualTo("ready");
		assertThat(ready.get("pageCount").asInt()).isEqualTo(2);
		assertThat(ready.get("job").get("id").asString()).isEqualTo(retried.get("job").get("id").asString());
		owner.post("/materials/" + id + "/reprocess", null, 409);
		// 자료, 확정 파일, 업로드는 하나이고 작업만 둘이다
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").size()).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM file_objects WHERE project_id = UUID_TO_BIN(?)", projectId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM uploads WHERE project_id = UUID_TO_BIN(?)", projectId)).isEqualTo(1);
		assertThat(owner.get("/projects/" + projectId + "/jobs?targetType=material&targetId=" + id).get("items").size())
			.isEqualTo(2);
	}

	@Test
	void unreadablePdfFailsOnceAndCancelOrDeleteStopsTheAnalysis() throws Exception {
		Browser owner = signup("실패 소유자");
		Browser member = signup("실패 팀원");
		String projectId = createProject(owner, "분석 실패 " + UUID.randomUUID());
		join(owner, member, projectId);

		// 시작 표시만 있고 내용이 깨진 파일은 다시 시도하지 않고 끝난다
		byte[] broken = "%PDF-1.7\n깨진 내용".getBytes(StandardCharsets.UTF_8);
		JsonNode unreadable = register(member, projectId, broken, "깨진 파일.pdf");
		runJobs();
		JsonNode job = member.get("/jobs/" + unreadable.get("job").get("id").asString());
		assertThat(job.get("status").asString()).isEqualTo("failed");
		assertThat(job.get("attempt").asInt()).isEqualTo(1);
		assertThat(job.get("error").get("code").asString()).isEqualTo("MATERIAL_UNREADABLE");
		assertThat(member.get("/materials/" + unreadable.get("material").get("id").asString()).get("status").asString())
			.isEqualTo("failed");

		// 대기 중인 분석을 취소하면 자료는 실패로 남고 다시 시도할 수 있다
		JsonNode queued = register(member, projectId, pdf(1), "취소할 파일.pdf");
		String queuedId = queued.get("material").get("id").asString();
		assertThat(member.request("POST", "/jobs/" + queued.get("job").get("id").asString() + "/cancel", null, 200)
			.get("status").asString()).isEqualTo("canceled");
		assertThat(member.get("/materials/" + queuedId).get("status").asString()).isEqualTo("failed");
		member.post("/materials/" + queuedId + "/reprocess", null, 201);
		runJobs();
		assertThat(member.get("/materials/" + queuedId).get("status").asString()).isEqualTo("ready");

		// 분석을 실행하는 중에 자료를 지우면 결과를 반영하지 않는다
		JsonNode running = register(member, projectId, pdf(1), "지울 파일.pdf");
		String runningId = running.get("material").get("id").asString();
		var claim = queue.claim("test").orElseThrow();
		member.request("DELETE", "/materials/" + runningId, Map.of("expectedVersion", 1), 200);
		queue.run(claim);
		assertThat(owner.get("/jobs/" + running.get("job").get("id").asString()).get("status").asString())
			.isEqualTo("canceled");
		assertThat(count("SELECT COUNT(*) FROM material_revisions WHERE material_id = UUID_TO_BIN(?)", runningId)).isZero();
		owner.request("GET", "/materials/" + runningId, null, 410);
	}

	@Test
	void sourceFileIsOnlyServedToCurrentMembersOfItsProject() throws Exception {
		Browser owner = signup("원문 소유자");
		Browser member = signup("원문 팀원");
		Browser stranger = signup("원문 외부인");
		String projectId = createProject(owner, "원문 권한 " + UUID.randomUUID());
		String otherProjectId = createProject(stranger, "다른 프로젝트 " + UUID.randomUUID());
		join(owner, member, projectId);
		byte[] bytes = pdf(1);

		// 다른 프로젝트에는 업로드를 준비할 수 없고 남의 전송 주소로 보낼 수 없다
		stranger.request("POST", "/projects/" + projectId + "/uploads", uploadBody(bytes, "무단.pdf"), 404);
		JsonNode ticket = prepare(member, projectId, bytes, "팀 자료.pdf");
		assertThat(put(stranger, ticket, bytes)).isEqualTo(404);
		assertThat(put(new Browser(), ticket, bytes)).isEqualTo(401);
		assertThat(put(member, ticket, bytes)).isEqualTo(204);
		// 준비한 사람과 프로젝트가 같아야 등록된다
		owner.post("/projects/" + projectId + "/materials/file", Map.of("uploadId", ticket.get("uploadId").asString()), 404);
		stranger.post("/projects/" + otherProjectId + "/materials/file", Map.of("uploadId", ticket.get("uploadId").asString()),
				404);
		String id = member.post("/projects/" + projectId + "/materials/file",
				Map.of("uploadId", ticket.get("uploadId").asString()), 201).get("material").get("id").asString();
		runJobs();

		String url = member.get("/materials/" + id + "/source").get("file").get("url").asString();
		assertThat(member.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(200);
		assertThat(owner.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(200);
		// 주소를 알아도 다른 프로젝트 사용자와 로그인하지 않은 사용자는 받을 수 없다
		assertThat(stranger.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(404);
		assertThat(new Browser().raw("GET", url, null, Map.of()).statusCode()).isEqualTo(401);
		stranger.request("GET", "/materials/" + id + "/source", null, 404);
		stranger.post("/materials/" + id + "/reprocess", null, 404);

		// 만료됐거나 고친 주소는 거절한다
		String fileId = jdbc.sql("SELECT BIN_TO_UUID(source_file_id) FROM materials WHERE id = UUID_TO_BIN(?)")
			.param(id)
			.query(String.class)
			.single();
		String expired = "/v1/storage/files/" + storage.sign("file", UUID.fromString(fileId), Instant.now().minusSeconds(1));
		HttpResponse<byte[]> gone = member.raw("GET", expired, null, Map.of());
		assertThat(gone.statusCode()).isEqualTo(403);
		assertThat(json.readTree(gone.body()).get("error").get("code").asString()).isEqualTo("FILE_URL_EXPIRED");
		assertThat(member.raw("GET", url.substring(0, url.length() - 2) + "xx", null, Map.of()).statusCode()).isEqualTo(403);
		// 전송용 서명을 원문 주소에 쓸 수 없다
		String wrongPurpose = "/v1/storage/files/"
				+ storage.sign("upload", UUID.fromString(fileId), Instant.now().plusSeconds(60));
		assertThat(member.raw("GET", wrongPurpose, null, Map.of()).statusCode()).isEqualTo(403);

		// 제거된 팀원은 아직 만료되지 않은 주소로도 받을 수 없다
		removeMember(owner, projectId, member.userId);
		assertThat(member.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(403);
		member.request("GET", "/materials/" + id + "/source", null, 403);
		member.post("/materials/" + id + "/reprocess", null, 403);
		assertThat(owner.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(200);

		// 자료를 지우면 남은 주소로도 받을 수 없다
		owner.request("DELETE", "/materials/" + id, Map.of("expectedVersion", 2), 200);
		assertThat(owner.raw("GET", url, null, Map.of()).statusCode()).isEqualTo(410);
		owner.request("GET", "/materials/" + id + "/source", null, 410);
	}

}
