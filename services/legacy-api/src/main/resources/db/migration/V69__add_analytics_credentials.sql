-- projects god-tableの分割(issue #571): analyticsサービスが概念上所有する設定(Google Analytics/
-- AdSense連携)をanalytics_credentialsへ切り出す。project_idの扱いはV67__add_project_ai_settings.sql
-- と同じ規約に従う。

CREATE TABLE analytics_credentials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    ga_property_id VARCHAR(64) NULL,
    ga_service_account_json_encrypted VARBINARY(4096) NULL,
    adsense_account_id VARCHAR(64) NULL,
    adsense_refresh_token_encrypted VARBINARY(1024) NULL,
    adsense_oauth_client_id VARCHAR(255) NULL,
    adsense_oauth_client_secret_encrypted VARBINARY(1024) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_analytics_credentials_project_id UNIQUE (project_id),
    CONSTRAINT fk_analytics_credentials_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- 既存データの移行: 該当ドメインの値を1つでも持つプロジェクトのみ1行を作成する。
INSERT INTO analytics_credentials (
    project_id, ga_property_id, ga_service_account_json_encrypted, adsense_account_id,
    adsense_refresh_token_encrypted, adsense_oauth_client_id, adsense_oauth_client_secret_encrypted,
    created_at, updated_at)
SELECT id, ga_property_id, ga_service_account_json_encrypted, adsense_account_id,
       adsense_refresh_token_encrypted, adsense_oauth_client_id, adsense_oauth_client_secret_encrypted,
       NOW(), NOW()
FROM projects
WHERE ga_property_id IS NOT NULL
   OR ga_service_account_json_encrypted IS NOT NULL
   OR adsense_account_id IS NOT NULL
   OR adsense_refresh_token_encrypted IS NOT NULL
   OR adsense_oauth_client_id IS NOT NULL
   OR adsense_oauth_client_secret_encrypted IS NOT NULL;
