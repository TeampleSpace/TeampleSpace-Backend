package com.teamplespace.platform.error;

// contracts/error-codes.json과 같은 값을 유지한다
public enum ErrorCode {

	UNAUTHENTICATED(401, false, "로그인이 필요해요."),
	CSRF_INVALID(403, false, "요청 확인 정보가 만료됐어요."),
	INVALID_EMAIL(422, false, "이메일을 확인해 주세요."),
	INVALID_CODE(422, false, "인증 코드를 확인해 주세요."),
	CHALLENGE_EXPIRED(410, false, "인증 코드가 만료됐어요."),
	RATE_LIMITED(429, true, "잠시 후 다시 시도해 주세요."),
	REQUIRED_CONSENT(422, false, "필수 약관에 동의해 주세요."),
	LEGAL_DOCUMENTS_UNAVAILABLE(503, false, "가입 약관을 준비하고 있어요."),
	ONBOARDING_REQUIRED(403, false, "가입 정보를 먼저 입력해 주세요."),
	REAUTH_REQUIRED(401, false, "본인 확인을 다시 진행해 주세요."),
	OWNED_PROJECTS_EXIST(409, false, "소유한 프로젝트를 이전하거나 삭제해 주세요."),
	IDENTITY_CONFLICT(409, false, "기존 로그인 수단으로 로그인해 주세요."),
	PROVIDER_UNAVAILABLE(503, true, "로그인 제공자를 사용할 수 없어요."),
	UNSUPPORTED_PROVIDER(422, false, "지원하지 않는 로그인 제공자예요."),
	INVALID_RETURN_TO(422, false, "돌아갈 주소가 올바르지 않아요."),
	OAUTH_STATE_MISMATCH(403, false, "로그인 요청을 확인할 수 없어요."),
	OAUTH_EXPIRED(410, false, "로그인 요청이 만료됐어요."),
	OAUTH_CANCELED(400, false, "로그인을 취소했어요."),
	OAUTH_CODE_INVALID(422, false, "로그인 정보를 확인할 수 없어요."),
	ID_TOKEN_INVALID(422, false, "로그인 제공자 확인에 실패했어요."),
	FEATURE_NOT_ENABLED(404, false, "아직 사용할 수 없는 기능이에요."),
	ACCESS_DENIED(403, false, "프로젝트 접근 권한이 없어요."),
	CONFIRMATION_MISMATCH(422, false, "프로젝트 이름을 다시 확인해 주세요."),
	MEMBER_NOT_ACTIVE(409, false, "현재 팀원을 다시 확인해 주세요."),
	OWNER_TRANSFER_REQUIRED(409, false, "소유권을 먼저 이전해 주세요."),
	INVITE_INVALID(404, false, "초대 링크를 확인해 주세요."),
	INVITE_EXPIRED(410, false, "초대 링크가 만료됐어요."),
	INVALID_CURSOR(422, false, "목록을 처음부터 다시 불러와 주세요."),
	MATERIAL_DELETED(410, false, "삭제된 자료예요."),
	MATERIAL_STATE_CONFLICT(409, false, "자료 상태를 다시 확인해 주세요."),
	UPLOAD_TYPE_NOT_ALLOWED(415, false, "PDF 파일만 올릴 수 있어요."),
	UPLOAD_TOO_LARGE(413, false, "파일이 허용 크기를 넘었어요."),
	UPLOAD_EXPIRED(410, false, "업로드 시간이 지났어요. 파일을 다시 올려 주세요."),
	UPLOAD_INCOMPLETE(409, false, "파일 전송이 끝나지 않았어요."),
	UPLOAD_REJECTED(422, false, "파일을 확인하지 못했어요. 다시 선택해 주세요."),
	UPLOAD_ALREADY_USED(409, false, "이미 등록한 업로드예요."),
	FILE_URL_EXPIRED(403, false, "주소가 만료됐어요."),
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
