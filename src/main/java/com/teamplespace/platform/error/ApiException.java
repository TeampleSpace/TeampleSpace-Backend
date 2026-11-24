package com.teamplespace.platform.error;

import java.util.List;
import java.util.Map;

// 저장하지 않고 바로 거절하는 요청에 쓴다
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final Integer retryAfterSeconds;

	// 오류 본문의 error에 함께 싣는 fieldErrors, currentVersions
	private final Map<String, Object> details;

	public ApiException(ErrorCode code) {
		this(code, null, Map.of());
	}

	public ApiException(ErrorCode code, Integer retryAfterSeconds) {
		this(code, retryAfterSeconds, Map.of());
	}

	private ApiException(ErrorCode code, Integer retryAfterSeconds, Map<String, Object> details) {
		super(code.name());
		this.code = code;
		this.retryAfterSeconds = retryAfterSeconds;
		this.details = details;
	}

	// 버전 충돌. name은 계약의 CurrentVersions 항목 이름이다
	public static ApiException conflict(String name, long currentVersion) {
		return conflict(Map.of(name, currentVersion));
	}

	public static ApiException conflict(Map<String, Long> currentVersions) {
		return new ApiException(ErrorCode.VERSION_CONFLICT, null, Map.of("currentVersions", currentVersions));
	}

	// 어느 입력이 틀렸는지 함께 알린다
	public static ApiException field(ErrorCode code, String field, String fieldCode, String message) {
		return new ApiException(code, null,
				Map.of("fieldErrors", List.of(Map.of("field", field, "code", fieldCode, "message", message))));
	}

	public ErrorCode code() {
		return code;
	}

	public Integer retryAfterSeconds() {
		return retryAfterSeconds;
	}

	public Map<String, Object> details() {
		return details;
	}

}
