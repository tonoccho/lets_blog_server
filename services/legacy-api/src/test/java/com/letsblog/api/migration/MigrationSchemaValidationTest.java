package com.letsblog.api.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Database Migration Schema Validation Tests")
class MigrationSchemaValidationTest extends MigrationTestBase {

    @Test
    @DisplayName("Essential tables should exist after migrations")
    void testEssentialTablesExist() throws Exception {
        try (Connection conn = getConnection()) {
            var metaData = conn.getMetaData();

            // audit_logsは#572でlog-writerサービス(lbs_logスキーマ)へ完全移管したため、
            // このスキーマ(lets_blog)には存在しない前提のテーブル一覧から除外した。
            // custom_tagsは#576でcontent-service(lbs_contentスキーマ)へ完全移管したため、
            // 同様にこのスキーマ(lets_blog)には存在しない前提のテーブル一覧から除外した
            // (V75__drop_content_service_tables.sqlでdropされる)。
            // sites/projectsは#577でproject-service(lbs_projectスキーマ)へ完全移管したため、
            // 同様にこのスキーマ(lets_blog)には存在しない前提のテーブル一覧から除外した
            // (V77__drop_project_service_tables.sqlでdropされる。バージョン番号の衝突で
            // 一度も実行されていなかったが#668で修正され、実際にdropされるようになった)。
            // api_keys/two_factor_secrets/password_reset_tokensは#566で旧認証機構の撤去に伴い
            // 削除したため、同様にこのスキーマには存在しない前提のテーブル一覧から除外した
            // (V78__drop_legacy_auth_tables.sqlでdropされる)。
            var requiredTables = new String[]{
                    "users"
            };

            for (String table : requiredTables) {
                try (ResultSet rs = metaData.getTables(null, null, table, new String[]{"TABLE"})) {
                    assertTrue(rs.next(), "Table '" + table + "' should exist after migrations");
                }
            }
        }
    }

    @Test
    @DisplayName("Flyway metadata table should exist")
    void testFlywayMetadataTableExists() throws Exception {
        try (Connection conn = getConnection()) {
            var metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, "flyway_schema_history", new String[]{"TABLE"})) {
                assertTrue(rs.next(), "flyway_schema_history table should exist");
            }
        }
    }

    @Test
    @DisplayName("Users table should have required columns")
    void testUsersTableColumns() throws Exception {
        try (Connection conn = getConnection()) {
            var metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, null, "users", null)) {
                var columns = new java.util.HashSet<String>();
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }

                var requiredColumns = new String[]{"id", "email", "password_hash"};
                for (String col : requiredColumns) {
                    assertTrue(columns.contains(col), "users table should have '" + col + "' column");
                }
            }
        }
    }

    @Test
    @DisplayName("No orphaned constraints should exist")
    void testNoOrphanedConstraints() throws Exception {
        try (Connection conn = getConnection()) {
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery(
                         "SELECT CONSTRAINT_NAME, TABLE_NAME FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS " +
                                 "WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NULL")) {
                assertFalse(rs.next(), "There should be no orphaned foreign key constraints");
            }
        }
    }

    @Test
    @DisplayName("All tables should have proper primary keys")
    void testAllTablesHavePrimaryKeys() throws Exception {
        try (Connection conn = getConnection()) {
            var metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    if (tableName.equals("flyway_schema_history")) continue;

                    try (ResultSet pkRs = metaData.getPrimaryKeys(null, null, tableName)) {
                        assertTrue(pkRs.next(), "Table '" + tableName + "' should have a primary key");
                    }
                }
            }
        }
    }
}
