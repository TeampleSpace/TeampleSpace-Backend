package com.teamplespace.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

// 슬라이드와 요소의 추가, 삭제, 순서 변경을 실제 HTTP로 확인한다
@TestPropertySource(properties = "teamplespace.editor.sample-presentation-enabled=true")
class DocumentCommandHttpTests extends HttpIntegrationSupport {

	@Test
	void slidesAreAddedReorderedAndDeletedWithOrderVersionCheck() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		String project = createProject(owner, "슬라이드 목록");
		join(owner, member, project);
		JsonNode start = presentation(owner, project);
		String presentationId = start.get("id").asString();
		List<String> order = ids(start.get("slideIds"));

		// 첫 슬라이드 뒤에 새 슬라이드를 넣는다
		String created = UUID.randomUUID().toString();
		ObjectNode create = command(start);
		create.put("slideId", created);
		create.put("afterSlideId", order.get(0));
		create.put("layoutId", "content");
		assertValid("SlideCreateRequest", create);
		String createKey = UUID.randomUUID().toString();
		JsonNode added = owner.request("POST", "/presentations/" + presentationId + "/slides", create, 200, createKey);
		assertValid("MutationResponse", added);
		order.add(1, created);
		assertThat(ids(added.get("slideIds"))).isEqualTo(order);
		assertThat(added.get("resource").get("version").asLong()).isEqualTo(1);
		assertThat(added.get("orderVersion").asLong()).isEqualTo(2);
		assertThat(added.get("presentationVersion").asLong()).isEqualTo(start.get("version").asLong() + 1);
		// 응답을 못 받은 것으로 보고 같은 요청을 다시 보내도 한 번만 반영된다
		assertThat(owner.request("POST", "/presentations/" + presentationId + "/slides", create, 200, createKey))
			.isEqualTo(added);
		JsonNode afterCreate = presentation(member, project);
		assertThat(ids(afterCreate.get("slideIds"))).isEqualTo(order);
		assertThat(afterCreate.get("orderVersion").asLong()).isEqualTo(2);

