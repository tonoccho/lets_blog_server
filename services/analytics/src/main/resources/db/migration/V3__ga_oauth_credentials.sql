-- Google Analytics連携をサービスアカウントJSONからユーザーOAuth(3-legged、AdSenseと同じ方式)へ移行する(#1231)。
--
-- 追加する列はAdSense(adsense_oauth_client_id / adsense_oauth_client_secret_encrypted /
-- adsense_refresh_token_encrypted)と同じ命名・型・暗号化方針(CredentialCipher、APP_ENCRYPTION_KEY)。
-- GA用のOAuthクライアントはGA専用の列で独立して保持し、AdSenseの設定に依存させない。
-- ga_property_id(GA4プロパティID、平文)は再利用する。
--
-- ga_service_account_json_encrypted はこのマイグレーションで削除する。旧方式でサービスアカウントJSONのみ
-- 設定されていたプロジェクトは、リフレッシュトークンを持たないため「未設定」となり、OAuthでの再連携が必要になる
-- (ga_property_idだけが残るが、リフレッシュトークンが無い限り hasGoogleAnalyticsCredentials は false)。

ALTER TABLE analytics_credentials
    ADD COLUMN ga_oauth_client_id VARCHAR(255) NULL AFTER ga_property_id,
    ADD COLUMN ga_oauth_client_secret_encrypted VARBINARY(1024) NULL AFTER ga_oauth_client_id,
    ADD COLUMN ga_refresh_token_encrypted VARBINARY(1024) NULL AFTER ga_oauth_client_secret_encrypted,
    DROP COLUMN ga_service_account_json_encrypted;
