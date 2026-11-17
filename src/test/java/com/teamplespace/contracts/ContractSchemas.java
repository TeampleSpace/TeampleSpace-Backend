package com.teamplespace.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

// contracts 폴더의 스키마로 JSON을 검증한다
public final class ContractSchemas {

	public static final Path CONTRACTS = Path.of("contracts");

	// 저장소 경로와 무관하게 스키마 파일 이름으로만 찾는다
	private static final String BASE = "contracts:/";

	private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
			SpecificationVersion.DRAFT_2020_12,
			builder -> builder.schemas(iri -> iri.startsWith(BASE)
					? read(CONTRACTS.resolve("schemas").resolve(iri.substring(BASE.length()))) : null));

	private ContractSchemas() {
	}

	public static List<Error> validate(String schemaRef, String input) {
		return REGISTRY.getSchema(SchemaLocation.of(BASE + schemaRef)).validate(input, InputFormat.JSON);
	}

	public static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
