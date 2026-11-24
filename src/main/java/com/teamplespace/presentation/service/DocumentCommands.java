package com.teamplespace.presentation.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.presentation.service.ElementPatchRequest.Geometry;
import com.teamplespace.review.service.ChangeGroups;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// 슬라이드와 요소를 추가하고 지우고 슬라이드 순서를 바꾸는 저장 명령
// 문서 변경, 버전, 이벤트, 멱등 결과를 한 트랜잭션으로 저장한다
// ponytail: 구조 변경은 변경 이력에 남기지 않는다. 이력과 검토 단계에서 형식을 정해 더한다
@Service
public class DocumentCommands {

	public record Result(int status, JsonNode body) {
	}

	private record Document(UUID id, long generation, long version, long orderVersion) {
	}

	private record SlideRow(UUID presentationId, long generation, long version, boolean deleted) {
	}

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

	// 순서를 다시 매길 때 잠시 옮겨 두는 자리. 실제 슬라이드 수보다 충분히 크다
	private static final int PARKED = 1_000_000;

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final IdempotencyStore idempotency;

	private final ProjectAccess access;

	private final ProjectEvents events;

	private final ChangeGroups groups;

	private final ObjectMapper json;

	private final Clock clock;

	public DocumentCommands(JdbcClient jdbc, TransactionTemplate transaction, IdempotencyStore idempotency,
			ProjectAccess access, ProjectEvents events, ChangeGroups groups, ObjectMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.idempotency = idempotency;
		this.access = access;
		this.events = events;
		this.groups = groups;
		this.json = json;
		this.clock = clock;
	}

	// contracts/schemas/api.schema.json의 SlideCreateRequest
	public Result createSlide(Actor actor, UUID presentationId, String idempotencyKey, JsonNode body) {
		ElementPatchRequest.requireObject(body,
				Set.of("generation", "orderVersion", "clientMutationId", "slideId", "afterSlideId", "layoutId"));
		long generation = ElementPatchRequest.version(body.get("generation"));
		long orderVersion = ElementPatchRequest.version(body.get("orderVersion"));
		String mutationId = mutationId(body);
		UUID slideId = ElementPatchRequest.uuid(body.get("slideId"));
		String after = optionalUuid(body.get("afterSlideId"));
		JsonNode layoutNode = body.get("layoutId");
		ElementPatchRequest.require(layoutNode == null || layoutNode.isNull()
				|| (layoutNode.isString() && layoutNode.asString().length() <= 64));
		String layoutId = layoutNode == null || layoutNode.isNull() ? null : layoutNode.asString();
		UUID projectId = projectOf("presentations", presentationId);

		return run(actor, projectId, "POST", "/v1/presentations/" + presentationId + "/slides", idempotencyKey, body,
				slideId, () -> {
					Document document = lockDocument(presentationId);
					if (document.generation() != generation || document.orderVersion() != orderVersion) {
						return reject(document, null, null);
					}
					List<String> order = slideIds(document);
					int index = after == null ? 0 : order.indexOf(after) + 1;
					// 없는 슬라이드 뒤에 넣을 수 없고 이미 쓰인 식별자로 만들 수 없다
					ElementPatchRequest.require(after == null || index > 0);
					ElementPatchRequest.require(count("slides", slideId) == 0);
					jdbc.sql("""
							INSERT INTO slides (id, project_id, presentation_id, generation, position, layout_id)
							VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:presentation), :generation,
							        :position, :layout)
							""")
						.param("id", slideId.toString())
						.param("project", projectId.toString())
						.param("presentation", presentationId.toString())
						.param("generation", generation)
						.param("position", PARKED - 1)
						.param("layout", layoutId)
						.update();
					order.add(index, slideId.toString());
					renumber(document, order);

					ObjectNode slide = json.createObjectNode();
					slide.put("id", slideId.toString());
					slide.put("presentationId", presentationId.toString());
					slide.put("generation", generation);
					slide.put("version", 1);
					slide.put("layoutId", layoutId);
					slide.putArray("elementIds");
					slide.putArray("elements");
					ObjectNode change = json.createObjectNode();
					change.set("resource", slide);
					change.put("slideVersion", 1);
					return finishOrder(projectId, document, order, "slide.created", actor, mutationId, slideId, change);
				});
	}

