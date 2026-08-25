-- analytics-serviceへのスキーマ所有権移管(issue #578)データ移行スクリプト。
--
-- 目的: analytics_credentialsテーブルを、legacy-apiが所有するlets_blogスキーマから、
-- analytics-serviceが新たに所有するlbs_analyticsスキーマへコピーする。lbs_analyticsスキーマの
-- 専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、逆にlets_blogのDBユーザーも
-- lbs_analyticsへの権限を持たないため、通常のアプリケーションレベルのFlywayマイグレーションでは
-- このクロススキーマのINSERT ... SELECTを実行できない。両スキーマへアクセスできる特権アカウント
-- (root)で本スクリプトを直接実行する運用とする(#572のmigrate-log-tables-to-lbs-log.sql、
-- #573のmigrate-media-tables-to-lbs-media.sql、#574のmigrate-ai-tables-to-lbs-ai.sqlと同じパターン)。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. analytics-serviceのFlyway V1(services/analytics/src/main/resources/db/migration/
--      V1__create_analytics_credentials.sql)を適用済みで、lbs_analyticsスキーマに空のテーブルが
--      存在すること。
--   2. legacy-api側でこのテーブルを削除するマイグレーション(V76)を、本スクリプトの実行後に適用すること。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-analytics-tables-to-lbs-analytics.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_analytics側の件数が一致していることを確認すること。
--
-- 冪等性: 対象テーブルはanalytics-serviceのV1で作成された直後の空テーブルであることを前提とする
-- (2回目以降の実行はauto_incrementの主キー衝突/UNIQUE制約違反でエラーになる。再実行が必要な場合は
-- 事前にlbs_analytics側のテーブルをTRUNCATEすること)。

INSERT INTO lbs_analytics.analytics_credentials (
    id, project_id, ga_property_id, ga_service_account_json_encrypted, adsense_account_id,
    adsense_refresh_token_encrypted, adsense_oauth_client_id, adsense_oauth_client_secret_encrypted,
    created_at, updated_at)
SELECT
    id, project_id, ga_property_id, ga_service_account_json_encrypted, adsense_account_id,
    adsense_refresh_token_encrypted, adsense_oauth_client_id, adsense_oauth_client_secret_encrypted,
    created_at, updated_at
FROM lets_blog.analytics_credentials;

-- コピー後のAUTO_INCREMENTを、コピーしたidの最大値+1から再開するよう調整する
-- (そうしないと次回INSERT時に既存の(コピー済み)idと衝突する)。
SET @ac_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_analytics.analytics_credentials);
SET @sql = CONCAT('ALTER TABLE lbs_analytics.analytics_credentials AUTO_INCREMENT = ', @ac_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 件数突合(source/destが一致していれば移行成功)。
SELECT 'analytics_credentials' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.analytics_credentials) AS source_count,
       (SELECT COUNT(*) FROM lbs_analytics.analytics_credentials) AS dest_count;
