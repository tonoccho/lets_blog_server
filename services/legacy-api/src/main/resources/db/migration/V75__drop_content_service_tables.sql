-- content-serviceへのスキーマ所有権移管(issue #576)。posts/custom_tags/custom_tag_templates/
-- content_cache/project_content_settingsの5テーブルは、content-serviceのFlyway V1
-- (services/content/src/main/resources/db/migration/V1__create_content_tables.sql)でlbs_content
-- スキーマに再作成済み。データはscripts/migrate-content-tables-to-lbs-content.sqlで移行済みで
-- あることを前提に、legacy-api(lets_blogスキーマ)側のテーブルをここで削除する(ai-service(#574)の
-- V74__drop_ai_service_tables.sqlと同じ運用: このマイグレーションはmigrate-content-tables-to-
-- lbs-content.sql実行後に適用すること)。
--
-- custom_tag_templates.original_tag_id -> custom_tags(id) のFOREIGN KEYがあるため、
-- custom_tag_templatesを先に削除する。

DROP TABLE IF EXISTS custom_tag_templates;
DROP TABLE IF EXISTS custom_tags;
DROP TABLE IF EXISTS content_cache;
DROP TABLE IF EXISTS project_content_settings;
DROP TABLE IF EXISTS posts;
