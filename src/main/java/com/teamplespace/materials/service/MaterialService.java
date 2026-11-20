package com.teamplespace.materials.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.materials.infra.FileStorage;
import com.teamplespace.materials.infra.LinkFetcher;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 자료 목록과 상세, 이름과 본문 변경, 삭제, 마크다운 자료, 팀 메모. contracts/stage-2.md를 따른다
@Service
public class MaterialService {

	public record MemoRequest(String title, String markdown) {
	}

	public record LinkRequest(String url, String title, String description) {
	}

	public record PatchRequest(Long expectedVersion, String title, String description, String markdown) {
	}

	public record DeleteRequest(Long expectedVersion) {
	}

	public record NoteRequest(Long expectedVersion, String markdown) {
	}

	// 잠근 자료의 현재 상태
	record Row(UUID id, UUID projectId, UUID createdBy, String kind, String title, String state, UUID currentRevisionId,
			long version, boolean deleted) {
	}

	static final int TITLE_MAX = 255;

	static final int DESCRIPTION_MAX = 2000;

	static final int MARKDOWN_MAX = 100_000;

	static final int NOTE_MAX = 20_000;

	private static final Set<String> KINDS = Set.of("file", "link", "memo");

	private static final Set<String> STATES = Set.of("processing", "ready", "failed");

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final ProjectAccess access;

	private final ProjectEvents events;

	private final IdempotencyStore idempotency;

	private final JobQueue jobs;

	private final FileStorage storage;

	private final Clock clock;

	private final Duration sourceUrlTtl;

