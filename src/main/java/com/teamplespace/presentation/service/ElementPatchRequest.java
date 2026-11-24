package com.teamplespace.presentation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

import tools.jackson.databind.JsonNode;

// contracts/schemas/api.schema.json의 ElementPatchRequest
public record ElementPatchRequest(long generation, long baseVersion, UUID editGroupId, String clientMutationId,
		boolean finalizeGroup, boolean hasPatch, Geometry geometry, JsonNode content, JsonNode style) {

	public record Geometry(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height, BigDecimal rotation,
			int z) {
	}

	private static final Set<String> FIELDS = Set.of("generation", "baseVersion", "editGroupId", "clientMutationId",
			"finalizeGroup", "patch");

	// 출처 연결 수정은 자료 기능을 만들 때 받는다
	private static final Set<String> PATCH_FIELDS = Set.of("geometry", "content", "style");

	private static final Set<String> GEOMETRY_FIELDS = Set.of("x", "y", "width", "height", "rotation", "z");

	private static final BigDecimal COORDINATE_LIMIT = new BigDecimal("100000000");

	private static final BigDecimal ROTATION_LIMIT = new BigDecimal("10000");

	public static ElementPatchRequest parse(JsonNode body) {
		requireObject(body, FIELDS);
		JsonNode finalizeNode = body.get("finalizeGroup");
		require(finalizeNode == null || finalizeNode.isBoolean());
		boolean finalizeGroup = finalizeNode != null && finalizeNode.asBoolean();
		String clientMutationId = string(body.get("clientMutationId"));
		require(!clientMutationId.isEmpty() && clientMutationId.length() <= 64);
		long generation = version(body.get("generation"));
		long baseVersion = version(body.get("baseVersion"));
		UUID editGroupId = uuid(body.get("editGroupId"));
		JsonNode patch = body.get("patch");
		if (patch == null) {
			// 묶음만 닫는 요청은 patch를 생략할 수 있다
			require(finalizeGroup);
			return new ElementPatchRequest(generation, baseVersion, editGroupId, clientMutationId, true, false, null,
					null, null);
		}
		requireObject(patch, PATCH_FIELDS);
		require(!patch.isEmpty());
		JsonNode content = patch.get("content");
		JsonNode style = patch.get("style");
		require(content == null || content.isObject());
		require(style == null || style.isObject());
		return new ElementPatchRequest(generation, baseVersion, editGroupId, clientMutationId, finalizeGroup, true,
				geometry(patch.get("geometry")), content, style);
	}

	// 아래 검사는 같은 패키지의 다른 저장 명령도 쓴다
	static Geometry geometry(JsonNode node) {
		if (node == null) {
			return null;
		}
		requireObject(node, GEOMETRY_FIELDS);
		require(node.size() == GEOMETRY_FIELDS.size());
		JsonNode z = node.get("z");
		require(z.isIntegralNumber() && z.canConvertToInt());
		Geometry geometry = new Geometry(decimal(node.get("x"), COORDINATE_LIMIT),
				decimal(node.get("y"), COORDINATE_LIMIT), decimal(node.get("width"), COORDINATE_LIMIT),
				decimal(node.get("height"), COORDINATE_LIMIT), decimal(node.get("rotation"), ROTATION_LIMIT),
				z.asInt());
		require(geometry.width().signum() > 0 && geometry.height().signum() > 0);
		return geometry;
	}

	// DB와 같은 소수 넷째 자리로 맞춘다
	private static BigDecimal decimal(JsonNode node, BigDecimal limit) {
		require(node != null && node.isNumber());
		BigDecimal value = node.decimalValue().setScale(4, RoundingMode.HALF_UP);
		require(value.abs().compareTo(limit) < 0);
		return value;
	}

	static long version(JsonNode node) {
		require(node != null && node.isIntegralNumber() && node.canConvertToLong());
		long value = node.asLong();
		require(value >= 1 && value <= 9007199254740991L);
		return value;
	}

	static UUID uuid(JsonNode node) {
		try {
			return UUID.fromString(string(node));
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

	static String string(JsonNode node) {
		require(node != null && node.isString());
		return node.asString();
	}

	static void requireObject(JsonNode node, Set<String> allowed) {
		require(node != null && node.isObject());
		node.properties().forEach(entry -> require(allowed.contains(entry.getKey())));
	}

	static void require(boolean condition) {
		if (!condition) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

}
