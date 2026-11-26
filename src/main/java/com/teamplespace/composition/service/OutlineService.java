package com.teamplespace.composition.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutlineService {

	public record SourceRef(UUID materialId, UUID revisionId, long sourceVersion, boolean sourceDeleted) {
	}

	public record Item(UUID id, String title, String summary, int position, List<SourceRef> sourceRefs) {
	}

	public record Outline(UUID projectId, long version, List<Item> items) {
	}

	public record SaveResult(int status, JsonNode body) {
	}

	private record OutlineRow(UUID id, long version) {
	}

	private record ItemRow(UUID id, String title, String summary, int position, SourceRef source) {
	}

	private static final long EMPTY_VERSION = 1;

	private final JdbcClient jdbc;
	private final JdbcTemplate batches;
	private final ProjectAccess access;
	private final IdempotencyStore idempotency;
	private final ProjectEvents events;
	private final ObjectMapper json;

	public OutlineService(JdbcClient jdbc, JdbcTemplate batches, ProjectAccess access,
			IdempotencyStore idempotency, ProjectEvents events, ObjectMapper json) {
		this.jdbc = jdbc;
		this.batches = batches;
		this.access = access;
		this.idempotency = idempotency;
		this.events = events;
		this.json = json;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Outline get(UUID actor, UUID projectId) {
		access.requireMember(projectId, actor);
		OutlineRow outline = find(projectId);
		return new Outline(projectId, outline.version(), outline.id() == null ? List.of() : items(outline.id()));
	}

	@Transactional
	public SaveResult save(UUID actor, UUID projectId, String keyValue, JsonNode body) {
		// 팀원 제거와 자료 변경, 구성 저장이 같은 프로젝트 잠금을 사용한다
		access.lock(projectId);
		access.requireMember(projectId, actor);
		if (keyValue == null || keyValue.isBlank() || keyValue.length() > 128) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		var key = IdempotencyStore.Key.of(actor, "PUT", "/v1/projects/" + projectId + "/outline", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
		if (stored.isPresent()) {
			return new SaveResult(stored.get().status(), json.readTree(stored.get().body()));
		}

		OutlineInput input = OutlineInput.parse(body);
		OutlineRow current = find(projectId);
		if (current.version() != input.expectedVersion()) {
			JsonNode conflict = json.createObjectNode().put("outlineVersion", current.version());
			return complete(key, 409, conflict, projectId);
		}
		if (current.version() == OutlineInput.MAX_VERSION) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}

		List<Item> replacement = replacement(projectId, current.id(), input);
		UUID outlineId = current.id() == null ? UUID.randomUUID() : current.id();
		Outline outline = writeReplacement(actor, projectId, current, outlineId, replacement);
		return complete(key, 200, json.valueToTree(outline), outlineId);
	}

	long version(UUID projectId) {
		return find(projectId).version();
	}

	// 호출자는 프로젝트 잠금을 보유하고 발행 직전 원본을 다시 확인한다
	Outline publishGenerated(UUID actor, UUID projectId, OutlineInput input) {
		OutlineRow current = find(projectId);
		UUID outlineId = current.id() == null ? UUID.randomUUID() : current.id();
		return writeReplacement(actor, projectId, current, outlineId, replacement(projectId, current.id(), input));
	}

	// 새 자료를 반영한 항목을 그대로 저장한다. 호출자는 프로젝트 잠금을 보유하고 발행 직전 원본을 다시 확인한다
	Outline publishItems(UUID actor, UUID projectId, List<Item> items) {
		OutlineRow current = find(projectId);
		List<Item> positioned = new ArrayList<>();
		for (int position = 0; position < items.size(); position++) {
			Item item = items.get(position);
			positioned.add(new Item(item.id(), item.title(), item.summary(), position, item.sourceRefs()));
		}
		return writeReplacement(actor, projectId, current, current.id(), List.copyOf(positioned));
	}

	private Outline writeReplacement(UUID actor, UUID projectId, OutlineRow current, UUID outlineId, List<Item> replacement) {
		long version = current.version() + 1;
		if (current.id() == null) {
			jdbc.sql("""
					INSERT INTO outlines(id, project_id, version, updated_by)
					VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, UUID_TO_BIN(?))
					""").params(outlineId.toString(), projectId.toString(), version, actor.toString()).update();
		}
		else {
			updateVersion(actor, outlineId, version);
			jdbc.sql("DELETE FROM outline_items WHERE outline_id = UUID_TO_BIN(?)").param(outlineId.toString()).update();
		}
		storeItems(projectId, outlineId, replacement);
		events.append(projectId, "outline.updated", actor, null, outlineId,
				json.createObjectNode().put("outlineVersion", version));
		return new Outline(projectId, version, replacement);
	}

	@Transactional
	public SaveResult replaceSources(UUID actor, UUID itemId, String keyValue, JsonNode body) {
		var key = keyValue == null || keyValue.isBlank() || keyValue.length() > 128 ? null
				: IdempotencyStore.Key.of(actor, "PUT", "/v1/outline-items/" + itemId + "/sources", keyValue);
		UUID projectId = jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM outline_items WHERE id = UUID_TO_BIN(?)")
			.param(itemId.toString()).query(String.class).optional().map(UUID::fromString)
			.or(() -> key == null ? Optional.empty() : idempotency.findResource(key))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		access.lock(projectId);
		access.requireMember(projectId, actor);
		if (key == null) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
		if (stored.isPresent()) {
			return new SaveResult(stored.get().status(), json.readTree(stored.get().body()));
		}
		OutlineInput.checkFields(body, Set.of("expectedVersion", "sourceIds"), "outline");
		long expected = OutlineInput.expectedVersion(body);
		List<UUID> sourceIds = OutlineInput.sources(body.path("sourceIds"), "sourceIds");
		OutlineRow current = find(projectId);
		if (expected != current.version()) {
			return complete(key, 409, json.createObjectNode().put("outlineVersion", current.version()), projectId);
		}
		if (current.version() == OutlineInput.MAX_VERSION) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		List<Item> currentItems = current.id() == null ? List.of() : items(current.id());
		Item target = currentItems.stream().filter(item -> item.id().equals(itemId)).findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<SourceRef> refs = resolve(sourceIds, readySources(projectId, sourceIds), "sourceIds");
		Item updated = new Item(itemId, target.title(), target.summary(), target.position(), refs);
		jdbc.sql("DELETE FROM outline_sources WHERE outline_item_id = UUID_TO_BIN(?)").param(itemId.toString()).update();
		storeSources(projectId, List.of(updated));
		long version = current.version() + 1;
		updateVersion(actor, current.id(), version);
		events.append(projectId, "outline.updated", actor, null, current.id(),
				json.createObjectNode().put("outlineVersion", version));
		List<Item> updatedItems = new ArrayList<>(currentItems);
		updatedItems.set(target.position(), updated);
		return complete(key, 200, json.valueToTree(new Outline(projectId, version, updatedItems)), projectId);
	}

	private void updateVersion(UUID actor, UUID outlineId, long version) {
		jdbc.sql("UPDATE outlines SET version = ?, updated_by = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)")
			.params(version, actor.toString(), outlineId.toString()).update();
	}

	private SaveResult complete(IdempotencyStore.Key key, int status, JsonNode body, UUID resource) {
		idempotency.complete(key, status == 200, status, json.writeValueAsString(body), resource);
		return new SaveResult(status, body);
	}

	private OutlineRow find(UUID projectId) {
		return jdbc.sql("SELECT BIN_TO_UUID(id) AS id, version FROM outlines WHERE project_id = UUID_TO_BIN(?)")
			.param(projectId.toString())
			.query((rs, unused) -> new OutlineRow(UUID.fromString(rs.getString("id")), rs.getLong("version")))
			.optional().orElse(new OutlineRow(null, EMPTY_VERSION));
	}

	private List<Item> items(UUID outlineId) {
		List<ItemRow> rows = jdbc.sql("""
				SELECT BIN_TO_UUID(i.id) AS item_id, i.title, i.summary, i.position,
				       BIN_TO_UUID(r.material_id) AS material_id, BIN_TO_UUID(s.revision_id) AS revision_id,
				       r.revision_no, (m.deleted_at IS NOT NULL OR m.state = 'deleted') AS source_deleted
				FROM outline_items i
				LEFT JOIN outline_sources s ON s.outline_item_id = i.id
				LEFT JOIN material_revisions r ON r.id = s.revision_id
				LEFT JOIN materials m ON m.id = r.material_id
				WHERE i.outline_id = UUID_TO_BIN(?)
				ORDER BY i.position, s.position
				""").param(outlineId.toString())
			.query((rs, unused) -> {
				String revision = rs.getString("revision_id");
				SourceRef source = revision == null ? null : new SourceRef(UUID.fromString(rs.getString("material_id")),
						UUID.fromString(revision), rs.getLong("revision_no"), rs.getBoolean("source_deleted"));
				return new ItemRow(UUID.fromString(rs.getString("item_id")), rs.getString("title"),
						rs.getString("summary"), rs.getInt("position"), source);
			}).list();
		Map<UUID, Item> items = new LinkedHashMap<>();
		for (ItemRow row : rows) {
			Item item = items.computeIfAbsent(row.id(), id ->
					new Item(id, row.title(), row.summary(), row.position(), new ArrayList<>()));
			if (row.source() != null) {
				item.sourceRefs().add(row.source());
			}
		}
		return List.copyOf(items.values());
	}

	private List<Item> replacement(UUID projectId, UUID outlineId, OutlineInput input) {
		Set<UUID> existing = outlineId == null ? Set.of() : new HashSet<>(jdbc.sql("""
				SELECT BIN_TO_UUID(id) FROM outline_items WHERE outline_id = UUID_TO_BIN(?)
				""").param(outlineId.toString()).query(String.class).list().stream().map(UUID::fromString).toList());
		List<UUID> sourceIds = input.items().stream().flatMap(item -> item.sourceIds().stream()).distinct().toList();
		Map<UUID, SourceRef> allowed = readySources(projectId, sourceIds);
		List<Item> result = new ArrayList<>();
		for (int position = 0; position < input.items().size(); position++) {
			var item = input.items().get(position);
			if (item.id() != null && !existing.contains(item.id())) {
				throw OutlineInput.invalid("items[" + position + "].id", "이 발표 구성의 항목을 선택해 주세요.");
			}
			List<SourceRef> sources = resolve(item.sourceIds(), allowed, "items[" + position + "].sourceIds");
			UUID id = item.id() == null ? UUID.randomUUID() : item.id();
			result.add(new Item(id, item.title(), item.summary(), position, sources));
		}
		return List.copyOf(result);
	}

	private List<SourceRef> resolve(List<UUID> ids, Map<UUID, SourceRef> allowed, String field) {
		List<SourceRef> sources = new ArrayList<>();
		for (int index = 0; index < ids.size(); index++) {
			SourceRef source = allowed.get(ids.get(index));
			if (source == null) {
				throw OutlineInput.invalid(field + "[" + index + "]", "같은 프로젝트에서 이용할 수 있는 자료를 선택해 주세요.");
			}
			sources.add(source);
		}
		return List.copyOf(sources);
	}

	private Map<UUID, SourceRef> readySources(UUID projectId, List<UUID> ids) {
		Map<UUID, SourceRef> result = new HashMap<>();
		if (ids.isEmpty()) {
			return result;
		}
		List<SourceRef> sources = jdbc.sql("""
				SELECT BIN_TO_UUID(m.id) AS material_id, BIN_TO_UUID(r.id) AS revision_id, r.revision_no
				FROM JSON_TABLE(:ids, '$[*]' COLUMNS (material_id VARCHAR(36) PATH '$')) selected
				JOIN materials m ON m.id = UUID_TO_BIN(selected.material_id)
				JOIN material_revisions r ON r.id = m.current_revision_id
				    AND r.project_id = m.project_id AND r.material_id = m.id
				WHERE m.project_id = UUID_TO_BIN(:project) AND m.state = 'ready' AND m.deleted_at IS NULL
				""").param("ids", json.writeValueAsString(ids)).param("project", projectId.toString())
			.query((rs, unused) -> new SourceRef(UUID.fromString(rs.getString("material_id")),
					UUID.fromString(rs.getString("revision_id")), rs.getLong("revision_no"), false)).list();
		for (SourceRef source : sources) {
			result.put(source.materialId(), source);
		}
		return result;
	}

	private void storeItems(UUID projectId, UUID outlineId, List<Item> items) {
		if (!items.isEmpty()) {
			List<Object[]> rows = items.stream().map(item -> new Object[] { item.id().toString(),
					projectId.toString(), outlineId.toString(), item.title(), item.summary(), item.position() }).toList();
			batches.batchUpdate("""
					INSERT INTO outline_items(id, project_id, outline_id, title, summary, position)
					VALUES(UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)
					""", rows);
		}
		storeSources(projectId, items);
	}

	private void storeSources(UUID projectId, List<Item> items) {
		List<Object[]> sources = new ArrayList<>();
		for (Item item : items) {
			for (int position = 0; position < item.sourceRefs().size(); position++) {
				sources.add(new Object[] { item.id().toString(), item.sourceRefs().get(position).revisionId().toString(),
						projectId.toString(), position });
			}
		}
		if (!sources.isEmpty()) {
			batches.batchUpdate("""
					INSERT INTO outline_sources(outline_item_id, revision_id, project_id, position)
					VALUES(UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)
					""", sources);
		}
	}
}
