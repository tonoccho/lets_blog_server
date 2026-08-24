-- ai-serviceへのスキーマ所有権移管(issue #574)データ移行スクリプト。
--
-- 目的: generation_jobs/article_plan_sessions/project_ai_settingsの3テーブルを、legacy-apiが
-- 所有するlets_blogスキーマから、ai-serviceが新たに所有するlbs_aiスキーマへコピーする。
-- lbs_aiスキーマの専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、逆にlets_blogの
-- DBユーザーもlbs_aiへの権限を持たないため、通常のアプリケーションレベルのFlywayマイグレーションでは
-- このクロススキーマのINSERT ... SELECTを実行できない。両スキーマへアクセスできる特権アカウント
-- (root)で本スクリプトを直接実行する運用とする(#572のmigrate-log-tables-to-lbs-log.sql、
-- #573のmigrate-media-tables-to-lbs-media.sqlと同じパターン)。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. ai-serviceのFlyway V1(services/ai/src/main/resources/db/migration/
--      V1__create_ai_tables.sql)を適用済みで、lbs_aiスキーマに空の3テーブルが存在すること。
--   2. legacy-api側でこれら3テーブルを削除するマイグレーション(V74)を、本スクリプトの実行後に適用すること。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-ai-tables-to-lbs-ai.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_ai側の件数が一致していることを確認すること。
--
-- 冪等性: 対象3テーブルはai-serviceのV1で作成された直後の空テーブルであることを前提とする
-- (2回目以降の実行はauto_incrementの主キー衝突/UNIQUE制約違反でエラーになる。再実行が必要な場合は
-- 事前にlbs_ai側の3テーブルをTRUNCATEすること)。

INSERT INTO lbs_ai.generation_jobs
    (id, type, status, request_payload, result_payload, created_at, updated_at)
SELECT
    id, type, status, request_payload, result_payload, created_at, updated_at
FROM lets_blog.generation_jobs;

INSERT INTO lbs_ai.article_plan_sessions
    (id, project_id, github_issue_number, title, history, created_at, updated_at)
SELECT
    id, project_id, github_issue_number, title, history, created_at, updated_at
FROM lets_blog.article_plan_sessions;

INSERT INTO lbs_ai.project_ai_settings
    (id, project_id, llm_model, llm_provider, brave_search_api_key_encrypted, created_at, updated_at)
SELECT
    id, project_id, llm_model, llm_provider, brave_search_api_key_encrypted, created_at, updated_at
FROM lets_blog.project_ai_settings;

-- コピー後のAUTO_INCREMENTを、コピーしたidの最大値+1から再開するよう調整する
-- (そうしないと次回INSERT時に既存の(コピー済み)idと衝突する)。
SET @gj_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_ai.generation_jobs);
SET @sql = CONCAT('ALTER TABLE lbs_ai.generation_jobs AUTO_INCREMENT = ', @gj_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @aps_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_ai.article_plan_sessions);
SET @sql = CONCAT('ALTER TABLE lbs_ai.article_plan_sessions AUTO_INCREMENT = ', @aps_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @pas_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_ai.project_ai_settings);
SET @sql = CONCAT('ALTER TABLE lbs_ai.project_ai_settings AUTO_INCREMENT = ', @pas_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 件数突合(source/destが一致していれば移行成功)。
SELECT 'generation_jobs' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.generation_jobs) AS source_count,
       (SELECT COUNT(*) FROM lbs_ai.generation_jobs) AS dest_count
UNION ALL
SELECT 'article_plan_sessions',
       (SELECT COUNT(*) FROM lets_blog.article_plan_sessions),
       (SELECT COUNT(*) FROM lbs_ai.article_plan_sessions)
UNION ALL
SELECT 'project_ai_settings',
       (SELECT COUNT(*) FROM lets_blog.project_ai_settings),
       (SELECT COUNT(*) FROM lbs_ai.project_ai_settings);