	// contracts/schemas/api.schema.json의 SlideDeleteRequest
	public Result deleteSlide(Actor actor, UUID slideId, String idempotencyKey, JsonNode body) {
		ElementPatchRequest.requireObject(body, Set.of("generation", "orderVersion", "clientMutationId"));
		long generation = ElementPatchRequest.version(body.get("generation"));
		long orderVersion = ElementPatchRequest.version(body.get("orderVersion"));
		String mutationId = mutationId(body);
		UUID projectId = projectOf("slides", slideId);

		return run(actor, projectId, "DELETE", "/v1/slides/" + slideId, idempotencyKey, body, slideId, () -> {
			SlideRow slide = lockSlide(slideId);
			Document document = lockDocument(slide.presentationId());
			if (slide.deleted() || slide.generation() != document.generation() || document.generation() != generation
					|| document.orderVersion() != orderVersion) {
				return reject(document, slide.version(), null);
			}
			List<String> order = slideIds(document);
			// 문서에는 슬라이드가 하나는 남아야 한다
			ElementPatchRequest.require(order.size() > 1);
			LocalDateTime now = LocalDateTime.now(clock);
			long slideVersion = slide.version() + 1;
			jdbc.sql("UPDATE slides SET deleted_at = :now, version = :version WHERE id = UUID_TO_BIN(:id)")
				.param("now", now)
				.param("version", slideVersion)
				.param("id", slideId.toString())
				.update();
			groups.closeForDeletedInLock(projectId, slideId, null);
			order.remove(slideId.toString());
			renumber(document, order);

			ObjectNode change = json.createObjectNode();
			change.set("tombstone", tombstone("slide", slideId, slideVersion, now));
			change.put("slideVersion", slideVersion);
			return finishOrder(projectId, document, order, "slide.deleted", actor, mutationId, slideId, change);
		});
	}

	// contracts/schemas/api.schema.json의 SlideOrderRequest
	public Result reorderSlides(Actor actor, UUID presentationId, String idempotencyKey, JsonNode body) {
		ElementPatchRequest.requireObject(body, Set.of("generation", "orderVersion", "clientMutationId", "slideIds"));
		long generation = ElementPatchRequest.version(body.get("generation"));
		long orderVersion = ElementPatchRequest.version(body.get("orderVersion"));
		String mutationId = mutationId(body);
		JsonNode idsNode = body.get("slideIds");
		ElementPatchRequest.require(idsNode != null && idsNode.isArray() && !idsNode.isEmpty());
		List<String> requested = new ArrayList<>();
		idsNode.forEach(id -> requested.add(ElementPatchRequest.uuid(id).toString()));
		ElementPatchRequest.require(new HashSet<>(requested).size() == requested.size());
		UUID projectId = projectOf("presentations", presentationId);

		return run(actor, projectId, "PUT", "/v1/presentations/" + presentationId + "/slide-order", idempotencyKey,
				body, presentationId, () -> {
					Document document = lockDocument(presentationId);
					if (document.generation() != generation || document.orderVersion() != orderVersion) {
						return reject(document, null, null);
					}
					// 지금 있는 슬라이드를 빠짐없이 한 번씩 담아야 한다
					List<String> current = slideIds(document);
					ElementPatchRequest
						.require(requested.size() == current.size() && new HashSet<>(current).containsAll(requested));
					renumber(document, requested);
					return finishOrder(projectId, document, requested, "slide.order.updated", actor, mutationId,
							presentationId, json.createObjectNode());
				});
	}

