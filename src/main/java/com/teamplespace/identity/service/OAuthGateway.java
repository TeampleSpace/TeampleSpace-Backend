package com.teamplespace.identity.service;

public interface OAuthGateway {
	record Identity(String subject, String email, java.time.Instant authenticatedAt) {
		public Identity(String subject,String email) { this(subject,email,null); }
	}
	String authorizationUrl(String provider, String state, String nonce, String verifier, String purpose);
	Identity exchange(String provider, String code, String state, String nonce, String verifier);
}
