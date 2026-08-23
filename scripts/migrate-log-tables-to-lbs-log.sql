-- ログの所有権をlog-writerへ完全移管する(issue #572)データ移行スクリプト。
--
-- 目的: 既存のaudit_logs/operation_logs/frontend_error_logsの3テーブルを、legacy-apiが所有する
-- lets_blogスキーマから、log-writerが新たに所有するlbs_logスキーマへコピーする。
-- lbs_logスキーマの専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、逆にlets_blogの
-- DBユーザーもlbs_logへの権限を持たないため、通常のアプリケーションレベルのFlywayマイグレーションでは
-- このクロススキーマのINSERT ... SELECTを実行できない。両スキーマへアクセスできる特権アカウント
-- (root)で本スクリプトを直接実行する運用とする。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. log-writerサービスのFlyway V1(services/log-writer/src/main/resources/db/migration/
--      V1__create_log_tables.sql)を適用済みで、lbs_logスキーマに空の3テーブルが存在すること。
--   2. legacy-api側のV72(services/legacy-api/src/main/resources/db/migration/
--      V72__drop_log_tables.sql、lets_blog側の3テーブルを削除する)はまだ適用しないこと
--      (本スクリプトはコピー元のlets_blog側テーブルがまだ存在する前提)。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-log-tables-to-lbs-log.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_log側の件数が一致していることを確認すること。
-- 一致を確認できたら、legacy-apiを再ビルド/再起動してV72(旧テーブルの削除)を適用してよい。
--
-- 冪等性: 対象3テーブルはlog-writerのV1で作成された直後の空テーブルであることを前提とする
-- (2回目以降の実行はauto_incrementの主キー衝突でエラーになる。再実行が必要な場合は事前に
-- lbs_log側の3テーブルをTRUNCATEすること)。

INSERT INTO lbs_log.audit_logs
    (id, user_id, actor_keycloak_sub, action, resource_type, resource_id, changes, remote_ip, user_agent, created_at)
SELECT
    id, user_id, actor_keycloak_sub, action, resource_type, resource_id, changes, remote_ip, user_agent, created_at
FROM lets_blog.audit_logs;

INSERT INTO lbs_log.operation_logs
    (id, operation_id, user_id, actor_keycloak_sub, method, path, status_code, duration_ms, success, error_message,
     created_at)
SELECT
    id, operation_id, user_id, actor_keycloak_sub, method, path, status_code, duration_ms, success, error_message,
    created_at
FROM lets_blog.operation_logs;

INSERT INTO lbs_log.frontend_error_logs
    (id, message, stack, component_stack, user_id, actor_keycloak_sub, level, context, url, user_agent, timestamp,
     created_at)
SELECT
    id, message, stack, component_stack, user_id, actor_keycloak_sub, level, context, url, user_agent, timestamp,
    created_at
FROM lets_blog.frontend_error_logs;

-- コピー後のAUTO_INCREMENTを、コピーしたidの最大値+1から再開するよう調整する
-- (そうしないと次回INSERT時に既存の(コピー済み)idと衝突する)。
SET @audit_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_log.audit_logs);
SET @sql = CONCAT('ALTER TABLE lbs_log.audit_logs AUTO_INCREMENT = ', @audit_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @operation_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_log.operation_logs);
SET @sql = CONCAT('ALTER TABLE lbs_log.operation_logs AUTO_INCREMENT = ', @operation_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @error_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_log.frontend_error_logs);
SET @sql = CONCAT('ALTER TABLE lbs_log.frontend_error_logs AUTO_INCREMENT = ', @error_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 件数突合(source/destが一致していれば移行成功。差分が出た場合はV72を適用せず調査すること)。
SELECT 'audit_logs' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.audit_logs) AS source_count,
       (SELECT COUNT(*) FROM lbs_log.audit_logs) AS dest_count
UNION ALL
SELECT 'operation_logs',
       (SELECT COUNT(*) FROM lets_blog.operation_logs),
       (SELECT COUNT(*) FROM lbs_log.operation_logs)
UNION ALL
SELECT 'frontend_error_logs',
       (SELECT COUNT(*) FROM lets_blog.frontend_error_logs),
       (SELECT COUNT(*) FROM lbs_log.frontend_error_logs);
