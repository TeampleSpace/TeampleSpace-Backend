package com.teamplespace.presentation.service;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

import tools.jackson.databind.JsonNode;

// 요소 종류별 내용과 스타일 검사. contracts/schemas/document.schema.json과 같은 규칙이다
final class ElementRules {

	private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

	private static final Set<String> TEXT_STYLE = Set.of("fontFamily", "fontSize", "fontWeight", "color", "align",
			"lineHeight");

	private static final Set<String> SHAPE_STYLE = Set.of("fill", "stroke", "strokeWidth");

	private static final Set<String> CROP = Set.of("x", "y", "width", "height");

	private ElementRules() {
	}

	// 보내지 않은 항목은 null이다. 맞지 않으면 문서를 바꾸지 않고 거절한다
	static void check(String type, JsonNode content, JsonNode style, JsonNode currentContent) {
		switch (type) {
			case "text" -> {
				if (content != null) {
					fields(content, Set.of("text"), Set.of());
					require(content.get("text").isString());
				}
				if (style != null) {
					fields(style, TEXT_STYLE, Set.of());
					require(style.get("fontFamily").isString() && !style.get("fontFamily").asString().isEmpty());
					require(positive(style.get("fontSize")) && positive(style.get("lineHeight")));
					JsonNode weight = style.get("fontWeight");
					require(weight.isIntegralNumber() && weight.asInt() >= 100 && weight.asInt() <= 900
							&& weight.asInt() % 100 == 0);
					require(color(style.get("color")));
					require(style.get("align").isString()
							&& Set.of("left", "center", "right").contains(style.get("align").asString()));
				}
			}
			case "shape" -> {
				if (content != null) {
					fields(content, Set.of("shape"), Set.of());
					require(content.get("shape").isString()
							&& Set.of("rect", "ellipse").contains(content.get("shape").asString()));
				}
				if (style != null) {
					fields(style, SHAPE_STYLE, Set.of("cornerRadius"));
					require(style.get("fill").isNull() || color(style.get("fill")));
					require(style.get("stroke").isNull() || color(style.get("stroke")));
					require(notNegative(style.get("strokeWidth")));
					require(!style.has("cornerRadius") || notNegative(style.get("cornerRadius")));
				}
			}
			default -> {
				if (content != null) {
					fields(content, Set.of("fileObjectId", "alt"), Set.of("crop"));
					// ponytail: 이미지 파일은 바꿀 수 없다. 파일을 확정해 연결하는 경로가 생기면 교체를 연다
					require(content.get("fileObjectId").equals(currentContent.get("fileObjectId")));
					require(content.get("alt").isString());
					if (content.has("crop")) {
						JsonNode crop = content.get("crop");
						fields(crop, CROP, Set.of());
						require(ratio(crop.get("x")) && ratio(crop.get("y")));
						require(ratio(crop.get("width")) && positive(crop.get("width")));
						require(ratio(crop.get("height")) && positive(crop.get("height")));
					}
				}
				require(style == null || style.isEmpty());
			}
		}
	}

	// 필수 항목이 모두 있고 그 밖의 항목은 허용한 것뿐이어야 한다
	private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
		require(node.isObject());
		required.forEach(name -> require(node.has(name)));
		node.properties().forEach(entry -> require(required.contains(entry.getKey()) || optional.contains(entry.getKey())));
	}

	private static boolean color(JsonNode node) {
		return node.isString() && COLOR.matcher(node.asString()).matches();
	}

	private static boolean positive(JsonNode node) {
		return node.isNumber() && node.decimalValue().signum() > 0;
	}

	private static boolean notNegative(JsonNode node) {
		return node.isNumber() && node.decimalValue().signum() >= 0;
	}

	private static boolean ratio(JsonNode node) {
		return notNegative(node) && node.decimalValue().compareTo(BigDecimal.ONE) <= 0;
	}

	private static void require(boolean condition) {
		if (!condition) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

}
