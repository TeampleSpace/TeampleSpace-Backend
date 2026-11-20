package com.teamplespace.presentation.service;

import java.util.Optional;
import java.util.UUID;

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
import tools.jackson.databind.node.ObjectNode;

// 요소 수정 저장 경로. 문서 변경, 변경 이력, 이벤트, 멱등 결과를 한 트랜잭션으로 저장한다
@Service
public class ElementPatchService {

	public record Result(int status, JsonNode body, boolean replayed) {
	}

	private record Current(String type, long elementVersion, boolean elementDeleted, Geometry geometry,
			String content, String style, long slideVersion, boolean slideDeleted, UUID presentationId,
			long generation, long presentationVersion) {
	}

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final IdempotencyStore idempotency;

	private final ProjectAccess access;

	private final ProjectEvents events;

	private final ChangeGroups groups;

	private final ObjectMapper json;

	public ElementPatchService(JdbcClient jdbc, TransactionTemplate transaction, IdempotencyStore idempotency,
			ProjectAccess access, ProjectEvents events, ChangeGroups groups, ObjectMapper json) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.idempotency = idempotency;
		this.access = access;
		this.events = events;
		this.groups = groups;
		this.json = json;
	}

	public Result patch(Actor actor, UUID slideId, UUID elementId, String idempotencyKey, JsonNode body) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		ElementPatchRequest request = ElementPatchRequest.parse(body);
		IdempotencyStore.Key key = IdempotencyStore.Key.of(actor.userId(), "PATCH",
				"/v1/slides/" + slideId + "/elements/" + elementId, idempotencyKey);
		byte[] requestHash = CanonicalJson.sha256(body);
		return transaction.execute(status -> patch(actor, slideId, elementId, key, requestHash, request));
	}

	private Result patch(Actor actor, UUID slideId, UUID elementId, IdempotencyStore.Key key, byte[] requestHash,
			ElementPatchRequest request) {
		// 1. 인증된 사용자의 프로젝트 참여 여부를 먼저 확인한다
		UUID projectId = jdbc
			.sql("SELECT BIN_TO_UUID(project_id) FROM slide_elements"
					+ " WHERE id = UUID_TO_BIN(:element) AND slide_id = UUID_TO_BIN(:slide)")
			.param("element", elementId.toString())
			.param("slide", slideId.toString())
			.query(String.class)
			.optional()
			.map(UUID::fromString)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		access.requireMember(projectId, actor.userId());

		// 2. 버전 검사보다 먼저 같은 멱등키의 기존 결과를 찾는다
		Optional<IdempotencyStore.Stored> stored = idempotency.findOrClaim(key, requestHash);
		if (stored.isPresent()) {
			return new Result(stored.get().status(), json.readTree(stored.get().body()), true);
		}

		// 3. 프로젝트, 문서, 대상 순서로 잠그고 권한을 다시 확인한다
		access.lock(projectId);
		access.requireMember(projectId, actor.userId());
		Current current = lockCurrent(elementId);
		// 요소 종류에 맞지 않는 내용과 스타일은 버전 검사보다 먼저 거절한다
		ElementRules.check(current.type(), request.content(), request.style(), json.readTree(current.content()));

		// 4. 시간이 지난 묶음을 먼저 닫는다. 브라우저가 닫는 요청을 보내지 못했어도 다음 쓰기에서 닫힌다
		groups.closeExpiredInLock(projectId);

		// 5. 새 요청만 세대와 버전을 검사한다. 거절 결과도 같은 키의 재시도에 그대로 돌려준다
		boolean stale = current.elementDeleted() || current.slideDeleted()
				|| current.generation() != request.generation()
				|| (request.hasPatch() && current.elementVersion() != request.baseVersion());
		if (stale) {
			return reject(key, ErrorCode.VERSION_CONFLICT, current, elementId);
		}
		Optional<ChangeGroups.Group> group = groups.find(request.editGroupId());
		if (group.isPresent() && !group.get().open()) {
			return reject(key, ErrorCode.EDIT_GROUP_CLOSED, current, elementId);
		}
		// 묶음은 처음 만든 작성자, 편집 세션, 요소, 세대에만 쓸 수 있다
		boolean misused = group.isPresent() && (!group.get().authorId().equals(actor.userId())
				|| !group.get().sessionId().equals(actor.sessionId()) || !group.get().elementId().equals(elementId)
				|| group.get().generation() != current.generation());
		if (misused || (group.isEmpty() && !request.hasPatch())) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}

		JsonNode before = ElementJson.of(json, elementId, slideId, current.type(), current.elementVersion(),
				current.geometry(), json.readTree(current.content()), json.readTree(current.style()));
		ObjectNode change = json.createObjectNode();
		long eventSeq;
		if (request.hasPatch()) {
			// 6. 다른 작성자의 묶음과 이 세션이 떠난 요소의 묶음을 닫는다
			groups.closeOtherAuthorsInLock(projectId, elementId, actor.userId());
			groups.closeOtherTargetsInLock(projectId, actor, elementId);

			// 7. 요소와 상위 버전을 함께 올린다
			Geometry geometry = request.geometry() != null ? request.geometry() : current.geometry();
			JsonNode content = request.content() != null ? request.content() : before.get("content");
			JsonNode style = request.style() != null ? request.style() : before.get("style");
			long elementVersion = current.elementVersion() + 1;
			long slideVersion = current.slideVersion() + 1;
			long presentationVersion = current.presentationVersion() + 1;
			update(elementId, slideId, current.presentationId(), geometry, content, style, elementVersion,
					slideVersion, presentationVersion);
			JsonNode after = ElementJson.of(json, elementId, slideId, current.type(), elementVersion, geometry,
					content, style);

			// 8. 묶음의 첫 저장이면 변경을 만들고, 이어지는 저장이면 마지막 이후 내용만 바꾼다
			UUID changeId;
			if (group.isPresent()) {
				changeId = group.get().changeId();
				groups.appendInLock(changeId, elementVersion, after, presentationVersion);
			}
			else {
				changeId = groups.openInLock(projectId, current.presentationId(), current.generation(), actor,
						request.editGroupId(), request.clientMutationId(), slideId, elementId,
						current.elementVersion(), before, elementVersion, after, presentationVersion);
			}
			change.set("resource", after);
			change.put("elementVersion", elementVersion);
			change.put("slideVersion", slideVersion);
			change.put("presentationVersion", presentationVersion);
			change.put("generation", current.generation());
			change.put("changeId", changeId.toString());

			// 9. 프로젝트 잠금 아래에서 이벤트 순번을 할당한다
			eventSeq = events.append(projectId, "element.updated", actor.userId(), request.clientMutationId(),
					elementId, change);
			if (request.finalizeGroup()) {
				groups.closeInLock(projectId, changeId, groups.now(), request.clientMutationId());
			}
		}
		else {
			// 내용과 버전은 그대로 두고 묶음만 닫는다
			UUID changeId = group.get().changeId();
			eventSeq = groups.closeInLock(projectId, changeId, groups.now(), request.clientMutationId());
			change.set("resource", before);
			change.put("elementVersion", current.elementVersion());
			change.put("slideVersion", current.slideVersion());
			change.put("presentationVersion", current.presentationVersion());
			change.put("generation", current.generation());
			change.put("changeId", changeId.toString());
		}

		// 10. 응답을 멱등 결과로 남긴다
		ObjectNode response = change.deepCopy();
		response.put("clientMutationId", request.clientMutationId());
		response.put("eventId", Long.toString(eventSeq));
		idempotency.complete(key, true, 200, json.writeValueAsString(response), elementId);
		return new Result(200, response, false);
	}

	private Current lockCurrent(UUID elementId) {
		return jdbc.sql("""
				SELECT e.type, e.version AS element_version, e.deleted_at IS NOT NULL AS element_deleted,
				       e.x, e.y, e.width, e.height, e.rotation, e.z_order,
				       CAST(e.content AS CHAR) AS content, CAST(e.style AS CHAR) AS style,
				       s.version AS slide_version, s.deleted_at IS NOT NULL AS slide_deleted,
				       BIN_TO_UUID(p.id) AS presentation_id, p.generation, p.version AS presentation_version
				FROM slide_elements e
				JOIN slides s ON s.id = e.slide_id
				JOIN presentations p ON p.id = s.presentation_id
				WHERE e.id = UUID_TO_BIN(:element)
				FOR UPDATE
				""")
			.param("element", elementId.toString())
			.query((rs, rowNum) -> new Current(rs.getString("type"), rs.getLong("element_version"),
					rs.getBoolean("element_deleted"),
					new Geometry(rs.getBigDecimal("x"), rs.getBigDecimal("y"), rs.getBigDecimal("width"),
							rs.getBigDecimal("height"), rs.getBigDecimal("rotation"), rs.getInt("z_order")),
					rs.getString("content"), rs.getString("style"), rs.getLong("slide_version"),
					rs.getBoolean("slide_deleted"), UUID.fromString(rs.getString("presentation_id")),
					rs.getLong("generation"), rs.getLong("presentation_version")))
			.single();
	}

	private void update(UUID elementId, UUID slideId, UUID presentationId, Geometry geometry, JsonNode content,
			JsonNode style, long elementVersion, long slideVersion, long presentationVersion) {
		jdbc.sql("""
				UPDATE slide_elements
				SET x = :x, y = :y, width = :width, height = :height, rotation = :rotation, z_order = :z,
				    content = CAST(:content AS JSON), style = CAST(:style AS JSON), version = :version
				WHERE id = UUID_TO_BIN(:element)
				""")
			.param("x", geometry.x())
			.param("y", geometry.y())
			.param("width", geometry.width())
			.param("height", geometry.height())
			.param("rotation", geometry.rotation())
			.param("z", geometry.z())
			.param("content", json.writeValueAsString(content))
			.param("style", json.writeValueAsString(style))
			.param("version", elementVersion)
			.param("element", elementId.toString())
			.update();
		jdbc.sql("UPDATE slides SET version = :version WHERE id = UUID_TO_BIN(:slide)")
			.param("version", slideVersion)
			.param("slide", slideId.toString())
			.update();
		jdbc.sql("UPDATE presentations SET version = :version WHERE id = UUID_TO_BIN(:presentation)")
			.param("version", presentationVersion)
			.param("presentation", presentationId.toString())
			.update();
	}

	// 문서는 바꾸지 않고 거절 결과만 멱등 기록에 남긴다
	private Result reject(IdempotencyStore.Key key, ErrorCode code, Current current, UUID elementId) {
		ObjectNode error = json.createObjectNode();
		error.put("code", code.name());
		error.put("message", code.message());
		error.put("retryable", code.retryable());
		ObjectNode versions = error.putObject("currentVersions");
		versions.put("generation", current.generation());
		versions.put("presentationVersion", current.presentationVersion());
		versions.put("slideVersion", current.slideVersion());
		versions.put("elementVersion", current.elementVersion());
		ObjectNode body = json.createObjectNode();
		body.set("error", error);
		idempotency.complete(key, false, code.httpStatus(), json.writeValueAsString(body), elementId);
		return new Result(code.httpStatus(), body, false);
	}

}
