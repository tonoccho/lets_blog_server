CREATE TABLE bulk_operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    operation_type VARCHAR(20) NOT NULL, -- CATEGORY / PLUGIN / THEME
    source_type VARCHAR(10) NOT NULL DEFAULT 'SLUG', -- SLUG / ZIP
    value VARCHAR(255) NOT NULL, -- SLUG: wordpress.orgのslug / ZIP: 元のファイル名(original_filenameと同値)
    original_filename VARCHAR(255), -- ZIPのみ
    storage_path VARCHAR(500), -- ZIPのみ、bulk_upload_files ボリューム内の相対パス
    file_sha256 VARCHAR(64), -- ZIPのみ
    environment VARCHAR(20) NOT NULL, -- local / test / production
    status VARCHAR(20) NOT NULL, -- SUCCESS / SKIPPED / FAILED
    error_message TEXT,
    is_replay BOOLEAN NOT NULL DEFAULT FALSE,
    actor_id BIGINT,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_bulk_operation_logs_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_bulk_operation_logs_project_id (project_id),
    INDEX idx_bulk_operation_logs_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
