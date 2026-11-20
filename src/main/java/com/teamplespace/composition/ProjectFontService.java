package com.teamplespace.composition;

import static com.teamplespace.composition.OutlineInput.invalid;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.workspace.application.ProjectAccess;

@Service
public class ProjectFontService {

	static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

	public record Font(UUID id, String fileName, String format, int sizeBytes, String contentUrl) {
	}

	public record FontList(List<Font> items) {
	}

	record FontContent(String format, byte[] bytes) {
	}

	private final JdbcClient jdbc;
	private final ProjectAccess access;

	public ProjectFontService(JdbcClient jdbc, ProjectAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	void requireMember(UUID actor, UUID projectId) {
		access.requireMember(projectId, actor);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public FontList list(UUID actor, UUID projectId) {
		requireMember(actor, projectId);
		return new FontList(jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id, file_name, format, size_bytes
				FROM project_fonts WHERE project_id = UUID_TO_BIN(?) ORDER BY created_at, id
				""").param(projectId.toString()).query((rs, i) -> font(rs, projectId)).list());
	}

	@Transactional
	public Font upload(UUID actor, UUID projectId, String fileName, byte[] bytes) {
		if (bytes.length > MAX_FILE_BYTES) throw new ApiException(ErrorCode.UPLOAD_TOO_LARGE);
		String name = fileName(fileName);
		String format = ProjectFontData.format(bytes);
		byte[] digest = digest(bytes);
		access.lock(projectId);
		requireMember(actor, projectId);
		var existing = jdbc.sql("""
				SELECT BIN_TO_UUID(id) AS id, file_name, format, size_bytes
				FROM project_fonts WHERE project_id = UUID_TO_BIN(?) AND digest = ?
				""").params(projectId.toString(), digest).query((rs, i) -> font(rs, projectId)).optional();
		if (existing.isPresent()) return existing.get();

		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO project_fonts (id, project_id, file_name, format, digest, size_bytes, content)
				VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?)
				""").params(id.toString(), projectId.toString(), name, format, digest, bytes.length, bytes).update();
		return font(id, projectId, name, format, bytes.length);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public FontContent content(UUID actor, UUID projectId, UUID id) {
		requireMember(actor, projectId);
		return jdbc.sql("SELECT format, content FROM project_fonts WHERE project_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?)")
				.params(projectId.toString(), id.toString())
				.query((rs, index) -> new FontContent(rs.getString("format"), rs.getBytes("content")))
				.optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	boolean contains(UUID projectId, UUID id) {
		return jdbc.sql("SELECT 1 FROM project_fonts WHERE project_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?)")
				.params(projectId.toString(), id.toString()).query(Integer.class).optional().isPresent();
	}

	private static Font font(ResultSet rs, UUID projectId) throws SQLException {
		return font(UUID.fromString(rs.getString("id")), projectId, rs.getString("file_name"),
				rs.getString("format"), rs.getInt("size_bytes"));
	}

	private static Font font(UUID id, UUID projectId, String name, String format, int size) {
		return new Font(id, name, format, size, "/v1/projects/" + projectId + "/design-fonts/" + id + "/content");
	}

	private static String fileName(String value) {
		String name = value.strip();
		String lower = name.toLowerCase(Locale.ROOT);
		if (name.codePointCount(0, name.length()) > 255 || name.length() <= 4
				|| name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(Character::isISOControl)
				|| (!lower.endsWith(".ttf") && !lower.endsWith(".otf"))) {
			throw invalid("fileName", "TTF 또는 OTF 글꼴 파일의 이름을 확인해 주세요.");
		}
		return name;
	}

	private static byte[] digest(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}
}
