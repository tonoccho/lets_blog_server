-- system_settingsのスキーマ所有権をplatform-serviceへ移管する(issue #693)。
-- legacy-api側の定義(services/legacy-api/src/main/resources/db/migration/V29__add_system_settings.sql)
-- と同一のテーブル定義をlbs_platformスキーマに再作成する(ADR-0004)。既存データの移行は
-- scripts/migrate-system-settings-to-lbs-platform.sqlを参照(本マイグレーション適用後に実行する運用)。

CREATE TABLE system_settings (
    setting_key VARCHAR(100) NOT NULL PRIMARY KEY,
    setting_value_encrypted BLOB NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
