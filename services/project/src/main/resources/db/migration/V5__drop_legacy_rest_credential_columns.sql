-- REST接続方式(Application Passwordのみで WordPress REST API を呼ぶ方式)は #518 で廃止済み。
-- 認証情報は credentials_encrypted(AGENT / SSH)だけが持つため、旧列を削除する(issue #1565)。
-- credentials_encrypted 内に残る appPassword キーは暗号化されているためSQLでは消せない。
-- 起動時に LegacyAppPasswordCleanup が取り除く。
ALTER TABLE `sites`
  DROP COLUMN `wp_username`,
  DROP COLUMN `wp_app_password_encrypted`;
