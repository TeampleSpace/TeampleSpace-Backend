package com.teamplespace.identity;

import java.util.Set;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class AuthWebConfiguration implements WebMvcConfigurer {

	private final AuthService auth;

	public AuthWebConfiguration(AuthService auth) { this.auth=auth; }

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
				res.setHeader("Cache-Control","no-store");res.setHeader("Referrer-Policy","no-referrer");
				res.setHeader("X-Content-Type-Options","nosniff");
				if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) auth.checkWrite(req);
				return true;
			}
		}).addPathPatterns("/v1/**");
	}
}
