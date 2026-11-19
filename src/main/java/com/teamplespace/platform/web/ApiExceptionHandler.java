package com.teamplespace.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

// 모든 오류를 contracts/schemas/api.schema.json의 ErrorResponse 형식으로 응답한다
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
		HttpHeaders headers = new HttpHeaders();
		if (ex.retryAfterSeconds() != null) {
			headers.set(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds().toString());
		}
		return response(ex.code(), HttpStatusCode.valueOf(ex.code().httpStatus()), headers, request, ex.details());
	}

	// 예상하지 못한 오류는 로그에만 남기고 응답에는 내부 정보를 넣지 않는다
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("처리하지 못한 오류", ex);
		return response(ErrorCode.INTERNAL_ERROR, HttpStatusCode.valueOf(500), new HttpHeaders(), request, Map.of());
	}

	// 잘못된 JSON, 허용하지 않는 메서드처럼 프레임워크가 거절한 요청이 모두 여기를 지난다
	// 프레임워크가 정한 상태와 헤더는 그대로 두고 본문만 공통 오류 형식으로 바꾼다
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (status.is5xxServerError() && status.value() != 503) {
			log.error("프레임워크가 처리하지 못한 오류", ex);
		}
		return response(codeFor(status), status, headers, request, Map.of());
	}

	private static ErrorCode codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 404 -> ErrorCode.NOT_FOUND;
			case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
			case 406 -> ErrorCode.NOT_ACCEPTABLE;
			case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
			case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
			case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
			// 그 밖의 요청 오류는 읽을 수 없는 요청으로, 서버 오류는 내부 오류로 알린다
			default -> status.is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR;
		};
	}

	private ResponseEntity<Object> response(ErrorCode code, HttpStatusCode status, HttpHeaders headers,
			WebRequest request, Map<String, Object> details) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code.name());
		error.put("message", code.message());
		error.put("retryable", code.retryable());
		error.putAll(details);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		body.put("requestId",
				String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)));
		return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON).body(body);
	}

}
