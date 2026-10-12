package com.letsblog.content.integration;

import com.letsblog.common.testfixtures.MigrationContract;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * content-service の Flyway マイグレーションの契約テスト(issue #914)。
 *
 * <p>#583 で legacy-api を削除するまで、マイグレーションの検証は legacy-api 専用の
 * テストとワークフローだけが担っており、削除と同時にリポジトリ全体から失われた。
 * Flyway を持つ9サービスすべてに同じ検証を置く。
 *
 * <p>検証内容の実体は {@link MigrationContract}(lbs-common の testFixtures)にあり、
 * 9サービスで共有する。
 *
 * <p><b>スキーマとエンティティの整合は、このテストが起動した時点で既に検証されている。</b>
 * {@code application-test.yml} が {@code spring.flyway.enabled: true} +
 * {@code spring.jpa.hibernate.ddl-auto: validate} になっているため、食い違えば
 * Spring コンテキストの起動自体が落ちる(#886 で identity-service が
 * 「missing table [role_permissions]」で起動できなかったのがこの形)。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("content-service: Flywayマイグレーションの契約(issue #914)")
class MigrationContractTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("2回目のマイグレーションは0件(冪等である)")
    void マイグレーションは冪等である() {
        assertDoesNotThrow(() -> MigrationContract.verifyIdempotent(flyway));
    }

    @Test
    @DisplayName("全マイグレーションがSUCCESSで履歴に残っている")
    void 全マイグレーションが適用済みである() {
        assertDoesNotThrow(() -> MigrationContract.verifyAllMigrationsApplied(flyway, "content"));
    }

    @Test
    @DisplayName("適用済みマイグレーションのチェックサムがファイルと一致する")
    void 適用済みマイグレーションを後から編集していない() {
        assertDoesNotThrow(() -> MigrationContract.verifyValidates(flyway, "content"));
    }

    @Test
    @DisplayName("Penpotのファイル URL の列(custom_tags)は削除されている(issue #1750)")
    void Penpotのファイル_URL列は削除されている() {
        Integer columns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'custom_tags' "
                        + "AND column_name = ?",
                Integer.class, "penpot" + "_file_url");
        assertEquals(0, columns);
    }

    @Test
    @DisplayName("custom_tags の他の列は残っている(issue #1750)")
    void custom_tagsの他の列は残っている() {
        Integer columns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'custom_tags' "
                        + "AND column_name IN ('tag_name', 'html_template', 'css_content', 'project_id')",
                Integer.class);
        assertEquals(4, columns);
    }
}
