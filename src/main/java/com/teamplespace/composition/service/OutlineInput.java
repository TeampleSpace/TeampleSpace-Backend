package com.teamplespace.composition.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

import tools.jackson.databind.JsonNode;

// 구성과 발표 생성 요청이 함께 쓰는 입력 검사
public record OutlineInput(long expectedVersion, List<Item> items) {
	static final long MAX_VERSION = 9_007_199_254_740_991L;
	static final int MAX_ITEMS = 100;
	static final int MAX_SOURCES = 20;
	private static final Set<String> ROOT_FIELDS = Set.of("expectedVersion", "items");
	private static final Set<String> ITEM_FIELDS = Set.of("id", "title", "summary", "sourceIds");

	record Item(UUID id, String title, String summary, List<UUID> sourceIds) {
	}

	static OutlineInput parse(JsonNode body) {
		checkFields(body, ROOT_FIELDS, "outline");
		long version = expectedVersion(body);
		JsonNode items = body.path("items");
		if (!items.isArray() || items.size() > MAX_ITEMS) {
			throw invalid("items", "구성 항목은 100개까지 저장할 수 있어요.");
		}
		List<Item> parsed = new ArrayList<>();
		Set<UUID> itemIds = new HashSet<>();
		for (int index = 0; index < items.size(); index++) {
			JsonNode item = items.get(index);
			String field = "items[" + index + "]";
			checkFields(item, ITEM_FIELDS, field);
			UUID id = item.has("id") ? uuid(item.get("id"), field + ".id") : null;
			if (id != null && !itemIds.add(id)) {
				throw invalid(field + ".id", "같은 항목을 두 번 넣을 수 없어요.");
			}
			String title = text(item.path("title"), field + ".title", 255).strip();
			if (title.isEmpty()) {
				throw invalid(field + ".title", "항목 제목을 입력해 주세요.");
			}
			String summary = item.has("summary") ? text(item.get("summary"), field + ".summary", 10_000) : "";
			parsed.add(new Item(id, title, summary, sources(item.path("sourceIds"), field + ".sourceIds")));
		}
		return new OutlineInput(version, List.copyOf(parsed));
	}

	static long expectedVersion(JsonNode body) {
		return version(body.path("expectedVersion"), "expectedVersion");
	}

	public static long version(JsonNode version, String field) {
		if (!version.isIntegralNumber() || !version.canConvertToLong()
				|| version.asLong() < 1 || version.asLong() > MAX_VERSION) {
			throw invalid(field, "버전을 확인해 주세요.");
		}
		return version.asLong();
	}

	public static List<UUID> sources(JsonNode value, String field) {
		if (!value.isArray() || value.size() > MAX_SOURCES) {
			throw invalid(field, "한 항목에 근거 자료를 20개까지 연결할 수 있어요.");
		}
		List<UUID> ids = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (int index = 0; index < value.size(); index++) {
			UUID id = uuid(value.get(index), field + "[" + index + "]");
			if (!seen.add(id)) {
				throw invalid(field, "같은 자료를 한 항목에 두 번 연결할 수 없어요.");
			}
			ids.add(id);
		}
		return List.copyOf(ids);
	}

	public static void checkFields(JsonNode value, Set<String> allowed, String field) {
		if (!value.isObject() || !allowed.containsAll(value.propertyNames())) {
			throw invalid(field, "요청 항목을 확인해 주세요.");
		}
	}

	static UUID uuid(JsonNode node, String field) {
		if (node.isString()) {
			String value = node.asString();
			try {
				UUID id = UUID.fromString(value);
				if (id.toString().equalsIgnoreCase(value)) {
					return id;
				}
			}
			catch (IllegalArgumentException ignored) {
			}
		}
		throw invalid(field, "식별자를 확인해 주세요.");
	}

	public static String text(JsonNode node, String field, int max) {
		if (!node.isString()) {
			throw invalid(field, "입력 형식과 글자 수를 확인해 주세요.");
		}
		String value = node.asString();
		if (value.codePointCount(0, value.length()) > max) {
			throw invalid(field, "입력 형식과 글자 수를 확인해 주세요.");
		}
		return value;
	}

	public static ApiException invalid(String field, String message) {
		return ApiException.field(ErrorCode.VALIDATION_FAILED, field, "INVALID_VALUE", message);
	}
}
