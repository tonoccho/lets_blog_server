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
--
-- #668で判明: project_users(プロジェクトメンバー、V13__add_projects.sql)/
-- bulk_operation_logs(V16__add_bulk_operation_logs.sql)/project_image_settings
-- (V68__add_project_image_settings.sql)の3テーブルも projects(id) へのFOREIGN KEYを持つが、
-- これらはproject-serviceへ移管されず引き続きlegacy-api(lets_blogスキーマ)が所有するテーブルで
-- あるため、tag_design_settingsのようにDROP TABLE自体はできない(現役ドメインテーブルであり、
-- 削除するとデータが失われる)。ADR-0004でスキーマ跨ぎのFK制約自体が禁止されているため、
-- projectsがlbs_projectスキーマへ移った時点でこれらのFK制約は物理的に維持できない
-- (別スキーマの表を参照できない)。よってテーブル・データ・他のカラムは変更せず、
-- projectsを削除する前にFK制約のみを外す(project_id列自体は暗黙の論理参照として残す)。
ALTER TABLE project_users DROP FOREIGN KEY project_users_ibfk_1;
ALTER TABLE bulk_operation_logs DROP FOREIGN KEY fk_bulk_operation_logs_project;
ALTER TABLE project_image_settings DROP FOREIGN KEY fk_project_image_settings_project;

-- 上記と同じ理由で、user_site_authors(V31__add_user_site_authors.sql、サイトごとのCMS投稿者ID対応表。
-- 引き続きlegacy-apiが所有)もsites(id)へのFOREIGN KEYを持つため、sitesを削除する前にFK制約のみを外す
-- (postsはV75で既に削除済みのためfk_posts_siteは既に存在しない。projects/static_content/
-- tag_design_settingsのsites参照は、それぞれ本ファイルの後段でDROP TABLEされるため個別対応は不要)。
ALTER TABLE user_site_authors DROP FOREIGN KEY user_site_authors_ibfk_2;

DROP TABLE IF EXISTS tag_design_settings;
DROP TABLE IF EXISTS static_content;
DROP TABLE IF EXISTS projects;
DROP TABLE IF EXISTS ssh_key_pairs;
DROP TABLE IF EXISTS sites;
