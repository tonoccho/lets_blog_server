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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V5(generation_jobsへ所有者列を足す)が、移行前から存在する行を消さず、
 * 所有者不明(NULL)のまま残すことの確認(issue #1406、要件4)。
 *
 * <p>スキーマ全体を V4 まで戻して再移行するとテスト用スキーマを共有する他のテストを壊すため、
 * V1 と同じ形の使い捨てテーブルを作り、V5 のSQLテキストをそのテーブルへ適用する。
 * 実テーブルについては、列が存在し NULL 許容であることを information_schema で確かめる。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ai-service: generation_jobs 所有者列のマイグレーション(issue #1406)")
class GenerationJobOwnerMigrationTest {

    private static final String PROBE_TABLE = "generation_jobs_v4_probe";

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void dropProbe() {
        jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
    }

    @Test
    @DisplayName("移行前から存在する行は、V5適用後も残り、所有者はNULL(不明)になる")
    void 既存行は残り所有者は不明になる() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbc.execute("CREATE TABLE " + PROBE_TABLE + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, type VARCHAR(50) NOT NULL, "
                + "status VARCHAR(20) NOT NULL DEFAULT 'pending', request_payload JSON NULL, result_payload JSON NULL, "
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO " + PROBE_TABLE + " (type, status) VALUES ('plan_chat', 'done')");

        String v5 = new String(
                new ClassPathResource("db/migration/V5__add_generation_job_owner.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        for (String statement : v5.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement.replace("generation_jobs", PROBE_TABLE));
            }
        }

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT type, owner_user_id FROM " + PROBE_TABLE);
        assertEquals(1, rows.size());
        assertEquals("plan_chat", rows.get(0).get("type"));
        assertNull(rows.get(0).get("owner_user_id"));
    }

    @Test
    @DisplayName("実テーブルの owner_user_id はNULL許容のBIGINTで、外部キーを持たない(ADR-0004)")
    void 実テーブルの所有者列はNULL許容でFKを持たない() {
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT IS_NULLABLE, DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_jobs' "
                        + "AND COLUMN_NAME = 'owner_user_id'");
        assertEquals("YES", column.get("IS_NULLABLE"));
        assertEquals("bigint", String.valueOf(column.get("DATA_TYPE")).toLowerCase());

        Integer foreignKeys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_jobs' "
                        + "AND COLUMN_NAME = 'owner_user_id' AND REFERENCED_TABLE_NAME IS NOT NULL",
                Integer.class);
        assertEquals(0, foreignKeys);
    }
}
