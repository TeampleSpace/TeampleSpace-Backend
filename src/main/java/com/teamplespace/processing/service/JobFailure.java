package com.teamplespace.processing.service;

// 작업 실패. code는 contracts/error-codes.json에서 HTTP 상태가 없는 항목이다
public class JobFailure extends RuntimeException {

	public static final String ATTEMPTS_EXHAUSTED = "JOB_ATTEMPTS_EXHAUSTED";

	public static final String TARGET_GONE = "JOB_TARGET_GONE";

	private final String code;

	private final String userMessage;

	private final boolean retryable;

	// retryable이면 시도 횟수 안에서 같은 작업을 다시 실행하고 사용자에게도 다시 시도할 수 있다고 알린다
	public JobFailure(String code, String userMessage, boolean retryable) {
		super(code);
		this.code = code;
		this.userMessage = userMessage;
		this.retryable = retryable;
	}

	public static JobFailure attemptsExhausted() {
		return new JobFailure(ATTEMPTS_EXHAUSTED, "처리를 끝내지 못했어요. 다시 시도해 주세요.", true);
	}

	public static JobFailure targetGone() {
		return new JobFailure(TARGET_GONE, "대상이 삭제됐어요.", false);
	}

	public String code() {
		return code;
	}

	public String userMessage() {
		return userMessage;
	}

	public boolean retryable() {
		return retryable;
	}

}