	public MaterialService(JdbcClient jdbc, ObjectMapper json, ProjectAccess access, ProjectEvents events,
			IdempotencyStore idempotency, JobQueue jobs, FileStorage storage, Clock clock,
			@Value("${teamplespace.storage.source-url-ttl:5m}") Duration sourceUrlTtl) {
		this.storage = storage;
		this.clock = clock;
		this.sourceUrlTtl = sourceUrlTtl;
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.events = events;
		this.idempotency = idempotency;
		this.jobs = jobs;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(UUID actor, UUID projectId, String query, String kind, String status, String cursor,
			Integer limit) {
		access.requireMember(projectId, actor);
		int size = limit == null ? 30 : limit;
		String search = query == null ? "" : query.strip();
		if (size < 1 || size > 100 || search.length() > TITLE_MAX || (kind != null && !KINDS.contains(kind))
				|| (status != null && !STATES.contains(status))) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		StringBuilder sql = new StringBuilder("""
				SELECT BIN_TO_UUID(m.id) AS id, BIN_TO_UUID(m.project_id) AS project_id, m.kind, m.title, m.state, m.version,
				       r.page_count, BIN_TO_UUID(m.created_by) AS created_by, u.display_name AS creator_name,
				       m.created_at, m.updated_at
				FROM materials m
				JOIN users u ON u.id = m.created_by
				LEFT JOIN material_revisions r ON r.id = m.current_revision_id
				WHERE m.project_id = UUID_TO_BIN(:project) AND m.deleted_at IS NULL
				  AND (:search = '' OR LOCATE(:search, m.title) > 0)
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("project", projectId.toString());
		params.put("search", search);
		if (kind != null) {
			sql.append(" AND m.kind = :kind");
			params.put("kind", kind);
		}
		if (status != null) {
			sql.append(" AND m.state = :status");
			params.put("status", status);
		}
		if (cursor != null && !cursor.isEmpty()) {
			// 커서는 앞 페이지 마지막 자료의 식별자다
			String last = cursorId(cursor);
			LocalDateTime after = jdbc
				.sql("SELECT created_at FROM materials WHERE id = UUID_TO_BIN(:id) AND project_id = UUID_TO_BIN(:project)")
				.param("id", last)
				.param("project", projectId.toString())
				.query(LocalDateTime.class)
				.optional()
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_CURSOR));
			sql.append(" AND (m.created_at < :after OR (m.created_at = :after AND m.id < UUID_TO_BIN(:cursor)))");
			params.put("after", after);
			params.put("cursor", last);
		}
		sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT :limit");
		params.put("limit", size + 1);
		List<Map<String, Object>> rows = jdbc.sql(sql.toString()).params(params).query((rs, row) -> summary(rs)).list();

		Map<String, Object> counts = new LinkedHashMap<>();
		KINDS.forEach(name -> counts.put(name, 0));
		jdbc.sql("""
				SELECT kind, COUNT(*) AS total FROM materials
				WHERE project_id = UUID_TO_BIN(:project) AND deleted_at IS NULL AND (:search = '' OR LOCATE(:search, title) > 0)
				GROUP BY kind
				""")
			.param("project", projectId.toString())
			.param("search", search)
			.query((rs, row) -> counts.put(rs.getString("kind"), rs.getInt("total")))
			.list();
		Map<String, Object> orderedCounts = new LinkedHashMap<>();
		orderedCounts.put("all", counts.values().stream().mapToInt(value -> (int) value).sum());
		for (String name : List.of("file", "link", "memo")) {
			orderedCounts.put(name, counts.get(name));
		}

		Map<String, Object> page = new LinkedHashMap<>();
		page.put("items", new ArrayList<>(rows.subList(0, Math.min(size, rows.size()))));
		page.put("nextCursor", rows.size() > size ? rows.get(size - 1).get("id") : null);
		page.put("counts", orderedCounts);
		return page;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UUID actor, UUID materialId) {
		Row row = readable(actor, materialId);
		return view(actor, row.id());
	}

	@Transactional
	public Map<String, Object> createMemo(UUID actor, UUID projectId, MemoRequest input, String keyValue) {
		access.lock(projectId);
		access.requireMember(projectId, actor);
		String title = title(input.title(), true);
		String markdown = text(input.markdown(), "markdown", MARKDOWN_MAX, true);
		var key = key(actor, "/v1/projects/" + projectId + "/materials/memo", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(json.valueToTree(input)));
		if (stored.isPresent()) {
			return read(stored.get().body());
		}
		UUID id = insert(projectId, actor, "memo", title, null, "ready", null);
		addMemoRevision(new Row(id, projectId, actor, "memo", title, "ready", null, 1, false), actor, markdown);
		event(projectId, "material.updated", actor, id);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("material", view(actor, id));
		result.put("job", null);
		idempotency.complete(key, true, 201, json.writeValueAsString(result), id);
		return result;
	}

	// 주소 형식만 확인해 등록하고 수집은 분석 작업이 한다. 실패해도 주소와 설명은 자료에 남는다
	@Transactional
	public Map<String, Object> createLink(UUID actor, UUID projectId, LinkRequest input, String keyValue) {
		access.lock(projectId);
		access.requireMember(projectId, actor);
		String url = LinkFetcher.normalize(input.url());
		String title = title(input.title(), false);
		String description = text(input.description(), "description", DESCRIPTION_MAX, false).strip();
		var key = key(actor, "/v1/projects/" + projectId + "/materials/link", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(json.valueToTree(input)));
		if (stored.isPresent()) {
			return read(stored.get().body());
		}
		// 제목을 비우면 호스트를 임시 제목으로 쓴다
		UUID id = insert(projectId, actor, "link", title.isEmpty() ? java.net.URI.create(url).getHost() : title,
				description.isEmpty() ? null : description, "processing", url);
		Map<String, Object> result = startAnalysis(actor, id);
		idempotency.complete(key, true, 201, json.writeValueAsString(result), id);
		return result;
	}

	@Transactional
	public Map<String, Object> patch(UUID actor, UUID materialId, PatchRequest input) {
		Row row = writable(actor, materialId);
		expect(row, input.expectedVersion());
		if (input.title() == null && input.description() == null && input.markdown() == null) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		if (input.markdown() != null && !row.kind().equals("memo")) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "markdown", "NOT_ALLOWED", "마크다운 자료만 본문을 바꿀 수 있어요.");
		}
		if (input.title() != null) {
			jdbc.sql("UPDATE materials SET title = ? WHERE id = UUID_TO_BIN(?)")
				.params(title(input.title(), true), materialId.toString())
				.update();
		}
		if (input.description() != null) {
			String description = text(input.description(), "description", DESCRIPTION_MAX, false).strip();
			jdbc.sql("UPDATE materials SET description = ? WHERE id = UUID_TO_BIN(?)")
				.params(description.isEmpty() ? null : description, materialId.toString())
				.update();
		}
		if (input.markdown() != null) {
			String markdown = text(input.markdown(), "markdown", MARKDOWN_MAX, true);
			String current = jdbc.sql("SELECT markdown FROM material_revisions WHERE id = UUID_TO_BIN(?)")
				.param(row.currentRevisionId().toString())
				.query(String.class)
				.single();
			// 본문이 그대로면 개정을 만들지 않는다
			if (!markdown.equals(current)) {
				addMemoRevision(row, actor, markdown);
				jobs.cancelActive(MaterialSummary.TYPE, row.currentRevisionId(), actor);
			}
		}
		jdbc.sql("UPDATE materials SET version = version + 1 WHERE id = UUID_TO_BIN(?)")
			.param(materialId.toString())
			.update();
		event(row.projectId(), "material.updated", actor, materialId);
		return view(actor, materialId);
	}

	@Transactional
	public Map<String, Object> delete(UUID actor, UUID materialId, DeleteRequest input) {
		Row row = writable(actor, materialId);
		if (!actor.equals(row.createdBy()) && !access.isOwner(row.projectId(), actor)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		expect(row, input.expectedVersion());
		// 목록에서만 숨긴다. 개정과 본문, 팀 메모는 지우지 않는다
		jdbc.sql("""
				UPDATE materials SET state = 'deleted', deleted_at = UTC_TIMESTAMP(6), version = version + 1
				WHERE id = UUID_TO_BIN(?)
				""").param(materialId.toString()).update();
		jobs.cancelActive("material", materialId, actor);
		if (row.currentRevisionId() != null) {
			jobs.cancelActive(MaterialSummary.TYPE, row.currentRevisionId(), actor);
		}
		long eventId = event(row.projectId(), "material.deleted", actor, materialId);
		Map<String, Object> tombstone = new LinkedHashMap<>();
		tombstone.put("resourceType", "material");
		tombstone.put("id", materialId.toString());
		tombstone.put("version", row.version() + 1);
		tombstone.put("deletedAt", jdbc.sql("SELECT deleted_at FROM materials WHERE id = UUID_TO_BIN(?)")
			.param(materialId.toString())
			.query(LocalDateTime.class)
			.single()
			.toInstant(ZoneOffset.UTC));
		return Map.of("tombstone", tombstone, "eventId", Long.toString(eventId));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> source(UUID actor, UUID materialId, UUID revisionId) {
		Row row = readable(actor, materialId);
		if (!row.state().equals("ready") || row.currentRevisionId() == null) {
			throw new ApiException(ErrorCode.MATERIAL_STATE_CONFLICT);
		}
		UUID revision = revisionId == null ? row.currentRevisionId() : revisionId;
		return jdbc.sql("""
				SELECT BIN_TO_UUID(r.id) AS id, r.revision_no, r.markdown, r.page_count, r.source_url, r.source_title,
				       r.extracted_text,
				       BIN_TO_UUID(f.id) AS file_id, f.mime_type, f.size_bytes, up.original_name
				FROM material_revisions r
				LEFT JOIN file_objects f ON f.id = r.source_file_id
				LEFT JOIN uploads up ON up.file_object_id = f.id
				WHERE r.id = UUID_TO_BIN(:revision) AND r.material_id = UUID_TO_BIN(:material)
				""").param("revision", revision.toString()).param("material", materialId.toString()).query((rs, n) -> {
			Map<String, Object> source = new LinkedHashMap<>();
			source.put("materialId", materialId.toString());
			source.put("revisionId", rs.getString("id"));
			source.put("sourceVersion", rs.getLong("revision_no"));
			source.put("kind", row.kind());
			source.put("title", row.title());
			Map<String, Object> file = null;
			if (row.kind().equals("file")) {
				// 주소는 발급할 때마다 권한을 확인하고 짧게만 유효하다
				Instant expiresAt = clock.instant().plus(sourceUrlTtl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
				file = new LinkedHashMap<>();
				file.put("url", "/v1/storage/files/"
						+ storage.sign("file", UUID.fromString(rs.getString("file_id")), expiresAt));
				file.put("expiresAt", expiresAt);
				file.put("mimeType", rs.getString("mime_type"));
				file.put("fileName", rs.getString("original_name"));
				file.put("sizeBytes", rs.getLong("size_bytes"));
				file.put("pageCount", rs.getInt("page_count"));
			}
			source.put("file", file);
			source.put("markdown", row.kind().equals("memo") ? rs.getString("markdown") : null);
			Map<String, Object> link = null;
			if (row.kind().equals("link")) {
				link = new LinkedHashMap<>();
				link.put("url", rs.getString("source_url"));
				link.put("title", rs.getString("source_title"));
				link.put("text", rs.getString("extracted_text"));
			}
			source.put("link", link);
			return source;
		}).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	// 실패한 분석을 보관한 원본으로 다시 시작한다. 파일을 다시 올리지 않는다
	@Transactional
	public Map<String, Object> reprocess(UUID actor, UUID materialId, String keyValue) {
		Row row = writable(actor, materialId);
		var key = key(actor, "/v1/materials/" + materialId + "/reprocess", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256("reprocess"));
		if (stored.isPresent()) {
			return read(stored.get().body());
		}
		if (!row.state().equals("failed")) {
			throw new ApiException(ErrorCode.MATERIAL_STATE_CONFLICT);
		}
		jdbc.sql("UPDATE materials SET state = 'processing', version = version + 1 WHERE id = UUID_TO_BIN(?)")
			.param(materialId.toString())
			.update();
		Map<String, Object> result = startAnalysis(actor, materialId);
		idempotency.complete(key, true, 201, json.writeValueAsString(result), materialId);
		return result;
	}

	// 분석 작업을 등록하고 등록 응답을 만든다. 프로젝트 잠금을 잡은 트랜잭션 안에서 호출한다
	Map<String, Object> startAnalysis(UUID actor, UUID materialId) {
		Row row = find(materialId, false);
		ObjectNode input = json.createObjectNode();
		input.put("materialId", materialId.toString());
		input.putObject("inputVersions").put("materialVersion", row.version());
		jobs.register(row.projectId(), actor, MaterialAnalysis.TYPE, "material", materialId, input);
		event(row.projectId(), "material.updated", actor, materialId);
		Map<String, Object> material = view(actor, materialId);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("material", material);
		result.put("job", material.get("job"));
		return result;
	}

	// 분석 결과를 반영해 자료를 준비 완료로 바꾼다. 작업 완료 트랜잭션 안에서 호출한다
	void markReady(UUID materialId) {
		jdbc.sql("UPDATE materials SET state = 'ready', version = version + 1 WHERE id = UUID_TO_BIN(?)")
			.param(materialId.toString())
			.update();
		event(find(materialId, false).projectId(), "material.updated", null, materialId);
	}

	// 분석이 실패하거나 취소로 끝났을 때 아직 처리 중인 자료를 실패로 바꾼다
	void markFailed(UUID materialId) {
		Row row = find(materialId, true);
		if (row.deleted() || !row.state().equals("processing")) {
			return;
		}
		jdbc.sql("UPDATE materials SET state = 'failed', version = version + 1 WHERE id = UUID_TO_BIN(?)")
			.param(materialId.toString())
			.update();
		event(row.projectId(), "material.updated", null, materialId);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> note(UUID actor, UUID materialId) {
		readable(actor, materialId);
		return jdbc.sql("""
				SELECT n.markdown, n.version, BIN_TO_UUID(n.updated_by) AS updated_by, u.display_name, n.updated_at
				FROM material_notes n JOIN users u ON u.id = n.updated_by
				WHERE n.material_id = UUID_TO_BIN(?)
				""").param(materialId.toString()).query((rs, n) -> {
			Map<String, Object> note = new LinkedHashMap<>();
			note.put("materialId", materialId.toString());
			note.put("markdown", rs.getString("markdown"));
			note.put("version", rs.getLong("version"));
			note.put("updatedBy", rs.getString("updated_by"));
			note.put("updatedByName", rs.getString("display_name"));
			note.put("updatedAt", instant(rs, "updated_at"));
			return note;
		}).optional().orElseGet(() -> {
			// 아직 쓰지 않은 메모
			Map<String, Object> empty = new LinkedHashMap<>();
			empty.put("materialId", materialId.toString());
			empty.put("markdown", "");
			empty.put("version", 0);
			empty.put("updatedBy", null);
			empty.put("updatedByName", null);
			empty.put("updatedAt", null);
			return empty;
		});
	}

	@Transactional
	public Map<String, Object> saveNote(UUID actor, UUID materialId, NoteRequest input) {
		// 자료 행 잠금이 같은 메모의 저장을 한 줄로 세운다
		Row row = writable(actor, materialId);
		String markdown = text(input.markdown(), "markdown", NOTE_MAX, true);
		if (input.expectedVersion() == null || input.expectedVersion() < 0) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		long current = jdbc.sql("SELECT version FROM material_notes WHERE material_id = UUID_TO_BIN(?)")
			.param(materialId.toString())
			.query(Long.class)
			.optional()
			.orElse(0L);
		if (current != input.expectedVersion()) {
			throw ApiException.conflict("noteVersion", current);
		}
		jdbc.sql("""
				INSERT INTO material_notes (material_id, project_id, markdown, updated_by)
				VALUES (UUID_TO_BIN(:material), UUID_TO_BIN(:project), :markdown, UUID_TO_BIN(:actor))
				ON DUPLICATE KEY UPDATE markdown = :markdown, updated_by = UUID_TO_BIN(:actor), version = version + 1
				""")
			.param("material", materialId.toString())
			.param("project", row.projectId().toString())
			.param("markdown", markdown)
			.param("actor", actor.toString())
			.update();
		ObjectNode payload = json.createObjectNode();
		payload.put("materialId", materialId.toString());
		payload.put("noteVersion", current + 1);
		long eventId = events.append(row.projectId(), "material.note.updated", actor, null, materialId, payload);
		Map<String, Object> saved = new LinkedHashMap<>();
		saved.put("materialId", materialId.toString());
		saved.put("version", current + 1);
		saved.put("eventId", Long.toString(eventId));
		return saved;
	}

	// 읽기 요청의 권한과 삭제 여부를 확인한다. 참여하지 않은 사용자에게는 자료가 있는지도 알리지 않는다
	Row readable(UUID actor, UUID materialId) {
		Row row = find(materialId, false);
		access.requireMember(row.projectId(), actor);
		if (row.deleted()) {
			throw new ApiException(ErrorCode.MATERIAL_DELETED);
		}
		return row;
	}

	// 쓰기 요청은 프로젝트를 잠가 팀원 제거와 이벤트 순서를 맞춘 뒤 자료를 잠근다
	Row writable(UUID actor, UUID materialId) {
		UUID projectId = find(materialId, false).projectId();
		access.requireMember(projectId, actor);
		access.lock(projectId);
		access.requireMember(projectId, actor);
		Row row = find(materialId, true);
		if (row.deleted()) {
			throw new ApiException(ErrorCode.MATERIAL_DELETED);
		}
		return row;
	}

	Row find(UUID materialId, boolean lock) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id, BIN_TO_UUID(project_id) AS project_id, BIN_TO_UUID(created_by) AS created_by,
				       kind, title, state, BIN_TO_UUID(current_revision_id) AS current_revision_id, version, deleted_at
				FROM materials WHERE id = UUID_TO_BIN(?)
				""" + (lock ? " FOR UPDATE" : ""))
			.param(materialId.toString())
			.query((rs, n) -> new Row(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("project_id")),
					UUID.fromString(rs.getString("created_by")), rs.getString("kind"), rs.getString("title"),
					rs.getString("state"),
					rs.getString("current_revision_id") == null ? null
							: UUID.fromString(rs.getString("current_revision_id")),
					rs.getLong("version"), rs.getObject("deleted_at") != null))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	UUID insert(UUID projectId, UUID actor, String kind, String title, String description, String state,
			String sourceUrl) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO materials (id, project_id, created_by, kind, title, description, state, source_url)
				VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?)
				""").params(id.toString(), projectId.toString(), actor.toString(), kind, title, description, state, sourceUrl)
			.update();
		return id;
	}

	// 새 개정을 넣고 현재 개정으로 바꾼다. 개정 번호는 자료 안에서 1씩 오른다
	UUID addRevision(Row row, UUID actor, Map<String, Object> columns) {
		UUID id = UUID.randomUUID();
		long next = jdbc.sql("SELECT COALESCE(MAX(revision_no), 0) + 1 FROM material_revisions WHERE material_id = UUID_TO_BIN(?)")
			.param(row.id().toString())
			.query(Long.class)
			.single();
		Map<String, Object> params = new LinkedHashMap<>(columns);
		params.put("id", id.toString());
		params.put("project", row.projectId().toString());
		params.put("material", row.id().toString());
		params.put("no", next);
		params.put("actor", actor.toString());
		for (String column : List.of("sourceFile", "sourceUrl", "sourceTitle", "markdown", "extractedText", "pageCount",
				"summary", "excerpt", "excerptPage")) {
			params.putIfAbsent(column, null);
		}
		jdbc.sql("""
				INSERT INTO material_revisions (id, project_id, material_id, revision_no, source_file_id, source_url,
				       source_title, markdown, extracted_text, page_count, summary, excerpt, excerpt_page, content_hash,
				       extractor_version, created_by)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:material), :no, UUID_TO_BIN(:sourceFile),
				       :sourceUrl, :sourceTitle, :markdown, :extractedText, :pageCount, :summary, :excerpt, :excerptPage,
				       :contentHash, :extractor, UUID_TO_BIN(:actor))
				""").params(params).update();
		jdbc.sql("UPDATE materials SET current_revision_id = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)")
			.params(id.toString(), row.id().toString())
			.update();
		return id;
	}

	private void addMemoRevision(Row row, UUID actor, String markdown) {
		Map<String, Object> columns = new LinkedHashMap<>();
		columns.put("markdown", markdown);
		columns.put("excerpt", excerpt(markdown));
		columns.put("contentHash", CanonicalJson.sha256(markdown));
		columns.put("extractor", "memo-1");
		addRevision(row, actor, columns);
	}

	long event(UUID projectId, String type, UUID actor, UUID materialId) {
		Row row = find(materialId, false);
		ObjectNode payload = json.createObjectNode();
		payload.put("materialId", materialId.toString());
		payload.put("materialVersion", row.version());
		if (!row.deleted()) {
			payload.put("status", row.state());
		}
		return events.append(projectId, type, actor, null, materialId, payload);
	}

	// contracts/schemas/stage-2.schema.json의 Material
	Map<String, Object> view(UUID actor, UUID materialId) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(m.id) AS id, BIN_TO_UUID(m.project_id) AS project_id, m.kind, m.title, m.description,
				       m.state, m.version, m.source_url, BIN_TO_UUID(m.created_by) AS created_by,
				       u.display_name AS creator_name, m.created_at, m.updated_at,
				       BIN_TO_UUID(r.id) AS revision_id, r.revision_no, r.page_count, r.summary, r.excerpt, r.excerpt_page,
				       BIN_TO_UUID(r.created_by) AS revision_by, ru.display_name AS revision_by_name,
				       r.created_at AS revision_at, f.mime_type, f.size_bytes, up.original_name
				FROM materials m
				JOIN users u ON u.id = m.created_by
				LEFT JOIN material_revisions r ON r.id = m.current_revision_id
				LEFT JOIN users ru ON ru.id = r.created_by
				LEFT JOIN file_objects f ON f.id = m.source_file_id
				LEFT JOIN uploads up ON up.file_object_id = f.id
				WHERE m.id = UUID_TO_BIN(?)
				""").param(materialId.toString()).query((rs, n) -> {
			Map<String, Object> material = summary(rs);
			material.put("description", rs.getString("description"));
			Map<String, Object> origin = new LinkedHashMap<>();
			origin.put("fileName", rs.getString("original_name"));
			origin.put("mimeType", rs.getString("mime_type"));
			origin.put("sizeBytes", rs.getObject("size_bytes"));
			origin.put("url", rs.getString("source_url"));
			material.put("source", origin);
			Map<String, Object> revision = null;
			if (rs.getString("revision_id") != null) {
				revision = new LinkedHashMap<>();
				revision.put("id", rs.getString("revision_id"));
				revision.put("sourceVersion", rs.getLong("revision_no"));
				revision.put("pageCount", rs.getObject("page_count"));
				revision.put("summary", rs.getString("summary"));
				Map<String, Object> excerpt = null;
				if (rs.getString("excerpt") != null) {
					excerpt = new LinkedHashMap<>();
					excerpt.put("text", rs.getString("excerpt"));
					excerpt.put("page", rs.getObject("excerpt_page"));
				}
				revision.put("excerpt", excerpt);
				revision.put("createdBy", person(rs.getString("revision_by"), rs.getString("revision_by_name")));
				revision.put("createdAt", instant(rs, "revision_at"));
			}
			material.put("currentRevision", revision);
			material.put("job", jobs.latest("material", materialId));
			if (revision != null && "ready".equals(rs.getString("state"))) {
				Map<String, Object> summaryJob = jobs.latest(MaterialSummary.TYPE, UUID.fromString(rs.getString("revision_id")));
				if (summaryJob != null) {
					material.put("summaryJob", summaryJob);
				}
			}
			material.put("canDelete", actor.toString().equals(rs.getString("created_by"))
					|| access.isOwner(UUID.fromString(rs.getString("project_id")), actor));
			return material;
		}).single();
	}

	private Map<String, Object> summary(ResultSet rs) throws SQLException {
		Map<String, Object> material = new LinkedHashMap<>();
		material.put("id", rs.getString("id"));
		material.put("projectId", rs.getString("project_id"));
		material.put("kind", rs.getString("kind"));
		material.put("title", rs.getString("title"));
		material.put("status", rs.getString("state"));
		material.put("version", rs.getLong("version"));
		material.put("pageCount", rs.getObject("page_count"));
		material.put("createdBy", person(rs.getString("created_by"), rs.getString("creator_name")));
		material.put("createdAt", instant(rs, "created_at"));
		material.put("updatedAt", instant(rs, "updated_at"));
		return material;
	}

	private static Map<String, Object> person(String userId, String name) {
		Map<String, Object> person = new LinkedHashMap<>();
		person.put("userId", userId);
		person.put("name", name);
		return person;
	}

	private void expect(Row row, Long expectedVersion) {
		if (expectedVersion == null || expectedVersion < 1) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		if (row.version() != expectedVersion) {
			throw ApiException.conflict("materialVersion", row.version());
		}
	}

	String title(String value, boolean required) {
		String title = value == null ? "" : value.strip();
		if (title.length() > TITLE_MAX || (required && title.isEmpty())) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, "title", title.isEmpty() ? "REQUIRED" : "TOO_LONG",
					title.isEmpty() ? "제목을 입력해 주세요." : "제목은 255자까지 쓸 수 있어요.");
		}
		return title;
	}

	String text(String value, String field, int max, boolean required) {
		if (value == null && required) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, field, "REQUIRED", "내용을 입력해 주세요.");
		}
		String text = value == null ? "" : value;
		if (text.length() > max) {
			throw ApiException.field(ErrorCode.VALIDATION_FAILED, field, "TOO_LONG", max + "자까지 쓸 수 있어요.");
		}
		return text;
	}

	// 본문 앞부분에서 표시 기호를 걷어 낸 짧은 발췌. 글이 없으면 null
	static String excerpt(String text) {
		String plain = text.replaceAll("(?m)^\\h*(#{1,6}|[-*+>]|\\d+\\.)\\h+", "").replaceAll("\\s+", " ").strip();
		if (plain.isEmpty()) {
			return null;
		}
		return plain.length() <= 300 ? plain : plain.substring(0, 300);
	}

	IdempotencyStore.Key key(UUID actor, String path, String value) {
		if (value == null || value.isBlank() || value.length() > 128) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		return IdempotencyStore.Key.of(actor, "POST", path, value);
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> read(String body) {
		return json.readValue(body, Map.class);
	}

	private static String cursorId(String cursor) {
		try {
			return UUID.fromString(cursor).toString();
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(ErrorCode.INVALID_CURSOR);
		}
	}

	private static Object instant(ResultSet rs, String column) throws SQLException {
		LocalDateTime value = rs.getObject(column, LocalDateTime.class);
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}

}
