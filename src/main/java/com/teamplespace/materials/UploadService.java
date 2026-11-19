package com.teamplespace.materials;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.workspace.application.ProjectAccess;

import tools.jackson.databind.ObjectMapper;

// PDF 업로드 준비, 파일 전송, 검사 뒤 자료 등록, 원문 파일 내려 주기
@Service
public class UploadService {

	public record UploadRequest(String kind, String fileName, String mimeType, Long sizeBytes, String checksumSha256) {
	}

	public record FileRequest(UUID uploadId, String title, String description) {
	}

	// 내려 줄 원문 파일
	public record StoredFile(Path path, String mimeType, String fileName) {
	}

	private record Upload(UUID id, UUID userId, String name, String mime, long size, byte[] checksum, String stagingKey,
			String state, LocalDateTime expiresAt) {
	}

	public static final String PDF_KIND = "material_pdf";

	public static final String PDF_MIME = "application/pdf";

	private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final Clock clock;

	private final ProjectAccess access;

	private final IdempotencyStore idempotency;

	private final FileStorage storage;

	private final MaterialService materials;

	private final TransactionTemplate transaction;

	private final long pdfMaxBytes;

	private final Duration uploadTtl;

	public UploadService(JdbcClient jdbc, ObjectMapper json, Clock clock, ProjectAccess access,
			IdempotencyStore idempotency, FileStorage storage, MaterialService materials,
			TransactionTemplate transaction, @Value("${teamplespace.materials.pdf-max-bytes:20971520}") long pdfMaxBytes,
			@Value("${teamplespace.storage.upload-url-ttl:10m}") Duration uploadTtl) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.access = access;
		this.idempotency = idempotency;
		this.storage = storage;
		this.materials = materials;
		this.transaction = transaction;
		this.pdfMaxBytes = pdfMaxBytes;
		this.uploadTtl = uploadTtl;
	}

	// 형식과 크기를 확인하고 임시 위치로 보낼 전송 주소를 발급한다
	@Transactional
	public Map<String, Object> prepare(UUID actor, UUID projectId, UploadRequest input) {
		access.requireMember(projectId, actor);
		if (!PDF_KIND.equals(input.kind())) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "kind", "NOT_ALLOWED", "올릴 수 없는 종류예요.");
		}
		String name = input.fileName() == null ? "" : input.fileName().replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).strip();
		if (name.isEmpty() || name.length() > MaterialService.TITLE_MAX) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "fileName", "INVALID", "파일 이름을 확인해 주세요.");
		}
		if (!PDF_MIME.equals(input.mimeType())) {
			throw ApiException.field(ErrorCode.UPLOAD_TYPE_NOT_ALLOWED, "file", "TYPE_NOT_ALLOWED", "PDF 파일만 올릴 수 있어요.");
		}
		if (input.sizeBytes() == null || input.sizeBytes() < 1) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "sizeBytes", "INVALID", "빈 파일은 올릴 수 없어요.");
		}
		if (input.sizeBytes() > pdfMaxBytes) {
			throw ApiException.field(ErrorCode.UPLOAD_TOO_LARGE, "file", "TOO_LARGE",
					(pdfMaxBytes / 1024 / 1024) + "MB까지 올릴 수 있어요.");
		}
		if (input.checksumSha256() == null || !input.checksumSha256().matches("[0-9a-f]{64}")) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "checksumSha256", "INVALID", "파일 확인 값을 다시 계산해 주세요.");
		}
		UUID id = UUID.randomUUID();
		Instant expiresAt = clock.instant().plus(uploadTtl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		jdbc.sql("""
				INSERT INTO uploads (id, project_id, user_id, kind, original_name, claimed_mime, size_bytes, checksum,
				                     staging_key, state, expires_at)
				VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, 'prepared', ?)
				""")
			.params(id.toString(), projectId.toString(), actor.toString(), PDF_KIND, name, PDF_MIME, input.sizeBytes(),
					HexFormat.of().parseHex(input.checksumSha256()), "staging/" + id,
					LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
			.update();
		Map<String, Object> ticket = new LinkedHashMap<>();
		ticket.put("uploadId", id.toString());
		ticket.put("uploadUrl", "/v1/storage/uploads/" + storage.sign("upload", id, expiresAt));
		ticket.put("uploadMethod", "PUT");
		ticket.put("uploadHeaders", Map.of("Content-Type", PDF_MIME));
		ticket.put("expiresAt", expiresAt);
		return ticket;
	}

	// 전송 주소로 온 파일을 임시 위치에 받는다. 등록 전에는 같은 주소로 다시 보낼 수 있다
	public void receive(UUID actor, String token, long declaredLength, InputStream body) {
		UUID id = storage.verify("upload", token, clock.instant())
			.orElseThrow(() -> new ApiException(ErrorCode.FILE_URL_EXPIRED));
		Upload upload = find(id, null, false);
		if (upload == null || !upload.userId().equals(actor)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		requireOpen(upload);
		if (declaredLength > upload.size() || storage.receive(upload.stagingKey(), body, upload.size()) < 0) {
			throw ApiException.field(ErrorCode.UPLOAD_TOO_LARGE, "file", "SIZE_MISMATCH", "준비한 크기보다 큰 파일이에요.");
		}
	}

	// 전송한 파일을 검사해 바뀌지 않는 원본으로 확정하고 자료와 분석 작업을 만든다
	public Map<String, Object> createFile(UUID actor, UUID projectId, FileRequest input, String keyValue) {
		Object outcome = transaction.execute(status -> {
			access.lock(projectId);
			access.requireMember(projectId, actor);
			if (input.uploadId() == null) {
				throw ApiException.field(ErrorCode.VALIDATION_FAILED, "uploadId", "REQUIRED", "업로드를 먼저 준비해 주세요.");
			}
			String title = materials.title(input.title(), false);
			String description = materials.text(input.description(), "description", MaterialService.DESCRIPTION_MAX, false)
				.strip();
			var key = materials.key(actor, "/v1/projects/" + projectId + "/materials/file", keyValue);
			var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(json.valueToTree(input)));
			if (stored.isPresent()) {
				// 거절도 처음 결과 그대로 다시 알린다
				return stored.get().status() == 201 ? materials.read(stored.get().body())
						: rejected(materials.read(stored.get().body()).get("reason").toString());
			}
			Upload upload = find(input.uploadId(), projectId, true);
			if (upload == null || !upload.userId().equals(actor)) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			requireOpen(upload);
			if (!storage.exists(upload.stagingKey())) {
				throw new ApiException(ErrorCode.UPLOAD_INCOMPLETE);
			}
			UUID fileId = UUID.randomUUID();
			String finalKey = "files/" + projectId + "/" + fileId + ".pdf";
			storage.promote(upload.stagingKey(), finalKey);
			String reason = inspect(upload, finalKey);
			if (reason != null) {
				storage.delete(finalKey);
				storage.delete(upload.stagingKey());
				jdbc.sql("UPDATE uploads SET state = 'rejected' WHERE id = UUID_TO_BIN(?)")
					.param(upload.id().toString())
					.update();
				idempotency.complete(key, false, 422, json.writeValueAsString(Map.of("reason", reason)), upload.id());
				return rejected(reason);
			}
			jdbc.sql("""
					INSERT INTO file_objects (id, project_id, purpose, bucket_key, checksum, mime_type, size_bytes, state,
					                          verified_at)
					VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, 'ready', UTC_TIMESTAMP(6))
					""")
				.params(fileId.toString(), projectId.toString(), PDF_KIND, finalKey, upload.checksum(), PDF_MIME,
						upload.size())
				.update();
			jdbc.sql("""
					UPDATE uploads SET state = 'consumed', consumed_at = UTC_TIMESTAMP(6), file_object_id = UUID_TO_BIN(?)
					WHERE id = UUID_TO_BIN(?)
					""").params(fileId.toString(), upload.id().toString()).update();
			storage.delete(upload.stagingKey());
			UUID materialId = materials.insert(projectId, actor, "file", title.isEmpty() ? upload.name() : title,
					description.isEmpty() ? null : description, "processing", null);
			jdbc.sql("UPDATE materials SET source_file_id = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)")
				.params(fileId.toString(), materialId.toString())
				.update();
			Map<String, Object> result = materials.startAnalysis(actor, materialId);
			idempotency.complete(key, true, 201, json.writeValueAsString(result), materialId);
			return result;
		});
		if (outcome instanceof ApiException rejection) {
			throw rejection;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) outcome;
		return result;
	}

	// 원문 주소로 온 요청. 주소가 유효해도 지금 팀원인지와 자료가 남아 있는지 다시 확인한다
	@Transactional(readOnly = true)
	public StoredFile file(UUID actor, String token) {
		UUID id = storage.verify("file", token, clock.instant())
			.orElseThrow(() -> new ApiException(ErrorCode.FILE_URL_EXPIRED));
		Map<String, Object> row = jdbc.sql("""
				SELECT BIN_TO_UUID(f.project_id) AS project_id, f.bucket_key, f.mime_type, u.original_name,
				       (SELECT COUNT(*) FROM materials m WHERE m.source_file_id = f.id AND m.deleted_at IS NULL) AS live
				FROM file_objects f JOIN uploads u ON u.file_object_id = f.id
				WHERE f.id = UUID_TO_BIN(?) AND f.state = 'ready'
				""")
			.param(id.toString())
			.query()
			.listOfRows()
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		access.requireMember(UUID.fromString((String) row.get("project_id")), actor);
		if (((Number) row.get("live")).intValue() == 0) {
			throw new ApiException(ErrorCode.MATERIAL_DELETED);
		}
		return new StoredFile(storage.path((String) row.get("bucket_key")), (String) row.get("mime_type"),
				(String) row.get("original_name"));
	}

	// 등록하지 않고 만료된 업로드의 임시 파일을 하루 뒤에 지운다
	@Scheduled(fixedDelayString = "${teamplespace.storage.cleanup-interval:1h}")
	public void cleanUpExpired() {
		List<String> expired = jdbc.sql("""
				SELECT BIN_TO_UUID(id) FROM uploads WHERE state = 'prepared' AND expires_at < :before LIMIT 200
				""").param("before", LocalDateTime.now(clock).minusDays(1)).query(String.class).list();
		for (String id : expired) {
			storage.delete("staging/" + id);
			jdbc.sql("UPDATE uploads SET state = 'expired' WHERE id = UUID_TO_BIN(?) AND state = 'prepared'")
				.param(id)
				.update();
		}
	}

	// 실제 크기, 체크섬, PDF 시작 표시를 확인한다. 통과하면 null
	private String inspect(Upload upload, String finalKey) {
		if (storage.size(finalKey) != upload.size()) {
			return "SIZE_MISMATCH";
		}
		if (!Arrays.equals(storage.sha256(finalKey), upload.checksum())) {
			return "CHECKSUM_MISMATCH";
		}
		// PDF는 앞쪽 1024바이트 안에 시작 표시가 있다
		byte[] head = storage.head(finalKey, 1024);
		for (int start = 0; start + PDF_SIGNATURE.length <= head.length; start++) {
			if (Arrays.equals(head, start, start + PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length)) {
				return null;
			}
		}
		return "NOT_PDF";
	}

	private static ApiException rejected(String reason) {
		String message = switch (reason) {
			case "SIZE_MISMATCH" -> "전송한 파일의 크기가 처음 선택한 파일과 달라요.";
			case "CHECKSUM_MISMATCH" -> "전송한 파일이 처음 선택한 파일과 달라요.";
			default -> "PDF 파일이 아니에요.";
		};
		return ApiException.field(ErrorCode.UPLOAD_REJECTED, "file", reason, message);
	}

	private void requireOpen(Upload upload) {
		if (upload.state().equals("consumed")) {
			throw new ApiException(ErrorCode.UPLOAD_ALREADY_USED);
		}
		if (upload.state().equals("rejected")) {
			throw rejected("NOT_PDF");
		}
		if (upload.state().equals("expired") || !upload.expiresAt().isAfter(LocalDateTime.now(clock))) {
			throw new ApiException(ErrorCode.UPLOAD_EXPIRED);
		}
	}

	private Upload find(UUID id, UUID projectId, boolean lock) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id, BIN_TO_UUID(user_id) AS user_id, original_name, claimed_mime, size_bytes,
				       checksum, staging_key, state, expires_at
				FROM uploads
				WHERE id = UUID_TO_BIN(:id) AND (:project IS NULL OR project_id = UUID_TO_BIN(:project))
				""" + (lock ? " FOR UPDATE" : ""))
			.param("id", id.toString())
			.param("project", projectId == null ? null : projectId.toString())
			.query((rs, n) -> new Upload(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("user_id")),
					rs.getString("original_name"), rs.getString("claimed_mime"), rs.getLong("size_bytes"),
					rs.getBytes("checksum"), rs.getString("staging_key"), rs.getString("state"),
					rs.getObject("expires_at", LocalDateTime.class)))
			.optional()
			.orElse(null);
	}

}
