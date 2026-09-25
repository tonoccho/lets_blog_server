package com.letsblog.identity.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * issue #1259: {@code V3__} 移行(個人設定のタイムゾーンを任意の上書きにする)が、
 * 既存データを正しく変換することの検証(AC2)。
 *
 * <p><b>Web UIから到達できない(文書化された例外)。</b> 受け入れテスト環境は移行適用済みの
 * 状態から始まるため「移行前の状態」を作れない。CLAUDE.md → Test-First Implementation の
 * 文書化された例外として、ここでサービス層のテストとして表現する。既存の
 * {@link MigrationContractTest} は冪等性・全適用・チェックサムのみを見ており、
 * <b>行の内容を検証する前例はこのリポジトリにまだ無い</b>。
 *
 * <p>本物の {@code lbs_identity_test} スキーマの {@code users} テーブルは、この
 * テストが動く時点で既に(Springコンテキスト起動時のFlyway自動適用により)V3まで
 * 適用済みであり、「移行前の状態」を再現できない。かつ {@code test_user} には新しい
 * スキーマを作る権限が無い({@code infra/mysql/init/02-create-test-schemas.sh} が
 * 作成したスキーマにしかGRANTされていない)ため、隔離されたスキーマも作れない。
 *
 * <p>そこで、同じ {@code lbs_identity_test} スキーマ内に本テスト専用の使い捨てテーブル
 * ({@code timezone_migration_probe})を作り、{@code V3__}移行ファイルの実ファイルを読み込んで
 * テーブル名だけ置換したSQLをそのまま実行する。実ファイルの内容を検証しつつ、
 * 他のテストが使う本物の{@code users}テーブルには一切触れない。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("identity-service: V3移行(タイムゾーンの任意上書き化)のデータ変換(issue #1259)")
class MigrationV3TimezoneContentTest {

    private static final String PROBE_TABLE = "timezone_migration_probe";
    private static final String MIGRATION_RESOURCE = "db/migration/V3__make_timezone_optional_override.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbcTemplate.execute(
                "CREATE TABLE " + PROBE_TABLE + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "timezone VARCHAR(50) DEFAULT 'Asia/Tokyo',"
                        + "PRIMARY KEY (id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
    }

    private String loadMigrationSqlAgainstProbeTable() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            if (in == null) {
                throw new AssertionError(
                        "移行ファイルが見つかりません(classpath): " + MIGRATION_RESOURCE);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // 本物のusersテーブルを一切変更せず、本テスト専用の使い捨てテーブルに対して
            // 同じ内容を実行する。ファイルの実体は"users"という単語単位でのみ書き換える。
            return sql.replaceAll("\\busers\\b", PROBE_TABLE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("既定値'Asia/Tokyo'だった行はNULLになり、他の値の行は変わらない")
    void 既定値の行だけNULLになる() {
        jdbcTemplate.update(
                "INSERT INTO " + PROBE_TABLE + " (timezone) VALUES (?), (?), (?)",
                "Asia/Tokyo", "America/New_York", null);

        String migrationSql = loadMigrationSqlAgainstProbeTable();
        for (String statement : migrationSql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timezone FROM " + PROBE_TABLE + " ORDER BY id");

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("timezone")).as("既定値だった行はNULLになる").isNull();
        assertThat(rows.get(1).get("timezone")).as("他の値の行は変わらない").isEqualTo("America/New_York");
        assertThat(rows.get(2).get("timezone")).as("元々NULLだった行はNULLのまま").isNull();
    }

    /**
     * {@code DROP DEFAULT}が実際に効いていることの確認。MySQL(strict mode。このプロジェクトの
     * {@code sql_mode}は{@code STRICT_TRANS_TABLES}を含む)では、列にDEFAULT句が一度も
     * 無かった場合(暗黙に{@code DEFAULT NULL}扱い)と、{@code DROP DEFAULT}でDEFAULT句を
     * 撤去した場合とで挙動が異なる。前者はINSERTで列を省略してもNULLが入るが、後者は
     * 「Field 'timezone' doesn't have a default value」(エラー1364)になる
     * ({@code SHOW CREATE TABLE}でも前者は{@code DEFAULT NULL}、後者はDEFAULT句自体が
     * 消えることで確認できる)。
     *
     * <p>本物のusersテーブルへのJPAのINSERTは全カラムを明示するため
     * (User.javaのコメント参照)、この省略時のエラーには実際には遭遇しない。ここでは
     * 「DROP DEFAULTが見かけ上ではなく実際にDEFAULT句を消していること」自体を固定する。
     */
    @Test
    @DisplayName("DROP DEFAULT後は列を省略したINSERTがエラーになる(DEFAULT句が実際に消えている)")
    void DROP_DEFAULT後は列省略のINSERTがエラーになる() {
        String migrationSql = loadMigrationSqlAgainstProbeTable();
        for (String statement : migrationSql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }

        assertThatThrownBy(() ->
                jdbcTemplate.update("INSERT INTO " + PROBE_TABLE + " (id) VALUES (100)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("default value");
    }
}
