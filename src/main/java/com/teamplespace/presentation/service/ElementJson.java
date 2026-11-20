package com.teamplespace.presentation.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.teamplespace.presentation.service.ElementPatchRequest.Geometry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// contracts/schemas/document.schema.json의 Element. 출처 연결은 자료 기능을 만들 때 채운다
final class ElementJson {

	private ElementJson() {
	}

	static ObjectNode of(ObjectMapper json, UUID elementId, UUID slideId, String type, long version,
			Geometry geometry, JsonNode content, JsonNode style) {
		ObjectNode element = json.createObjectNode();
		element.put("id", elementId.toString());
		element.put("slideId", slideId.toString());
		element.put("type", type);
		element.put("version", version);
		ObjectNode node = element.putObject("geometry");
		node.put("x", plain(geometry.x()));
		node.put("y", plain(geometry.y()));
		node.put("width", plain(geometry.width()));
		node.put("height", plain(geometry.height()));
		node.put("rotation", plain(geometry.rotation()));
		node.put("z", geometry.z());
		element.set("content", content);
		element.set("style", style);
		element.putArray("sourceRefs");
		return element;
	}

	// 80.0000을 8E+1이 아니라 80으로 내보낸다
	static BigDecimal plain(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

}
