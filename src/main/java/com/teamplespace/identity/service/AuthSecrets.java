package com.teamplespace.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.teamplespace.identity.config.AuthSettings;

@Component
public class AuthSecrets {

	private final AuthSettings settings;
	private final SecureRandom random = new SecureRandom();

	public AuthSecrets(AuthSettings settings) { this.settings = settings; }

	public String token() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public String code() { return String.format("%06d", random.nextInt(1_000_000)); }

	public byte[] hash(String value) {
		try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
		catch (Exception ex) { throw new IllegalStateException(ex); }
	}

	public byte[] hmac(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) { throw new IllegalStateException(ex); }
	}

	public String csrf(String token) { return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac("csrf:" + token)); }

	public boolean matches(byte[] expected, byte[] actual) { return MessageDigest.isEqual(expected, actual); }

	public String encrypt(String value) {
		try {
			byte[] iv = new byte[12]; random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hash(settings.secret()), "AES"), new GCMParameterSpec(128, iv));
			byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
			byte[] result = new byte[iv.length + encrypted.length];
			System.arraycopy(iv,0,result,0,iv.length); System.arraycopy(encrypted,0,result,iv.length,encrypted.length);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
		} catch(Exception ex) { throw new IllegalStateException(ex); }
	}

	public String decrypt(String value) {
		try {
			byte[] bytes=Base64.getUrlDecoder().decode(value);
			Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(hash(settings.secret()),"AES"),new GCMParameterSpec(128,bytes,0,12));
			return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
		} catch(Exception ex) { throw new IllegalStateException(ex); }
	}
}
