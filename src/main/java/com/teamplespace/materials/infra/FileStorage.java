package com.teamplespace.materials.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.teamplespace.identity.service.AuthSecrets;

// 서버의 지정 폴더에 두는 비공개 파일 보관과 만료되는 서명 주소
// ponytail: 파일 저장소 제공자가 정해지지 않아 로컬 폴더를 쓴다. 정해지면 같은 키 규칙으로 외부 저장소로 옮긴다
@Component
public class FileStorage {

	private final Path root;

	private final AuthSecrets secrets;

	public FileStorage(@Value("${teamplespace.storage.root:data/storage}") String root, AuthSecrets secrets) {
		this.root = Path.of(root).toAbsolutePath().normalize();
		this.secrets = secrets;
	}

	// 서버가 만든 키만 받는다. 클라이언트가 보낸 경로는 쓰지 않는다
	public Path path(String key) {
		Path path = root.resolve(key).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("보관 폴더 밖의 경로");
		}
		return path;
	}

	public boolean exists(String key) {
		return Files.isRegularFile(path(key));
	}

	// 임시 파일에 다 받은 뒤 한 번에 바꿔 놓는다. 한도를 넘으면 버리고 -1을 돌려준다
	public long receive(String key, InputStream input, long maxBytes) {
		Path target = path(key);
		try {
			Files.createDirectories(target.getParent());
			Path partial = Files.createTempFile(target.getParent(), "upload-", ".part");
			try {
				long written = 0;
				try (OutputStream output = Files.newOutputStream(partial)) {
					byte[] buffer = new byte[64 * 1024];
					int read;
					while ((read = input.read(buffer)) != -1) {
						written += read;
						if (written > maxBytes) {
							break;
						}
						output.write(buffer, 0, read);
					}
				}
				if (written > maxBytes) {
					return -1;
				}
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return written;
			}
			finally {
				Files.deleteIfExists(partial);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// 임시 파일을 바뀌지 않는 최종 위치로 복사한다. 검사는 복사본으로 한다
	public void promote(String stagingKey, String finalKey) {
		try {
			Files.createDirectories(path(finalKey).getParent());
			Files.copy(path(stagingKey), path(finalKey));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public void delete(String key) {
		try {
			Files.deleteIfExists(path(key));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public long size(String key) {
		try {
			return Files.size(path(key));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public byte[] sha256(String key) {
		try (InputStream input = Files.newInputStream(path(key))) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
			return digest.digest();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public byte[] head(String key, int length) {
		try (InputStream input = Files.newInputStream(path(key))) {
			return input.readNBytes(length);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// 용도, 대상, 만료 시각을 서버 비밀키로 서명한 토큰
	public String sign(String purpose, UUID id, Instant expiresAt) {
		String payload = purpose + ":" + id + ":" + expiresAt.getEpochSecond();
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		return encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
				+ encoder.encodeToString(secrets.hmac("storage:" + payload));
	}

	// 서명이 맞고 만료되지 않았을 때만 대상 식별자를 돌려준다
	public Optional<UUID> verify(String purpose, String token, Instant now) {
		try {
			String[] parts = token.split("\\.");
			if (parts.length != 2) {
				return Optional.empty();
			}
			String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
			String[] fields = payload.split(":");
			if (fields.length != 3 || !fields[0].equals(purpose)
					|| !secrets.matches(secrets.hmac("storage:" + payload), Base64.getUrlDecoder().decode(parts[1]))
					|| Long.parseLong(fields[2]) <= now.getEpochSecond()) {
				return Optional.empty();
			}
			return Optional.of(UUID.fromString(fields[1]));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