	// contracts/schemas/api.schema.json의 ElementCreateRequest
	public Result createElement(Actor actor, UUID slideId, String idempotencyKey, JsonNode body) {
		ElementPatchRequest.requireObject(body, Set.of("generation", "clientMutationId", "element"));
		long generation = ElementPatchRequest.version(body.get("generation"));
		String mutationId = mutationId(body);
		JsonNode element = body.get("element");
		ElementPatchRequest.requireObject(element, Set.of("id", "type", "geometry", "content", "style"));
		UUID elementId = ElementPatchRequest.uuid(element.get("id"));
		String type = ElementPatchRequest.string(element.get("type"));
		// 이미지는 파일을 확정해 연결하는 경로가 생길 때 연다
		ElementPatchRequest.require(type.equals("text") || type.equals("shape"));
		Geometry geometry = ElementPatchRequest.geometry(element.get("geometry"));
		JsonNode content = element.get("content");
		JsonNode style = element.get("style");
		ElementPatchRequest.require(geometry != null && content != null && style != null);
		ElementRules.check(type, content, style, null);
		UUID projectId = projectOf("slides", slideId);

		return run(actor, projectId, "POST", "/v1/slides/" + slideId + "/elements", idempotencyKey, body, elementId,
				() -> {
					SlideRow slide = lockSlide(slideId);
					Document document = lockDocument(slide.presentationId());
					if (slide.deleted() || slide.generation() != document.generation()
							|| document.generation() != generation) {
						return reject(document, slide.version(), null);
					}
					ElementPatchRequest.require(count("slide_elements", elementId) == 0);
					jdbc.sql("""
							INSERT INTO slide_elements (id, project_id, slide_id, type, x, y, width, height, rotation,
							                            z_order, content, style)
							VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:slide), :type, :x, :y, :width,
							        :height, :rotation, :z, CAST(:content AS JSON), CAST(:style AS JSON))
							""")
						.param("id", elementId.toString())
						.param("project", projectId.toString())
						.param("slide", slideId.toString())
						.param("type", type)
						.param("x", geometry.x())
						.param("y", geometry.y())
						.param("width", geometry.width())
						.param("height", geometry.height())
						.param("rotation", geometry.rotation())
						.param("z", geometry.z())
						.param("content", json.writeValueAsString(content))
						.param("style", json.writeValueAsString(style))
						.update();

					ObjectNode change = json.createObjectNode();
					change.set("resource", ElementJson.of(json, elementId, slideId, type, 1, geometry, content, style));
					change.put("elementVersion", 1);
					// 요소 추가도 요소 변경 이벤트로 알린다. 받는 쪽은 모르는 요소면 슬라이드에 더한다
					return finishElement(projectId, document, slideId, slide.version() + 1, "element.updated", actor,
							mutationId, elementId, change);
				});
	}

	// contracts/schemas/api.schema.json의 ElementDeleteRequest
	public Result deleteElement(Actor actor, UUID slideId, UUID elementId, String idempotencyKey, JsonNode body) {
		ElementPatchRequest.requireObject(body, Set.of("generation", "baseVersion", "clientMutationId"));
		long generation = ElementPatchRequest.version(body.get("generation"));
		long baseVersion = ElementPatchRequest.version(body.get("baseVersion"));
		String mutationId = mutationId(body);
		UUID projectId = jdbc
			.sql("SELECT BIN_TO_UUID(project_id) FROM slide_elements"
					+ " WHERE id = UUID_TO_BIN(:element) AND slide_id = UUID_TO_BIN(:slide)")
			.param("element", elementId.toString())
			.param("slide", slideId.toString())
			.query(String.class)
			.optional()
			.map(UUID::fromString)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		return run(actor, projectId, "DELETE", "/v1/slides/" + slideId + "/elements/" + elementId, idempotencyKey,
				body, elementId, () -> {
					SlideRow slide = lockSlide(slideId);
					Document document = lockDocument(slide.presentationId());
					Map<String, Object> current = jdbc
						.sql("SELECT version, deleted_at IS NOT NULL AS deleted FROM slide_elements"
								+ " WHERE id = UUID_TO_BIN(:id) FOR UPDATE")
						.param("id", elementId.toString())
						.query()
						.singleRow();
					long elementVersion = ((Number) current.get("version")).longValue();
					boolean deleted = ((Number) current.get("deleted")).intValue() == 1;
					if (deleted || slide.deleted() || slide.generation() != document.generation()
							|| document.generation() != generation || elementVersion != baseVersion) {
						return reject(document, slide.version(), elementVersion);
					}
					LocalDateTime now = LocalDateTime.now(clock);
					jdbc.sql("UPDATE slide_elements SET deleted_at = :now, version = :version WHERE id = UUID_TO_BIN(:id)")
						.param("now", now)
						.param("version", elementVersion + 1)
						.param("id", elementId.toString())
						.update();
					groups.closeForDeletedInLock(projectId, slideId, elementId);

					ObjectNode change = json.createObjectNode();
					change.set("tombstone", tombstone("element", elementId, elementVersion + 1, now));
					change.put("elementVersion", elementVersion + 1);
					return finishElement(projectId, document, slideId, slide.version() + 1, "element.deleted", actor,
							mutationId, elementId, change);
				});
	}

