package com.teamplespace.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.platform.error.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 서버의 오류 코드가 계약 목록과 같은지 확인
class ErrorCodeContractTests {

	@Test
	void codesMatchTheContractList() {
		Map<String, JsonNode> listed = new HashMap<>();
		new ObjectMapper().readTree(ContractSchemas.read(ContractSchemas.CONTRACTS.resolve("error-codes.json")))
			.get("codes")
			.forEach(code -> listed.put(code.get("code").asString(), code));

		for (ErrorCode code : ErrorCode.values()) {
			JsonNode contract = listed.get(code.name());
			assertThat(contract).as(code.name()).isNotNull();
			assertThat(contract.get("httpStatus").asInt()).as(code.name()).isEqualTo(code.httpStatus());
			assertThat(contract.get("retryable").asBoolean()).as(code.name()).isEqualTo(code.retryable());
		}
	}

}
