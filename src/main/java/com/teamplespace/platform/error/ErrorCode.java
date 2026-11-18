package com.teamplespace.platform.error;

// contracts/error-codes.json과 같은 값을 유지한다
public enum ErrorCode {

	VERSION_CONFLICT(409, false, "이후 수정이 있어요."),
	IDEMPOTENCY_KEY_REUSED(409, false, "같은 멱등키로 다른 요청을 보냈어요."),
	REQUEST_IN_PROGRESS(409, true, "같은 요청을 처리하고 있어요."),
	EDIT_GROUP_CLOSED(409, false, "이미 닫힌 편집 묶음이에요."),
	CHANGE_NOT_FINALIZED(409, false, "아직 편집 중인 변경이에요."),
	IDEMPOTENCY_KEY_REQUIRED(400, false, "Idempotency-Key 헤더가 필요해요."),
	BAD_REQUEST(400, false, "요청 형식이 올바르지 않아요."),
	FORBIDDEN(403, false, "권한이 없어요."),
	NOT_FOUND(404, false, "대상을 찾을 수 없어요."),
	METHOD_NOT_ALLOWED(405, false, "허용하지 않는 요청 방식이에요."),
	NOT_ACCEPTABLE(406, false, "요청한 응답 형식을 지원하지 않아요."),
	PAYLOAD_TOO_LARGE(413, false, "요청이 너무 커요."),
	UNSUPPORTED_MEDIA_TYPE(415, false, "지원하지 않는 요청 본문 형식이에요."),
	VALIDATION_FAILED(422, false, "입력을 확인해 주세요."),
	INTERNAL_ERROR(500, false, "요청을 처리하지 못했어요."),
	SERVICE_UNAVAILABLE(503, true, "잠시 후 다시 시도해 주세요.");

	private final int httpStatus;

	private final boolean retryable;

	private final String message;

	ErrorCode(int httpStatus, boolean retryable, String message) {
		this.httpStatus = httpStatus;
		this.retryable = retryable;
		this.message = message;
	}

	public int httpStatus() {
		return httpStatus;
	}

	public boolean retryable() {
		return retryable;
	}

	public String message() {
		return message;
	}

}
