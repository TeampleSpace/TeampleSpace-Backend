package com.teamplespace.identity.config;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AuthSettings {

	private final Environment env;

	public AuthSettings(Environment env) { this.env = env; }

	public String value(String key) { return env.getProperty("teamplespace.auth." + key, ""); }

	public String origin() { return value("frontend-origin"); }

	public String secret() { return value("secret"); }

	public boolean secureCookie() { return !value("secure-cookie").equals("false"); }

	public boolean configured() { return secret().length() >= 32 && validUrl(origin()); }

	public boolean mailEnabled() {
		return configured() && !env.getProperty("spring.mail.host", "").isBlank() && !value("mail-from").isBlank();
	}

	public boolean oauthEnabled(String provider) {
		return List.of("google", "kakao", "naver").contains(provider) && configured()
				&& !value(provider + ".client-id").isBlank() && !value(provider + ".client-secret").isBlank()
				&& validUrl(value("backend-origin"));
	}

	public List<Map<String, String>> legalDocuments() {
		if (value("terms-version").isBlank() || value("privacy-version").isBlank()
				|| !validUrl(value("terms-url")) || !validUrl(value("privacy-url"))) return List.of();
		return List.of(Map.of("type", "terms", "version", value("terms-version"), "url", value("terms-url")),
				Map.of("type", "privacy", "version", value("privacy-version"), "url", value("privacy-url")));
	}

	private boolean validUrl(String value) {
		try {
			URI uri = URI.create(value);
			return uri.getHost() != null && uri.getUserInfo() == null
					&& (uri.getScheme().equals("https") || (!secureCookie() && uri.getScheme().equals("http")));
		} catch (IllegalArgumentException ex) { return false; }
	}
}
