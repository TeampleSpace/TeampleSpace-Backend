package com.teamplespace.platform.error;

// contracts/error-codes.json과 같은 값을 유지한다
public enum ErrorCode {

	VERSION_CONFLICT(409, false, "이후 수정이 있어요."),
	IDEMPOTENCY_KEY_REUSED(409, false, "같은 멱등키로 다른 요청을 보냈어요."),
	REQUEST_IN_PROGRESS(409, true, "같은 요청을 처리하고 있어요."),
	EDIT_GROUP_CLOSED(409, false, "이미 닫힌 편집 묶음이에요."),
	CHANGE_NOT_FINALIZED(409, false, "아직 편집 중인 변경이에요."),
	IDEMPOTENCY_KEY_REQUIRED(400, false, "Idempotency-Key 헤더가 필요해요."),
	FORBIDDEN(403, false, "권한이 없어요."),
	NOT_FOUND(404, false, "대상을 찾을 수 없어요."),
	VALIDATION_FAILED(422, false, "입력을 확인해 주세요.");

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
