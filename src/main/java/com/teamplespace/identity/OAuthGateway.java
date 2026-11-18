package com.teamplespace.identity;

public interface OAuthGateway {
	record Identity(String subject, String email) { }
	String authorizationUrl(String provider, String state, String nonce, String verifier);
	Identity exchange(String provider, String code, String state, String nonce, String verifier);
}