	// 권한 확인, 멱등 결과 찾기, 프로젝트 잠금, 결과 기록을 모든 명령이 같은 순서로 거친다
	private Result run(Actor actor, UUID projectId, String method, String path, String idempotencyKey, JsonNode body,
			UUID resourceId, Supplier<Result> work) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		IdempotencyStore.Key key = IdempotencyStore.Key.of(actor.userId(), method, path, idempotencyKey);
		byte[] requestHash = CanonicalJson.sha256(body);
		return transaction.execute(status -> {
			access.requireMember(projectId, actor.userId());
			// 버전 검사보다 먼저 같은 멱등키의 기존 결과를 찾는다
			Optional<IdempotencyStore.Stored> stored = idempotency.findOrClaim(key, requestHash);
			if (stored.isPresent()) {
				return new Result(stored.get().status(), json.readTree(stored.get().body()));
			}
			access.lock(projectId);
			access.requireMember(projectId, actor.userId());
			groups.closeExpiredInLock(projectId);
			Result result = work.get();
			// 거절 결과도 같은 키의 재시도에 그대로 돌려준다
			idempotency.complete(key, result.status() == 200, result.status(),
					json.writeValueAsString(result.body()), resourceId);
			return result;
		});
	}

	// 슬라이드 목록이 바뀐 변경을 마무리한다. 문서 버전과 순서 버전을 함께 올린다
	private Result finishOrder(UUID projectId, Document document, List<String> order, String type, Actor actor,
			String mutationId, UUID resourceId, ObjectNode change) {
		long presentationVersion = document.version() + 1;
		long orderVersion = document.orderVersion() + 1;
		jdbc.sql("UPDATE presentations SET version = :version, order_version = :orderVersion WHERE id = UUID_TO_BIN(:id)")
			.param("version", presentationVersion)
			.param("orderVersion", orderVersion)
			.param("id", document.id().toString())
			.update();
		ArrayNode slideIds = change.putArray("slideIds");
		order.forEach(slideIds::add);
		change.put("presentationVersion", presentationVersion);
		change.put("orderVersion", orderVersion);
		change.put("generation", document.generation());
		return respond(projectId, type, actor, mutationId, resourceId, change);
	}

	// 요소가 생기거나 지워진 변경을 마무리한다. 슬라이드 버전과 문서 버전을 함께 올린다
	private Result finishElement(UUID projectId, Document document, UUID slideId, long slideVersion, String type,
			Actor actor, String mutationId, UUID elementId, ObjectNode change) {
		long presentationVersion = document.version() + 1;
		jdbc.sql("UPDATE slides SET version = :version WHERE id = UUID_TO_BIN(:id)")
			.param("version", slideVersion)
			.param("id", slideId.toString())
			.update();
		jdbc.sql("UPDATE presentations SET version = :version WHERE id = UUID_TO_BIN(:id)")
			.param("version", presentationVersion)
			.param("id", document.id().toString())
			.update();
		change.put("slideVersion", slideVersion);
		change.put("presentationVersion", presentationVersion);
		change.put("generation", document.generation());
		return respond(projectId, type, actor, mutationId, elementId, change);
	}

	private Result respond(UUID projectId, String type, Actor actor, String mutationId, UUID resourceId,
			ObjectNode change) {
		long eventSeq = events.append(projectId, type, actor.userId(), mutationId, resourceId, change);
		ObjectNode response = change.deepCopy();
		response.put("clientMutationId", mutationId);
		response.put("eventId", Long.toString(eventSeq));
		return new Result(200, response);
	}

	// 문서는 바꾸지 않고 현재 버전을 알린다
	private Result reject(Document document, Long slideVersion, Long elementVersion) {
		ErrorCode code = ErrorCode.VERSION_CONFLICT;
		ObjectNode error = json.createObjectNode();
		error.put("code", code.name());
		error.put("message", code.message());
		error.put("retryable", code.retryable());
		ObjectNode versions = error.putObject("currentVersions");
		versions.put("generation", document.generation());
		versions.put("presentationVersion", document.version());
		versions.put("orderVersion", document.orderVersion());
		if (slideVersion != null) {
			versions.put("slideVersion", slideVersion);
		}
		if (elementVersion != null) {
			versions.put("elementVersion", elementVersion);
		}
		ObjectNode body = json.createObjectNode();
		body.set("error", error);
		return new Result(code.httpStatus(), body);
	}

	private ObjectNode tombstone(String resourceType, UUID id, long version, LocalDateTime deletedAt) {
		ObjectNode tombstone = json.createObjectNode();
		tombstone.put("resourceType", resourceType);
		tombstone.put("id", id.toString());
		tombstone.put("version", version);
		tombstone.put("deletedAt", deletedAt.format(TIMESTAMP));
		return tombstone;
	}

	// 남은 슬라이드의 위치를 주어진 순서대로 다시 매긴다
	// 같은 위치를 두 슬라이드가 쓸 수 없어 먼저 모두 비켜 둔 뒤 하나씩 놓는다
	private void renumber(Document document, List<String> order) {
		jdbc.sql("""
				UPDATE slides SET position = position + :parked
				WHERE presentation_id = UUID_TO_BIN(:presentation) AND generation = :generation
				  AND deleted_at IS NULL AND position < :limit
				""")
			.param("parked", PARKED)
			.param("limit", PARKED - 1)
			.param("presentation", document.id().toString())
			.param("generation", document.generation())
			.update();
		for (int index = 0; index < order.size(); index++) {
			jdbc.sql("UPDATE slides SET position = :position WHERE id = UUID_TO_BIN(:id)")
				.param("position", index)
				.param("id", order.get(index))
				.update();
		}
	}

	private List<String> slideIds(Document document) {
		return new ArrayList<>(jdbc.sql("""
				SELECT BIN_TO_UUID(id) FROM slides
				WHERE presentation_id = UUID_TO_BIN(:presentation) AND generation = :generation AND deleted_at IS NULL
				ORDER BY position
				""")
			.param("presentation", document.id().toString())
			.param("generation", document.generation())
			.query(String.class)
			.list());
	}

	private Document lockDocument(UUID presentationId) {
		return jdbc.sql("""
				SELECT generation, version, order_version FROM presentations
				WHERE id = UUID_TO_BIN(:id) AND state = 'ready' FOR UPDATE
				""")
			.param("id", presentationId.toString())
			.query((rs, rowNum) -> new Document(presentationId, rs.getLong("generation"), rs.getLong("version"),
					rs.getLong("order_version")))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private SlideRow lockSlide(UUID slideId) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(presentation_id) AS presentation_id, generation, version,
				       deleted_at IS NOT NULL AS deleted
				FROM slides WHERE id = UUID_TO_BIN(:id) FOR UPDATE
				""")
			.param("id", slideId.toString())
			.query((rs, rowNum) -> new SlideRow(UUID.fromString(rs.getString("presentation_id")),
					rs.getLong("generation"), rs.getLong("version"), rs.getBoolean("deleted")))
			.single();
	}

	// 테이블 이름은 코드에 적은 상수만 넘긴다
	private UUID projectOf(String table, UUID id) {
		return jdbc.sql("SELECT BIN_TO_UUID(project_id) FROM " + table + " WHERE id = UUID_TO_BIN(:id)")
			.param("id", id.toString())
			.query(String.class)
			.optional()
			.map(UUID::fromString)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private int count(String table, UUID id) {
		return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = UUID_TO_BIN(:id)")
			.param("id", id.toString())
			.query(Integer.class)
			.single();
	}

	private static String mutationId(JsonNode body) {
		String value = ElementPatchRequest.string(body.get("clientMutationId"));
		ElementPatchRequest.require(!value.isEmpty() && value.length() <= 64);
		return value;
	}

	private static String optionalUuid(JsonNode node) {
		return node == null || node.isNull() ? null : ElementPatchRequest.uuid(node).toString();
	}

}
