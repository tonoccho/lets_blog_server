-- platform-serviceへのスキーマ所有権移管(issue #693)。system_settingsテーブルは、platform-service
-- のFlyway V1(services/platform/src/main/resources/db/migration/V1__create_system_settings.sql)で
-- lbs_platformスキーマに再作成済み。データはscripts/migrate-system-settings-to-lbs-platform.sqlで
-- 移行済みであることを前提に、legacy-api(lets_blogスキーマ)側のテーブルをここで削除する
-- (V77__drop_project_service_tables.sqlと同じ運用: このマイグレーションは
-- migrate-system-settings-to-lbs-platform.sql実行後に適用すること)。

DROP TABLE IF EXISTS system_settings;
