package com.teamplespace.platform.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 요청마다 식별자를 만들어 로그, 응답 헤더, 오류 본문을 이어 볼 수 있게 한다
// 클라이언트가 보낸 값은 로그를 흐릴 수 있어 받지 않고 서버가 만든다
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	public static final String ATTRIBUTE = "requestId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		MDC.put(ATTRIBUTE, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(ATTRIBUTE);
		}
	}

}
