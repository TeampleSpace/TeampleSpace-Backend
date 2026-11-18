package com.teamplespace.identity;

import java.util.Set;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class AuthWebConfiguration implements WebMvcConfigurer {

	private final AuthService auth;
	private final AuthSettings settings;

	public AuthWebConfiguration(AuthService auth, AuthSettings settings) { this.auth=auth; this.settings=settings; }

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		if (settings.configured()) registry.addMapping("/v1/**").allowedOrigins(settings.origin())
			.allowedMethods("GET","HEAD","POST","PATCH","PUT","DELETE","OPTIONS")
			.allowedHeaders("Content-Type","X-CSRF-Token","Idempotency-Key","Last-Event-ID")
			.exposedHeaders("X-Request-Id","Retry-After").allowCredentials(true).maxAge(600);
	}

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
