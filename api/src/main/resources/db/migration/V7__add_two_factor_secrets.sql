CREATE TABLE two_factor_secrets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    secret VARCHAR(32) NOT NULL,
    backup_codes TEXT NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_at DATETIME,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_two_factor_secrets_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id_enabled (user_id, is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
