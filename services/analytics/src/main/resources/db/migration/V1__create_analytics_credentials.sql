-- analytics-serviceへのスキーマ所有権移管(#578)。analytics_credentialsテーブルを、legacy-api
-- (lets_blogスキーマ)側のFlyway V69で定義された最終形と同一のカラム構成でlbs_analyticsスキーマに
-- 作成する。既存データの移行はこのマイグレーション適用後に
-- scripts/migrate-analytics-tables-to-lbs-analytics.sqlを実行して行う(このマイグレーション自体は
-- 空のテーブルを作るだけで、データはコピーしない)。
--
-- lets_blog.projects(project-service未抽出、legacy-api側に残る)へのFOREIGN KEYはADR-0004が禁じる
-- クロススキーマFKのため、media(#573)/ai(#574)のV1と同じ方針で持たない。project_idはIDのみを
-- 参照キーとして保持し、削除時の連動(ON DELETE CASCADE相当)はアプリケーション側の責務とする。

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
    CONSTRAINT uk_analytics_credentials_project_id UNIQUE (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
