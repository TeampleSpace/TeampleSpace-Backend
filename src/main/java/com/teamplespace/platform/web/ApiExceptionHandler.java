package com.teamplespace.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

// 오류를 contracts/schemas/api.schema.json의 ErrorResponse 형식으로 응답한다
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> handle(ApiException ex, HttpServletRequest request) {
		return response(ex.code(), ex.retryAfterSeconds(), request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<Map<String, Object>> handleUnknownPath(HttpServletRequest request) {
		return response(ErrorCode.NOT_FOUND, null, request);
	}

	// 예상하지 못한 오류는 로그에만 남기고 응답에는 내부 정보를 넣지 않는다
	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
		if (ex instanceof ErrorResponse) {
			// 스프링이 상태를 정한 요청 오류는 실제 경로를 만들 때 코드 대응을 정한다
			throw ex;
		}
		log.error("처리하지 못한 오류", ex);
		return response(ErrorCode.INTERNAL_ERROR, null, request);
	}

	private ResponseEntity<Map<String, Object>> response(ErrorCode code, Integer retryAfterSeconds,
			HttpServletRequest request) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code.name());
		error.put("message", code.message());
		error.put("retryable", code.retryable());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		body.put("requestId", String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.httpStatus());
		if (retryAfterSeconds != null) {
			builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
		}
		return builder.body(body);
	}

}
