-- project-serviceへのスキーマ所有権移管(issue #577)。sites/projects/ssh_key_pairs/static_content/
-- tag_design_settingsの5テーブルは、project-serviceのFlyway V1
-- (services/project/src/main/resources/db/migration/V1__create_project_tables.sql)でlbs_projectスキーマに
-- 再作成済み。データはscripts/migrate-project-tables-to-lbs-project.sqlで移行済みであることを前提に、
-- legacy-api(lets_blogスキーマ)側のテーブルをここで削除する(content-service(#576)の
-- V75__drop_content_service_tables.sqlと同じ運用: このマイグレーションはmigrate-project-tables-to-
-- lbs-project.sql実行後に適用すること)。
--
-- tag_design_settings.project_id -> projects(id)、projects.{local,test,production}_site_id -> sites(id)、
-- static_content.site_id -> sites(id) のFOREIGN KEYがあるため、参照する側(子)を先に削除する。

DROP TABLE IF EXISTS tag_design_settings;
DROP TABLE IF EXISTS static_content;
DROP TABLE IF EXISTS projects;
DROP TABLE IF EXISTS ssh_key_pairs;
DROP TABLE IF EXISTS sites;
