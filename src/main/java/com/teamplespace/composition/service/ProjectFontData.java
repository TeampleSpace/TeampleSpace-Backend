package com.teamplespace.composition.service;

import static com.teamplespace.composition.service.OutlineInput.invalid;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import com.teamplespace.platform.error.ApiException;

final class ProjectFontData {

	private static final int TTF_SIGNATURE = 0x00010000;
	private static final int OTF_SIGNATURE = 0x4f54544f;
	private static final int HEAD_MAGIC = 0x5f0f3cf5;
	private static final int HEADER_SIZE = 12;
	private static final int RECORD_SIZE = 16;
	private static final Map<String, Integer> REQUIRED = Map.of(
			"head", 54, "cmap", 4, "hhea", 36, "hmtx", 4,
			"maxp", 6, "name", 6, "OS/2", 78, "post", 32);

	private ProjectFontData() {
	}

	private record Table(int offset, int length) {
		int end() {
			return offset + length;
		}
	}

	static String format(byte[] bytes) {
		if (bytes.length < HEADER_SIZE) throw invalidFont();
		ByteBuffer font = ByteBuffer.wrap(bytes);
		int signature = font.getInt(0);
		if (signature != TTF_SIGNATURE && signature != OTF_SIGNATURE) throw invalidFont();
		int count = Short.toUnsignedInt(font.getShort(4));
		int directoryEnd = HEADER_SIZE + count * RECORD_SIZE;
		if (directoryEnd > bytes.length) throw invalidFont();

		Map<String, Table> tables = new HashMap<>();
		for (int record = HEADER_SIZE; record < directoryEnd; record += RECORD_SIZE) {
			String name = new String(bytes, record, 4, StandardCharsets.US_ASCII);
			long offset = Integer.toUnsignedLong(font.getInt(record + 8));
			long length = Integer.toUnsignedLong(font.getInt(record + 12));
			if (offset < directoryEnd || offset % 4 != 0 || offset + length > bytes.length
					|| tables.putIfAbsent(name, new Table((int) offset, (int) length)) != null) {
				throw invalidFont();
			}
		}
		int end = directoryEnd;
		for (Table table : tables.values().stream().filter(item -> item.length() > 0)
				.sorted(Comparator.comparingInt(Table::offset)).toList()) {
			if (table.offset() < end) throw invalidFont();
			end = table.end();
		}
		for (var requirement : REQUIRED.entrySet()) {
			if (!has(tables, requirement.getKey(), requirement.getValue())) throw invalidFont();
		}
		int head = tables.get("head").offset();
		int unitsPerEm = unsignedShort(font, head + 18);
		int glyphs = unsignedShort(font, tables.get("maxp").offset() + 4);
		int metrics = unsignedShort(font, tables.get("hhea").offset() + 34);
		Table map = tables.get("cmap");
		int maps = unsignedShort(font, map.offset() + 2);
		if (font.getInt(head + 12) != HEAD_MAGIC || unitsPerEm < 16 || unitsPerEm > 16384
				|| metrics == 0 || metrics > glyphs || !has(tables, "hmtx", metrics * 4 + (glyphs - metrics) * 2)
				|| unsignedShort(font, map.offset()) != 0 || maps == 0 || map.length() < 6 + maps * 8) {
			throw invalidFont();
		}
		int directoryLength = 4 + maps * 8;
		for (int index = 0; index < maps; index++) {
			long relative = Integer.toUnsignedLong(font.getInt(map.offset() + 8 + index * 8));
			if (relative < directoryLength || relative + 2 > map.length()) throw invalidFont();
		}
		if (signature == TTF_SIGNATURE) {
			if (!has(tables, "glyf", 1) || !has(tables, "loca", 4)) throw invalidFont();
			return "ttf";
		}
		if (!has(tables, "CFF ", 4) && !has(tables, "CFF2", 5)) throw invalidFont();
		return "otf";
	}

	private static boolean has(Map<String, Table> tables, String name, int minimum) {
		Table table = tables.get(name);
		return table != null && table.length() >= minimum;
	}

	private static int unsignedShort(ByteBuffer value, int offset) {
		return Short.toUnsignedInt(value.getShort(offset));
	}

	private static ApiException invalidFont() {
		return invalid("file", "올바른 TTF 또는 OTF 글꼴 파일을 선택해 주세요.");
	}
}
