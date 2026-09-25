-- platform-serviceへのスキーマ所有権移管(issue #693)データ移行スクリプト。
--
-- 目的: system_settingsテーブルを、legacy-apiが所有するlets_blogスキーマから、
-- platform-serviceが新たに所有するlbs_platformスキーマへコピーする。lbs_platformスキーマの
-- 専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、逆にlets_blogのDBユーザーも
-- lbs_platformへの権限を持たないため、通常のアプリケーションレベルのFlywayマイグレーションでは
-- このクロススキーマのINSERT ... SELECTを実行できない。両スキーマへアクセスできる特権アカウント
-- (root)で本スクリプトを直接実行する運用とする(#578のmigrate-analytics-tables-to-lbs-analytics.sql
-- と同じパターン)。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. platform-serviceのFlyway V1(services/platform/src/main/resources/db/migration/
--      V1__create_system_settings.sql)を適用済みで、lbs_platformスキーマに空のテーブルが
--      存在すること。
--   2. legacy-api側でこのテーブルを削除するマイグレーション(V80)を、本スクリプトの実行後に適用すること。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-system-settings-to-lbs-platform.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_platform側の件数が一致していることを確認すること。
--
-- 冪等性: 対象テーブルはplatform-serviceのV1で作成された直後の空テーブルであることを前提とする
-- (2回目以降の実行はsetting_key主キーの衝突でエラーになる。再実行が必要な場合は事前にlbs_platform
-- 側のテーブルをTRUNCATEすること)。system_settingsはAUTO_INCREMENTを使わない(setting_keyが
-- 文字列PK)ため、AUTO_INCREMENT調整は不要。

INSERT INTO lbs_platform.system_settings (setting_key, setting_value_encrypted, updated_at)
SELECT setting_key, setting_value_encrypted, updated_at
FROM lets_blog.system_settings;

-- 件数突合(source/destが一致していれば移行成功)。
SELECT 'system_settings' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.system_settings) AS source_count,
       (SELECT COUNT(*) FROM lbs_platform.system_settings) AS dest_count;
