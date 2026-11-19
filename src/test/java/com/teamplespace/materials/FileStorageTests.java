package com.teamplespace.materials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.teamplespace.identity.AuthSecrets;

class FileStorageTests {

	@TempDir
	Path root;

	@Test
	void interruptedTransferRemovesPartialFileAndPreservesPreviousUpload() throws Exception {
		FileStorage storage = new FileStorage(root.toString(), mock(AuthSecrets.class));
		byte[] previous = "%PDF-previous".getBytes();
		storage.receive("staging/retry", new ByteArrayInputStream(previous), 100);
		InputStream broken = new InputStream() {
			int reads;
			@Override
			public int read() throws IOException {
				if (reads++ < 10) return 'x';
				throw new IOException("전송 중단");
			}
		};
		assertThatThrownBy(() -> storage.receive("staging/retry", broken, 100)).isInstanceOf(UncheckedIOException.class);
		assertThat(Files.readAllBytes(storage.path("staging/retry"))).isEqualTo(previous);
		try (var files = Files.list(storage.path("staging"))) {
			assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("retry");
		}
	}
}
