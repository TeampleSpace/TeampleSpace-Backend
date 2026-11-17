package com.teamplespace.platform.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

// 키 순서와 숫자 표기가 달라도 같은 내용이면 같은 문자열을 만든다
public final class CanonicalJson {

	private CanonicalJson() {
	}

	public static String write(JsonNode node) {
		StringBuilder out = new StringBuilder();
		append(node, out);
		return out.toString();
	}

	public static byte[] sha256(JsonNode node) {
		return sha256(write(node));
	}

	public static byte[] sha256(String text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void append(JsonNode node, StringBuilder out) {
		if (node.isObject()) {
			TreeMap<String, JsonNode> sorted = new TreeMap<>();
			node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
			out.append('{');
			String separator = "";
			for (var entry : sorted.entrySet()) {
				out.append(separator).append(JsonNodeFactory.instance.stringNode(entry.getKey())).append(':');
				append(entry.getValue(), out);
				separator = ",";
			}
			out.append('}');
		}
		else if (node.isArray()) {
			out.append('[');
			String separator = "";
			for (JsonNode item : node) {
				out.append(separator);
				append(item, out);
				separator = ",";
			}
			out.append(']');
		}
		else if (node.isNumber()) {
			out.append(node.decimalValue().stripTrailingZeros().toPlainString());
		}
		else {
			out.append(node);
		}
	}

}
