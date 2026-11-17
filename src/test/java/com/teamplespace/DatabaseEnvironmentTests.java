package com.teamplespace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 실제 MySQL 연결과 기본 설정 확인
@SpringBootTest
class DatabaseEnvironmentTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void connectsToMySql() {
		String version = jdbc.queryForObject("SELECT VERSION()", String.class);
		assertThat(version).isNotBlank();
		System.out.println("MySQL version: " + version);
	}

	@Test
	void sessionUsesUtc() {
		String offset = jdbc.queryForObject("SELECT TIMEDIFF(NOW(), UTC_TIMESTAMP())", String.class);
		assertThat(offset).startsWith("00:00:00");
	}

	@Test
	void writesUseReadCommitted() {
		String isolation = jdbc.queryForObject("SELECT @@transaction_isolation", String.class);
		assertThat(isolation).isEqualTo("READ-COMMITTED");
	}

	@Test
	void databaseUsesUtf8mb4() {
		String charset = jdbc.queryForObject("SELECT @@character_set_database", String.class);
		assertThat(charset).isEqualTo("utf8mb4");
	}

	@Test
	void flywayManagesSchema() {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'",
				Integer.class);
		assertThat(count).isEqualTo(1);
	}

}
