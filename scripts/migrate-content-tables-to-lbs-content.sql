-- content-serviceへのスキーマ所有権移管(issue #576)データ移行スクリプト。
--
-- 目的: posts/custom_tags/custom_tag_templates/content_cache/project_content_settingsの5テーブルを、
-- legacy-apiが所有するlets_blogスキーマから、content-serviceが新たに所有するlbs_contentスキーマへ
-- コピーする。lbs_contentスキーマの専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、
-- 逆にlets_blogのDBユーザーもlbs_contentへの権限を持たないため、通常のアプリケーションレベルの
-- FlywayマイグレーションではこのクロススキーマのINSERT ... SELECTを実行できない。両スキーマへ
-- アクセスできる特権アカウント(root)で本スクリプトを直接実行する運用とする(#572/#573/#574と
-- 同じパターン)。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. content-serviceのFlyway V1(services/content/src/main/resources/db/migration/
--      V1__create_content_tables.sql)を適用済みで、lbs_contentスキーマに空の5テーブルが存在すること。
--   2. legacy-api側でこれら5テーブルを削除するマイグレーションを、本スクリプトの実行後に適用すること。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-content-tables-to-lbs-content.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_content側の件数が一致していることを確認すること。
--
-- 冪等性: 対象5テーブルはcontent-serviceのV1で作成された直後の空テーブルであることを前提とする
-- (2回目以降の実行はauto_incrementの主キー衝突/UNIQUE制約違反でエラーになる。再実行が必要な場合は
-- 事前にlbs_content側の5テーブルをTRUNCATEすること)。

INSERT INTO lbs_content.posts
    (id, site_id, wp_post_id, slug, local_file_hash, uploaded_images_json, categories,
     publish_scheduled_at, status, last_published_at, created_at, updated_at)
SELECT
    id, site_id, wp_post_id, slug, local_file_hash, uploaded_images_json, categories,
    publish_scheduled_at, status, last_published_at, created_at, updated_at
FROM lets_blog.posts;

INSERT INTO lbs_content.custom_tags
    (id, tag_name, html_template, description, css_content, tag_format, project_id, penpot_file_url,
     created_at, updated_at)
SELECT
    id, tag_name, html_template, description, css_content, tag_format, project_id, penpot_file_url,
    created_at, updated_at
FROM lets_blog.custom_tags;

INSERT INTO lbs_content.custom_tag_templates
    (id, template_name, description, category, html_template, css_content, version, is_published,
     original_tag_id, project_id, created_by, created_at, updated_at)
SELECT
    id, template_name, description, category, html_template, css_content, version, is_published,
    original_tag_id, project_id, created_by, created_at, updated_at
FROM lets_blog.custom_tag_templates;

INSERT INTO lbs_content.content_cache
    (id, url, url_hash, content_type, data_json, content_hash, last_checked_at, last_updated_at,
     created_at, updated_at)
SELECT
    id, url, url_hash, content_type, data_json, content_hash, last_checked_at, last_updated_at,
    created_at, updated_at
FROM lets_blog.content_cache;

INSERT INTO lbs_content.project_content_settings
    (id, project_id, css_selector_prefix, created_at, updated_at)
SELECT
    id, project_id, css_selector_prefix, created_at, updated_at
FROM lets_blog.project_content_settings;

-- コピー後のAUTO_INCREMENTを、コピーしたidの最大値+1から再開するよう調整する
-- (そうしないと次回INSERT時に既存の(コピー済み)idと衝突する)。
SET @posts_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_content.posts);
SET @sql = CONCAT('ALTER TABLE lbs_content.posts AUTO_INCREMENT = ', @posts_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ct_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_content.custom_tags);
SET @sql = CONCAT('ALTER TABLE lbs_content.custom_tags AUTO_INCREMENT = ', @ct_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ctt_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_content.custom_tag_templates);
SET @sql = CONCAT('ALTER TABLE lbs_content.custom_tag_templates AUTO_INCREMENT = ', @ctt_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @cc_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_content.content_cache);
SET @sql = CONCAT('ALTER TABLE lbs_content.content_cache AUTO_INCREMENT = ', @cc_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @pcs_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_content.project_content_settings);
SET @sql = CONCAT('ALTER TABLE lbs_content.project_content_settings AUTO_INCREMENT = ', @pcs_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 件数突合(source/destが一致していれば移行成功)。
SELECT 'posts' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.posts) AS source_count,
       (SELECT COUNT(*) FROM lbs_content.posts) AS dest_count
UNION ALL
SELECT 'custom_tags',
       (SELECT COUNT(*) FROM lets_blog.custom_tags),
       (SELECT COUNT(*) FROM lbs_content.custom_tags)
UNION ALL
SELECT 'custom_tag_templates',
       (SELECT COUNT(*) FROM lets_blog.custom_tag_templates),
       (SELECT COUNT(*) FROM lbs_content.custom_tag_templates)
UNION ALL
SELECT 'content_cache',
       (SELECT COUNT(*) FROM lets_blog.content_cache),
       (SELECT COUNT(*) FROM lbs_content.content_cache)
UNION ALL
SELECT 'project_content_settings',
       (SELECT COUNT(*) FROM lets_blog.project_content_settings),
       (SELECT COUNT(*) FROM lbs_content.project_content_settings);
