package com.letsblog.platform.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #1521: {@code V2__} 移行が {@code system_settings} に残る {@code image_llm_api_key} の行だけを
 * 削除することの検証(プロジェクトへのコピーはしない)。
 *
 * <p>Web UIから到達できない(受け入れテスト環境は移行適用済みの状態から始まり「移行前の行」を作れない)ため、
 * CLAUDE.md の文書化された例外としてサービス層のテストで表現する。前例は identity-service の
 * {@code MigrationV3TimezoneContentTest}: 本物の {@code system_settings} には触れず、同じスキーマ内の
 * 使い捨てテーブルに対して移行ファイルの実体(テーブル名だけ置換)を実行する。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("platform-service: V2移行(image_llm_api_keyの行削除)のデータ変換(issue #1521)")
class MigrationV2ImageLlmApiKeyContentTest {

    private static final String PROBE_TABLE = "system_settings_migration_probe";
    private static final String MIGRATION_RESOURCE = "db/migration/V2__delete_image_llm_api_key.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbcTemplate.execute("CREATE TABLE " + PROBE_TABLE + " ("
                + "setting_key VARCHAR(100) NOT NULL PRIMARY KEY,"
                + "setting_value_encrypted BLOB NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
    }

    private void runMigration() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            if (in == null) {
                throw new AssertionError("移行ファイルが見つかりません(classpath): " + MIGRATION_RESOURCE);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\bsystem_settings\\b", PROBE_TABLE);
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty() && !trimmed.lines().allMatch(l -> l.trim().startsWith("--"))) {
                    jdbcTemplate.execute(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("image_llm_api_keyの行だけが消え、image_llm_base_urlなど他の行は残る")
    void 画像用キーの行だけが削除される() {
        jdbcTemplate.update("INSERT INTO " + PROBE_TABLE + " (setting_key, setting_value_encrypted) VALUES (?, ?), (?, ?), (?, ?)",
                "image_llm_api_key", new byte[] {1, 2}, "image_llm_base_url", new byte[] {3}, "llm_api_key", new byte[] {4});

        runMigration();

        List<String> keys = jdbcTemplate.queryForList(
                "SELECT setting_key FROM " + PROBE_TABLE + " ORDER BY setting_key", String.class);
        assertThat(keys).containsExactly("image_llm_base_url", "llm_api_key");
    }

    @Test
    @DisplayName("行が無くても失敗せず、何度実行しても同じ結果になる")
    void 行が無くても失敗しない() {
        runMigration();
        runMigration();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + PROBE_TABLE, Integer.class)).isZero();
    }
}