		// 예전 순서 버전에서 보낸 변경은 덮어쓰지 않는다
		ObjectNode stale = command(start);
		stale.set("slideIds", json.valueToTree(order));
		JsonNode conflict = member.request("PUT", "/presentations/" + presentationId + "/slide-order", stale, 409,
				UUID.randomUUID().toString());
		assertValid("ErrorResponse", conflict);
		assertThat(conflict.get("error").get("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(conflict.get("error").get("currentVersions").get("orderVersion").asLong()).isEqualTo(2);

		// 순서를 뒤집는다
		List<String> reversed = new ArrayList<>(order);
		Collections.reverse(reversed);
		ObjectNode reorder = command(afterCreate);
		reorder.set("slideIds", json.valueToTree(reversed));
		assertValid("SlideOrderRequest", reorder);
		JsonNode reordered = member.request("PUT", "/presentations/" + presentationId + "/slide-order", reorder, 200,
				UUID.randomUUID().toString());
		assertValid("MutationResponse", reordered);
		assertThat(ids(reordered.get("slideIds"))).isEqualTo(reversed);
		JsonNode afterOrder = presentation(owner, project);
		assertThat(ids(afterOrder.get("slideIds"))).isEqualTo(reversed);
		assertThat(ids(afterOrder.get("slides"), "id")).isEqualTo(reversed);

		// 빠지거나 겹친 순서는 받지 않는다
		for (List<String> wrong : List.of(reversed.subList(1, reversed.size()),
				List.of(reversed.get(0), reversed.get(0), reversed.get(1), reversed.get(2)))) {
			ObjectNode invalid = command(afterOrder);
			invalid.set("slideIds", json.valueToTree(wrong));
			owner.request("PUT", "/presentations/" + presentationId + "/slide-order", invalid, 422,
					UUID.randomUUID().toString());
		}

		// 요소가 있는 슬라이드를 지우면 스냅샷에서 빠지고 그 요소는 더 저장할 수 없다
		JsonNode doomed = afterOrder.get("slides").get(reversed.indexOf(order.get(0)));
		JsonNode title = doomed.get("elements").get(1);
		ObjectNode delete = command(afterOrder);
		assertValid("SlideDeleteRequest", delete);
		String deleteKey = UUID.randomUUID().toString();
		JsonNode deleted = owner.request("DELETE", "/slides/" + order.get(0), delete, 200, deleteKey);
		assertValid("MutationResponse", deleted);
		assertThat(deleted.get("tombstone").get("resourceType").asString()).isEqualTo("slide");
		assertThat(deleted.get("tombstone").get("version").asLong()).isEqualTo(doomed.get("version").asLong() + 1);
		assertThat(owner.request("DELETE", "/slides/" + order.get(0), delete, 200, deleteKey)).isEqualTo(deleted);
		reversed.remove(order.get(0));
		JsonNode afterDelete = presentation(owner, project);
		assertThat(ids(afterDelete.get("slideIds"))).isEqualTo(reversed);
		assertThat(ContractSchemas.validate("document.schema.json", afterDelete.toString())).isEmpty();
		ObjectNode patch = json.createObjectNode();
		patch.put("generation", 1);
		patch.put("baseVersion", title.get("version").asLong());
		patch.put("editGroupId", UUID.randomUUID().toString());
		patch.put("clientMutationId", UUID.randomUUID().toString());
		patch.putObject("patch").putObject("content").put("text", "지워진 슬라이드의 글");
		owner.request("PATCH", "/slides/" + order.get(0) + "/elements/" + title.get("id").asString(), patch, 409,
				UUID.randomUUID().toString());
		// 이미 지운 슬라이드를 새 요청으로 다시 지울 수 없다
		owner.request("DELETE", "/slides/" + order.get(0), command(afterDelete), 409, UUID.randomUUID().toString());

		// 마지막 하나는 남긴다
		JsonNode current = afterDelete;
		while (current.get("slideIds").size() > 1) {
			owner.request("DELETE", "/slides/" + current.get("slideIds").get(0).asString(), command(current), 200,
					UUID.randomUUID().toString());
			current = presentation(owner, project);
		}
		owner.request("DELETE", "/slides/" + current.get("slideIds").get(0).asString(), command(current), 422,
				UUID.randomUUID().toString());
		assertThat(presentation(owner, project).get("slideIds")).hasSize(1);
	}

	@Test
	void elementsAreAddedAndDeletedWithVersionCheck() throws Exception {
		Browser owner = signup("소유자");
		String project = createProject(owner, "요소");
		JsonNode start = presentation(owner, project);
		JsonNode slide = start.get("slides").get(1);
		String slideId = slide.get("id").asString();
		String path = "/slides/" + slideId + "/elements";

		ObjectNode create = json.createObjectNode();
		create.put("generation", 1);
		create.put("clientMutationId", UUID.randomUUID().toString());
		String elementId = UUID.randomUUID().toString();
		create.set("element", json.readTree("""
				{"id":"%s","type":"text","geometry":{"x":80,"y":420,"width":600,"height":80,"rotation":0,"z":9},
				 "content":{"text":"새 글상자"},
				 "style":{"fontFamily":"Pretendard","fontSize":24,"fontWeight":400,"color":"#1F2933","align":"left",
				          "lineHeight":1.4}}
				""".formatted(elementId)));
		assertValid("ElementCreateRequest", create);
		String createKey = UUID.randomUUID().toString();
		JsonNode added = owner.request("POST", path, create, 200, createKey);
		assertValid("MutationResponse", added);
		assertThat(added.get("resource").get("slideId").asString()).isEqualTo(slideId);
		assertThat(added.get("elementVersion").asLong()).isEqualTo(1);
		assertThat(added.get("slideVersion").asLong()).isEqualTo(slide.get("version").asLong() + 1);
		assertThat(owner.request("POST", path, create, 200, createKey)).isEqualTo(added);
		JsonNode afterCreate = presentation(owner, project).get("slides").get(1);
		assertThat(ids(afterCreate.get("elementIds"))).contains(elementId).hasSize(slide.get("elements").size() + 1);
		assertThat(afterCreate.get("version").asLong()).isEqualTo(slide.get("version").asLong() + 1);

		// 같은 식별자, 종류에 맞지 않는 내용, 이미지는 받지 않는다
		owner.request("POST", path, create, 422, UUID.randomUUID().toString());
		for (String wrong : List.of("{\"content\":{}}", "{\"type\":\"image\"}", "{\"style\":{\"fill\":\"#FFFFFF\"}}")) {
			ObjectNode invalid = create.deepCopy();
			invalid.put("clientMutationId", UUID.randomUUID().toString());
			ObjectNode element = (ObjectNode) invalid.get("element");
			element.put("id", UUID.randomUUID().toString());
			element.setAll((ObjectNode) json.readTree(wrong));
			owner.request("POST", path, invalid, 422, UUID.randomUUID().toString());
		}
		assertThat(presentation(owner, project).get("slides").get(1)).isEqualTo(afterCreate);

		// 예전 버전에서 보낸 삭제는 거절한다
		ObjectNode delete = json.createObjectNode();
		delete.put("generation", 1);
		delete.put("baseVersion", 5);
		delete.put("clientMutationId", UUID.randomUUID().toString());
		JsonNode conflict = owner.request("DELETE", path + "/" + elementId, delete, 409, UUID.randomUUID().toString());
		assertThat(conflict.get("error").get("currentVersions").get("elementVersion").asLong()).isEqualTo(1);

		delete.put("baseVersion", 1);
		assertValid("ElementDeleteRequest", delete);
		String deleteKey = UUID.randomUUID().toString();
		JsonNode deleted = owner.request("DELETE", path + "/" + elementId, delete, 200, deleteKey);
		assertValid("MutationResponse", deleted);
		assertThat(deleted.get("tombstone").get("resourceType").asString()).isEqualTo("element");
		assertThat(deleted.get("elementVersion").asLong()).isEqualTo(2);
		assertThat(owner.request("DELETE", path + "/" + elementId, delete, 200, deleteKey)).isEqualTo(deleted);
		JsonNode afterDelete = presentation(owner, project).get("slides").get(1);
		assertThat(ids(afterDelete.get("elementIds"))).doesNotContain(elementId);
		assertThat(ContractSchemas.validate("document.schema.json", presentation(owner, project).toString())).isEmpty();

		// 지운 요소는 고치거나 다시 지울 수 없다
		ObjectNode patch = json.createObjectNode();
		patch.put("generation", 1);
		patch.put("baseVersion", 2);
		patch.put("editGroupId", UUID.randomUUID().toString());
		patch.put("clientMutationId", UUID.randomUUID().toString());
		patch.putObject("patch").putObject("content").put("text", "지운 요소의 글");
		owner.request("PATCH", path + "/" + elementId, patch, 409, UUID.randomUUID().toString());
		delete.put("baseVersion", 2);
		owner.request("DELETE", path + "/" + elementId, delete, 409, UUID.randomUUID().toString());
	}

	@Test
	void structureChangesArriveAsEventsAndOutsidersAreRejected() throws Exception {
		Browser owner = signup("소유자");
		Browser member = signup("팀원");
		Browser stranger = signup("외부인");
		String project = createProject(owner, "구조 이벤트");
		join(owner, member, project);
		JsonNode start = presentation(owner, project);
		String presentationId = start.get("id").asString();
		String cursor = owner.get("/projects/" + project + "/sync-snapshot?scope=editor").get("eventCursor").asString();
		List<String> order = ids(start.get("slideIds"));

		ObjectNode create = command(start);
		String created = UUID.randomUUID().toString();
		create.put("slideId", created);
		owner.request("POST", "/presentations/" + presentationId + "/slides", create, 200, UUID.randomUUID().toString());
		ObjectNode element = json.createObjectNode();
		element.put("generation", 1);
		element.put("clientMutationId", UUID.randomUUID().toString());
		String elementId = UUID.randomUUID().toString();
		element.set("element", json.readTree("""
				{"id":"%s","type":"shape","geometry":{"x":10,"y":10,"width":100,"height":100,"rotation":0,"z":0},
				 "content":{"shape":"rect"},"style":{"fill":"#E8F1FA","stroke":null,"strokeWidth":0}}
				""".formatted(elementId)));
		owner.request("POST", "/slides/" + created + "/elements", element, 200, UUID.randomUUID().toString());
		ObjectNode deleteElement = json.createObjectNode();
		deleteElement.put("generation", 1);
		deleteElement.put("baseVersion", 1);
		deleteElement.put("clientMutationId", UUID.randomUUID().toString());
		owner.request("DELETE", "/slides/" + created + "/elements/" + elementId, deleteElement, 200,
				UUID.randomUUID().toString());
		JsonNode afterCreate = presentation(owner, project);
		ObjectNode reorder = command(afterCreate);
		List<String> moved = new ArrayList<>(order);
		moved.add(created);
		reorder.set("slideIds", json.valueToTree(moved));
		owner.request("PUT", "/presentations/" + presentationId + "/slide-order", reorder, 200,
				UUID.randomUUID().toString());
		JsonNode last = owner.request("DELETE", "/slides/" + created, command(presentation(owner, project)), 200,
				UUID.randomUUID().toString());

		// 다른 팀원은 스냅샷 커서 뒤의 이벤트로 같은 변경을 순서대로 받는다
		HttpResponse<Stream<String>> stream = member.lines("/projects/" + project + "/events",
				Map.of("Last-Event-ID", cursor));
		List<Map<String, String>> received = messages(stream, last.get("eventId").asString());
		assertThat(received).extracting(message -> message.get("event"))
			.containsExactly("slide.created", "element.updated", "element.deleted", "slide.order.updated",
					"slide.deleted");
		for (Map<String, String> message : received) {
			assertThat(ContractSchemas.validate("api.schema.json#/$defs/Event", message.get("data")))
				.as(message.get("event"))
				.isEmpty();
		}
		JsonNode orderEvent = json.readTree(received.get(3).get("data"));
		assertThat(ids(orderEvent.get("payload").get("slideIds"))).isEqualTo(moved);
		assertThat(orderEvent.get("presentationVersion").asLong())
			.isEqualTo(json.readTree(received.get(2).get("data")).get("presentationVersion").asLong() + 1);

		// 참여한 적 없는 사용자와 제거된 팀원은 쓸 수 없다
		removeMember(owner, project, member.userId);
		JsonNode current = presentation(owner, project);
		String slideId = current.get("slideIds").get(0).asString();
		JsonNode target = current.get("slides").get(0).get("elements").get(0);
		for (Browser blocked : List.of(stranger, member)) {
			int status = blocked == stranger ? 404 : 403;
			ObjectNode slide = command(current);
			slide.put("slideId", UUID.randomUUID().toString());
			blocked.request("POST", "/presentations/" + presentationId + "/slides", slide, status,
					UUID.randomUUID().toString());
			blocked.request("DELETE", "/slides/" + slideId, command(current), status, UUID.randomUUID().toString());
			ObjectNode same = command(current);
			same.set("slideIds", current.get("slideIds"));
			blocked.request("PUT", "/presentations/" + presentationId + "/slide-order", same, status,
					UUID.randomUUID().toString());
			ObjectNode again = element.deepCopy();
			((ObjectNode) again.get("element")).put("id", UUID.randomUUID().toString());
			blocked.request("POST", "/slides/" + slideId + "/elements", again, status, UUID.randomUUID().toString());
			blocked.request("DELETE", "/slides/" + slideId + "/elements/" + target.get("id").asString(), deleteElement,
					status, UUID.randomUUID().toString());
		}
		assertThat(presentation(owner, project)).isEqualTo(current);
		owner.request("POST", "/presentations/" + presentationId + "/slides", create, 400, null);
	}

	// 예제 문서를 만들고 문서를 읽는다
	private JsonNode presentation(Browser browser, String project) throws Exception {
		browser.post("/projects/" + project + "/sample-presentation", null, 204);
		return browser.get("/projects/" + project + "/sync-snapshot?scope=editor").get("presentation");
	}

	// 슬라이드 목록을 바꾸는 요청의 공통 항목
	private ObjectNode command(JsonNode presentation) {
		ObjectNode body = json.createObjectNode();
		body.put("generation", presentation.get("generation").asLong());
		body.put("orderVersion", presentation.get("orderVersion").asLong());
		body.put("clientMutationId", UUID.randomUUID().toString());
		return body;
	}

	private List<String> ids(JsonNode array) {
		List<String> ids = new ArrayList<>();
		array.forEach(id -> ids.add(id.asString()));
		return ids;
	}

	private List<String> ids(JsonNode array, String field) {
		List<String> ids = new ArrayList<>();
		array.forEach(item -> ids.add(item.get(field).asString()));
		return ids;
	}

	private void assertValid(String definition, JsonNode value) {
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/" + definition, value.toString()))
			.as(definition + " " + value)
			.isEmpty();
	}

}
