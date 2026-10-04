-- project-serviceへのスキーマ所有権移管(issue #577)データ移行スクリプト。
--
-- 目的: sites/projects/ssh_key_pairs/static_content/tag_design_settingsの5テーブルを、
-- legacy-apiが所有するlets_blogスキーマから、project-serviceが新たに所有するlbs_projectスキーマへ
-- コピーする。lbs_projectスキーマの専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、
-- 逆にlets_blogのDBユーザーもlbs_projectへの権限を持たないため、通常のアプリケーションレベルの
-- FlywayマイグレーションではこのクロススキーマのINSERT ... SELECTを実行できない。両スキーマへ
-- アクセスできる特権アカウント(root)で本スクリプトを直接実行する運用とする
-- (#572/#573/#574/#576と同じパターン)。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. project-serviceのFlyway V1(services/project/src/main/resources/db/migration/
--      V1__create_project_tables.sql)を適用済みで、lbs_projectスキーマに空の5テーブルが存在すること
--      (#577 stage1で既に適用済みのはず)。
--   2. issue #577のstage1〜stage3(project-service側のコード実装・legacy-api側のブリッジ化)を
--      すべてdevelopへマージ・デプロイし、legacy-api側でこれら5テーブルへの書き込みが一切発生しない
--      状態にしてから本スクリプトを実行すること(本スクリプト実行後もlegacy-apiが書き込みを続けると、
--      その変更はlbs_project側に反映されないまま失われる)。
--   3. 本スクリプト実行後、services/legacy-api/src/main/resources/db/migration/
--      V77__drop_project_service_tables.sqlを適用し、legacy-api(lets_blogスキーマ)側の
--      該当テーブルを削除すること(#668で、analytics-service抽出(#578)が同時に追加した
--      V76__drop_analytics_service_tables.sqlとバージョン番号が衝突していたため、
--      本ファイルはV76からV77へリネームした。lets_blogスキーマにはまだ適用されていない
--      ことを確認済み)。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-project-tables-to-lbs-project.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_project側の件数が一致していることを確認すること。
--
-- 冪等性: 対象5テーブルはproject-serviceのV1で作成された直後の空テーブルであることを前提とする
-- (2回目以降の実行はauto_incrementの主キー衝突/UNIQUE制約違反でエラーになる。再実行が必要な場合は
-- 事前にlbs_project側の5テーブルをTRUNCATEすること。ただしFK依存があるため
-- TRUNCATE順は本スクリプトのINSERT順と逆(tag_design_settings→static_content→ssh_key_pairs→
-- projects→sites)にすること)。
--
-- INSERT順序: sites/projects間・static_content→sites間・tag_design_settings→projects間に実FK
-- 制約があるため、参照される側を先に投入する(sites→projects→ssh_key_pairs→static_content→
-- tag_design_settings。V1__create_project_tables.sqlのCREATE TABLE順と同じ)。

INSERT INTO lbs_project.sites
    (id, name, site_key, cms_type, base_url, credentials_encrypted,
     created_at, updated_at, managed_wordpress, wp_slug, wp_db_name)
SELECT
    id, name, site_key, cms_type, base_url, credentials_encrypted,
    created_at, updated_at, managed_wordpress, wp_slug, wp_db_name
FROM lets_blog.sites;

INSERT INTO lbs_project.projects
    (id, name, slug, local_site_id, test_site_id, production_site_id, created_at, updated_at,
     master_environment, github_repository, github_token_encrypted)
SELECT
    id, name, slug, local_site_id, test_site_id, production_site_id, created_at, updated_at,
    master_environment, github_repository, github_token_encrypted
FROM lets_blog.projects;

INSERT INTO lbs_project.ssh_key_pairs
    (id, name, comment, public_key_line, private_key_encrypted, created_at)
SELECT
    id, name, comment, public_key_line, private_key_encrypted, created_at
FROM lets_blog.ssh_key_pairs;

INSERT INTO lbs_project.static_content
    (id, site_id, content_type, body, created_at, updated_at)
SELECT
    id, site_id, content_type, body, created_at, updated_at
FROM lets_blog.static_content;

INSERT INTO lbs_project.tag_design_settings
    (id, project_id, tag_type, preset_id, background_color, text_color, accent_color, custom_css,
     html_template, created_at, updated_at)
SELECT
    id, project_id, tag_type, preset_id, background_color, text_color, accent_color, custom_css,
    html_template, created_at, updated_at
FROM lets_blog.tag_design_settings;

-- コピー後のAUTO_INCREMENTを、コピーしたidの最大値+1から再開するよう調整する
-- (そうしないと次回INSERT時に既存の(コピー済み)idと衝突する)。
SET @sites_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_project.sites);
SET @sql = CONCAT('ALTER TABLE lbs_project.sites AUTO_INCREMENT = ', @sites_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @projects_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_project.projects);
SET @sql = CONCAT('ALTER TABLE lbs_project.projects AUTO_INCREMENT = ', @projects_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @skp_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_project.ssh_key_pairs);
SET @sql = CONCAT('ALTER TABLE lbs_project.ssh_key_pairs AUTO_INCREMENT = ', @skp_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sc_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_project.static_content);
SET @sql = CONCAT('ALTER TABLE lbs_project.static_content AUTO_INCREMENT = ', @sc_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tds_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_project.tag_design_settings);
SET @sql = CONCAT('ALTER TABLE lbs_project.tag_design_settings AUTO_INCREMENT = ', @tds_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 件数突合(source/destが一致していれば移行成功)。
SELECT 'sites' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.sites) AS source_count,
       (SELECT COUNT(*) FROM lbs_project.sites) AS dest_count
UNION ALL
SELECT 'projects',
       (SELECT COUNT(*) FROM lets_blog.projects),
       (SELECT COUNT(*) FROM lbs_project.projects)
UNION ALL
SELECT 'ssh_key_pairs',
       (SELECT COUNT(*) FROM lets_blog.ssh_key_pairs),
       (SELECT COUNT(*) FROM lbs_project.ssh_key_pairs)
UNION ALL
SELECT 'static_content',
       (SELECT COUNT(*) FROM lets_blog.static_content),
       (SELECT COUNT(*) FROM lbs_project.static_content)
UNION ALL
SELECT 'tag_design_settings',
       (SELECT COUNT(*) FROM lets_blog.tag_design_settings),
       (SELECT COUNT(*) FROM lbs_project.tag_design_settings);
