package com.teamplespace.platform.error;

// 저장하지 않고 바로 거절하는 요청에 쓴다
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final Integer retryAfterSeconds;

	public ApiException(ErrorCode code) {
		this(code, null);
	}

	public ApiException(ErrorCode code, Integer retryAfterSeconds) {
		super(code.name());
		this.code = code;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public ErrorCode code() {
		return code;
	}

	public Integer retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
