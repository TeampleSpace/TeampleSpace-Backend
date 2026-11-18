package com.teamplespace.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 요청 식별자와 공통 오류 응답을 HTTP 계층에서 확인
// 운영 경로가 아직 없어 테스트 전용 경로로 확인한다
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiErrorResponseTests.ProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorResponseTests {

	// 테스트 소스에만 있어 운영 실행에는 포함되지 않는다
	@RestController
	static class ProbeController {

		private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

		@GetMapping("/test-probe/ok")
		String ok() {
			log.info("요청을 처리함");
			return "ok";
		}

		@GetMapping("/test-probe/conflict")
		String conflict() {
			throw new ApiException(ErrorCode.VERSION_CONFLICT);
		}

		@GetMapping("/test-probe/in-progress")
		String inProgress() {
			throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS, 1);
		}

		@GetMapping("/test-probe/broken")
		String broken() {
			throw new IllegalStateException("내부 비밀 정보");
		}

		@PostMapping("/test-probe/echo")
		Map<String, Object> echo(@RequestBody Map<String, Object> body) {
			return body;
		}

		@GetMapping("/test-probe/page")
		Map<String, Object> page(@RequestParam int size) {
			return Map.of("size", size);
		}

	}

	@Autowired
	MockMvc mvc;

	final ObjectMapper json = new ObjectMapper();

	@Test
	void everyRequestGetsItsOwnIdInHeaderAndLog(CapturedOutput output) throws Exception {
		MockHttpServletResponse first = mvc.perform(get("/test-probe/ok")).andReturn().getResponse();
		MockHttpServletResponse second = mvc.perform(get("/test-probe/ok")).andReturn().getResponse();

		String firstId = first.getHeader(RequestIdFilter.HEADER);
		String secondId = second.getHeader(RequestIdFilter.HEADER);
		assertThat(first.getStatus()).isEqualTo(200);
		assertThat(firstId).matches("req_[0-9a-f]{32}");
		assertThat(secondId).matches("req_[0-9a-f]{32}").isNotEqualTo(firstId);
		// 로그 줄에 같은 식별자가 남는다
		assertThat(output.getOut().lines().filter(line -> line.contains("요청을 처리함")))
			.anyMatch(line -> line.contains("[" + firstId + "]"))
			.anyMatch(line -> line.contains("[" + secondId + "]"));
	}

	@Test
	void apiErrorUsesTheSharedFormatWithTheSameRequestId() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/test-probe/conflict")).andReturn().getResponse();

		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(body.get("error").get("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(body.get("error").get("retryable").asBoolean()).isFalse();
		assertThat(body.get("requestId").asString()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
		assertThat(response.getHeader("Retry-After")).isNull();
	}

	@Test
	void retryableErrorCarriesRetryAfter() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/test-probe/in-progress")).andReturn().getResponse();

		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(body.get("error").get("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
		assertThat(body.get("error").get("retryable").asBoolean()).isTrue();
		assertThat(response.getHeader("Retry-After")).isEqualTo("1");
	}

	@Test
	void unexpectedErrorHidesInternalDetails(CapturedOutput output) throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/test-probe/broken")).andReturn().getResponse();

		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(body.get("error").get("code").asString()).isEqualTo("INTERNAL_ERROR");
		assertThat(response.getContentAsString()).doesNotContain("내부 비밀 정보").doesNotContain("IllegalStateException");
		// 원인은 같은 요청 식별자와 함께 서버 로그에만 남는다
		String requestId = response.getHeader(RequestIdFilter.HEADER);
		assertThat(output.getOut().lines().filter(line -> line.contains("처리하지 못한 오류")))
			.anyMatch(line -> line.contains("[" + requestId + "]"));
		assertThat(output.getOut()).contains("내부 비밀 정보");
	}

	@Test
	void unknownPathUsesTheSharedFormat() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/no-such-path")).andReturn().getResponse();

		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(body.get("error").get("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(body.get("requestId").asString()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
	}

	@Test
	void malformedJsonKeepsItsStatusAndHidesParserDetails() throws Exception {
		MockHttpServletResponse response = mvc
			.perform(post("/test-probe/echo").contentType(MediaType.APPLICATION_JSON).content("{\"text\": 비밀"))
			.andReturn()
			.getResponse();

		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(body.get("error").get("code").asString()).isEqualTo("BAD_REQUEST");
		assertThat(body.get("requestId").asString()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
		// 파서 메시지와 요청 내용을 돌려주지 않는다
		assertThat(response.getContentAsString()).doesNotContain("JSON")
			.doesNotContain("Unexpected")
			.doesNotContain("비밀")
			.doesNotContain("Exception");
	}

	@Test
	void methodNotAllowedKeepsItsStatusAndAllowHeader() throws Exception {
		MockHttpServletResponse response = mvc.perform(post("/test-probe/ok")).andReturn().getResponse();

		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(405);
		assertThat(body.get("error").get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
		assertThat(response.getHeader("Allow")).contains("GET");
		assertThat(body.get("requestId").asString()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
	}

	@Test
	void unsupportedBodyTypeKeepsItsStatus() throws Exception {
		MockHttpServletResponse response = mvc
			.perform(post("/test-probe/echo").contentType(MediaType.TEXT_PLAIN).content("글자"))
			.andReturn()
			.getResponse();

		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(415);
		assertThat(body.get("error").get("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
	}

	@Test
	void missingOrMistypedParameterIsBadRequest() throws Exception {
		MockHttpServletResponse missing = mvc.perform(get("/test-probe/page")).andReturn().getResponse();
		MockHttpServletResponse mistyped = mvc.perform(get("/test-probe/page").param("size", "많이"))
			.andReturn()
			.getResponse();

		assertThat(missing.getStatus()).isEqualTo(400);
		assertThat(body(missing).get("error").get("code").asString()).isEqualTo("BAD_REQUEST");
		assertThat(mistyped.getStatus()).isEqualTo(400);
		assertThat(body(mistyped).get("error").get("code").asString()).isEqualTo("BAD_REQUEST");
		assertThat(mistyped.getContentAsString()).doesNotContain("많이").doesNotContain("NumberFormat");
	}

	@Test
	void unacceptableResponseTypeKeepsItsStatus() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/test-probe/page").param("size", "3").accept(MediaType.APPLICATION_PDF))
			.andReturn()
			.getResponse();

		// 요청한 형식으로 줄 수 없어도 오류 본문은 JSON으로 준다
		JsonNode body = body(response);
		assertThat(response.getStatus()).isEqualTo(406);
		assertThat(body.get("error").get("code").asString()).isEqualTo("NOT_ACCEPTABLE");
		assertThat(response.getContentType()).startsWith("application/json");
	}

	// 본문이 계약 스키마를 만족하는지도 함께 확인한다
	JsonNode body(MockHttpServletResponse response) throws Exception {
		String content = response.getContentAsString();
		assertThat(ContractSchemas.validate("api.schema.json#/$defs/ErrorResponse", content)).isEmpty();
		return json.readTree(content);
	}

}
