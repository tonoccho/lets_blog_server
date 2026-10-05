package com.letsblog.ai.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V8(プロジェクトのLLMモデルをプロバイダー別の列へ分ける)が、移行前の llm_model を失わないことの確認
 * (issue #1644、要件4)。
 *
 * <p>GenerationJobOwnerMigrationTestと同じく、V7までの形の使い捨てテーブルへV8のSQLテキストを適用する。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ai-service: project_ai_settings のプロバイダー別モデル列のマイグレーション(issue #1644)")
class ProjectLlmModelPerProviderMigrationTest {

    private static final String PROBE_TABLE = "project_ai_settings_v7_probe";

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void dropProbe() {
        jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
    }

    private void migrate() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbc.execute("CREATE TABLE " + PROBE_TABLE + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, "
                + "llm_model VARCHAR(255) NULL, llm_provider VARCHAR(20) NULL)");
        jdbc.update("INSERT INTO " + PROBE_TABLE + " (project_id, llm_model, llm_provider) VALUES "
                + "(1, 'gpt-a', 'OPENAI'), (2, 'claude-b', 'CLAUDE'), (3, 'qwen', 'OLLAMA'), "
                + "(4, 'shared', NULL), (5, NULL, 'CLAUDE'), (6, 'orphan', 'UNKNOWN')");

        String v8 = new String(
                new ClassPathResource("db/migration/V8__split_project_llm_model_by_provider.sql")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        for (String statement : v8.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement.replace("project_ai_settings", PROBE_TABLE));
            }
        }
    }

    private Map<String, Object> row(long projectId) {
        return jdbc.queryForMap("SELECT * FROM " + PROBE_TABLE + " WHERE project_id = ?", projectId);
    }

    @Test
    @DisplayName("llm_provider が保存されている行は、そのプロバイダーの列へ移り、共通の旧値は空になる")
    void プロバイダー保存済みの行はそのプロバイダーの列へ移る() throws Exception {
        migrate();

        Map<String, Object> openai = row(1);
        assertEquals("gpt-a", openai.get("llm_model_openai"));
        assertNull(openai.get("llm_model"));
        assertNull(openai.get("llm_model_claude"));
        assertNull(openai.get("llm_model_ollama"));

        assertEquals("claude-b", row(2).get("llm_model_claude"));
        assertNull(row(2).get("llm_model"));
        assertEquals("qwen", row(3).get("llm_model_ollama"));
        assertNull(row(3).get("llm_model"));
    }

    @Test
    @DisplayName("llm_provider が未保存の行は、llm_model が全プロバイダー共通の旧値として残る")
    void プロバイダー未保存の行は共通の旧値として残る() throws Exception {
        migrate();

        Map<String, Object> shared = row(4);
        assertEquals("shared", shared.get("llm_model"));
        assertNull(shared.get("llm_model_openai"));
        assertNull(shared.get("llm_model_claude"));
        assertNull(shared.get("llm_model_ollama"));
    }

    @Test
    @DisplayName("llm_model が空の行や未知のプロバイダーの行は値を失わない")
    void 空の行と未知のプロバイダーの行は変わらない() throws Exception {
        migrate();

        assertNull(row(5).get("llm_model_claude"));
        assertNull(row(5).get("llm_model"));
        assertEquals("orphan", row(6).get("llm_model"));
    }
}
