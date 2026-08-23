-- ログの所有権をlog-writerへ完全移管する(#572)。audit_logs/operation_logs/frontend_error_logsの
-- 3テーブルを、legacy-api(lets_blogスキーマ)側のFlyway V5/V34/V35/V66で定義された最終形と
-- 同一のカラム構成でlbs_logスキーマに作成する。既存データの移行はこのマイグレーション適用後に
-- scripts/migrate-log-tables-to-lbs-log.sql を実行して行う(このマイグレーション自体は空の
-- テーブルを作るだけで、データはコピーしない)。
--
-- bulk_operation_logsはこのマイグレーションの対象外。projectsテーブル(legacy-apiが所有する
-- lets_blogスキーマ)へのFK(fk_bulk_operation_logs_project)を持つワークフロー状態であり、
-- ADR-0004が禁じるクロススキーマFKを避けるためlegacy-api側に残す(#572のPR説明を参照)。

CREATE TABLE audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    actor_keycloak_sub VARCHAR(255) NULL,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50),
    resource_id BIGINT,
    changes TEXT,
    remote_ip VARCHAR(45),
    user_agent TEXT,
    created_at DATETIME NOT NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_created_at (created_at),
    INDEX idx_resource_type_id (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    user_id BIGINT,
    actor_keycloak_sub VARCHAR(255) NULL,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    status_code INT,
    duration_ms BIGINT NOT NULL,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    created_at DATETIME NOT NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_operation_id (operation_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE frontend_error_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message TEXT NOT NULL,
    stack TEXT,
    component_stack TEXT,
    user_id BIGINT NULL,
    actor_keycloak_sub VARCHAR(255) NULL,
    level VARCHAR(20) NOT NULL,
    context TEXT,
    url TEXT,
    user_agent TEXT,
    timestamp DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_level (level),
    INDEX idx_created_at (created_at),
    INDEX idx_url (url(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
