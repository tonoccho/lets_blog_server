CREATE TABLE operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    user_id BIGINT,
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
