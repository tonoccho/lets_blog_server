CREATE TABLE audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
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
