-- ai-serviceへのスキーマ所有権移管(#574)。generation_jobs/article_plan_sessions/project_ai_settingsの
-- 3テーブルを、legacy-api(lets_blogスキーマ)側のFlyway V1/V23/V24/V67で定義された最終形と
-- 同一のカラム構成でlbs_aiスキーマに作成する。既存データの移行はこのマイグレーション適用後に
-- scripts/migrate-ai-tables-to-lbs-ai.sqlを実行して行う(このマイグレーション自体は空のテーブルを
-- 作るだけで、データはコピーしない)。
--
-- lets_blog.projects(project-service未抽出、legacy-api側に残る)へのFOREIGN KEYはADR-0004が禁じる
-- クロススキーマFKのため、media(#573)/log-writer(#572)のV1と同じ方針で持たない。project_idは
-- IDのみを参照キーとして保持し、削除時の連動(ON DELETE CASCADE相当)はアプリケーション側の責務とする。

CREATE TABLE generation_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    request_payload JSON NULL,
    result_payload JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE article_plan_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    github_issue_number INT NULL,
    title VARCHAR(255) NOT NULL,
    history JSON NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_article_plan_sessions_project_id (project_id),
    INDEX idx_article_plan_sessions_issue (project_id, github_issue_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_ai_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    llm_model VARCHAR(255) NULL,
    llm_provider VARCHAR(20) NULL,
    brave_search_api_key_encrypted VARBINARY(1024) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_project_ai_settings_project_id UNIQUE (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
