-- ai-serviceへのスキーマ所有権移管(issue #574)。generation_jobs/article_plan_sessions/
-- project_ai_settingsの3テーブルは、ai-serviceのFlyway V1(services/ai/src/main/resources/
-- db/migration/V1__create_ai_tables.sql)でlbs_aiスキーマに再作成済み。データは
-- scripts/migrate-ai-tables-to-lbs-ai.sqlで移行済みであることを前提に、legacy-api(lets_blog
-- スキーマ)側のテーブルをここで削除する(media-service(#573)のV73__drop_media_service_tables.sql
-- と同じ運用: このマイグレーションはscripts/migrate-ai-tables-to-lbs-ai.sql実行後に適用すること)。

DROP TABLE IF EXISTS article_plan_sessions;
DROP TABLE IF EXISTS project_ai_settings;
DROP TABLE IF EXISTS generation_jobs;
