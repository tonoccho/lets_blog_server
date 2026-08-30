-- analytics-serviceへのスキーマ所有権移管(issue #578)。analytics_credentialsテーブルは、
-- analytics-serviceのFlyway V1(services/analytics/src/main/resources/db/migration/
-- V1__create_analytics_credentials.sql)でlbs_analyticsスキーマに再作成済み。データは
-- scripts/migrate-analytics-tables-to-lbs-analytics.sqlで移行済みであることを前提に、legacy-api
-- (lets_blogスキーマ)側のテーブルをここで削除する(V74__drop_ai_service_tables.sql/
-- V75__drop_content_service_tables.sqlと同じ運用: このマイグレーションは
-- scripts/migrate-analytics-tables-to-lbs-analytics.sql実行後に適用すること)。

DROP TABLE IF EXISTS analytics_credentials;
