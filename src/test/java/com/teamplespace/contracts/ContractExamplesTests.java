package com.teamplespace.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// contracts 폴더의 예제가 스키마와 맞는지 확인
class ContractExamplesTests {

	static final Path CONTRACTS = Path.of("contracts");
	static final Path SCHEMAS = CONTRACTS.resolve("schemas");
	static final Path EXAMPLES = CONTRACTS.resolve("examples");

	final ObjectMapper json = new ObjectMapper();
	// 저장소 경로와 무관하게 스키마 파일 이름으로만 찾는다
	static final String BASE = "contracts:/";

	final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
			builder -> builder.schemas(iri -> iri.startsWith(BASE) ? read(SCHEMAS.resolve(iri.substring(BASE.length()))) : null));

	@TestFactory
	Stream<DynamicTest> examplesMatchManifest() throws IOException {
		JsonNode manifest = json.readTree(Files.readString(EXAMPLES.resolve("manifest.json")));
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonNode entry : manifest) {
			String example = entry.get("example").asString();
			String schemaRef = entry.get("schema").asString();
			boolean valid = entry.get("valid").asBoolean();
			tests.add(DynamicTest.dynamicTest(example, () -> {
				List<Error> errors = validate(schemaRef, Files.readString(EXAMPLES.resolve(example)));
				if (valid) {
					assertThat(errors).as(example).isEmpty();
				}
				else {
					assertThat(errors).as(example).isNotEmpty();
				}
			}));
		}
		return tests.stream();
	}

	@Test
	void everyExampleIsListedInManifest() throws IOException {
		JsonNode manifest = json.readTree(Files.readString(EXAMPLES.resolve("manifest.json")));
		Set<String> listed = new HashSet<>();
		manifest.forEach(entry -> listed.add(entry.get("example").asString()));
		try (Stream<Path> files = Files.walk(EXAMPLES)) {
			Set<String> actual = new HashSet<>();
			files.filter(path -> path.toString().endsWith(".json"))
				.map(path -> EXAMPLES.relativize(path).toString().replace('\\', '/'))
				.filter(name -> !name.equals("manifest.json"))
				.forEach(actual::add);
			assertThat(actual).isEqualTo(listed);
		}
	}

	// 스키마로 표현할 수 없는 문서 안의 참조 관계
	@Test
	void presentationExampleKeepsReferencesConsistent() throws IOException {
		JsonNode presentation = json.readTree(Files.readString(EXAMPLES.resolve("presentation.json")));
		List<String> slideIds = new ArrayList<>();
		presentation.get("slideIds").forEach(id -> slideIds.add(id.asString()));
		List<String> actualSlideIds = new ArrayList<>();
		for (JsonNode slide : presentation.get("slides")) {
			actualSlideIds.add(slide.get("id").asString());
			assertThat(slide.get("presentationId").asString()).isEqualTo(presentation.get("id").asString());
			assertThat(slide.get("generation").asLong()).isEqualTo(presentation.get("generation").asLong());
			List<String> elementIds = new ArrayList<>();
			slide.get("elementIds").forEach(id -> elementIds.add(id.asString()));
			List<String> actualElementIds = new ArrayList<>();
			for (JsonNode element : slide.get("elements")) {
				actualElementIds.add(element.get("id").asString());
				assertThat(element.get("slideId").asString()).isEqualTo(slide.get("id").asString());
				if (element.get("type").asString().equals("image")) {
					Path asset = EXAMPLES.resolve("assets")
						.resolve(element.get("content").get("fileObjectId").asString() + ".png");
					assertThat(asset).exists();
				}
			}
			assertThat(actualElementIds).isEqualTo(elementIds);
		}
		assertThat(actualSlideIds).isEqualTo(slideIds);
	}

	@Test
	void errorExamplesUseListedCodes() throws IOException {
		Set<String> codes = new HashSet<>();
		json.readTree(Files.readString(CONTRACTS.resolve("error-codes.json")))
			.get("codes")
			.forEach(code -> codes.add(code.get("code").asString()));
		try (Stream<Path> files = Files.list(EXAMPLES)) {
			for (Path file : files.filter(path -> path.getFileName().toString().startsWith("error-")).toList()) {
				String code = json.readTree(Files.readString(file)).get("error").get("code").asString();
				assertThat(codes).as(file.getFileName().toString()).contains(code);
			}
		}
	}

	List<Error> validate(String schemaRef, String input) {
		Schema schema = registry.getSchema(SchemaLocation.of(BASE + schemaRef));
		return schema.validate(input, InputFormat.JSON);
	}

	static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
