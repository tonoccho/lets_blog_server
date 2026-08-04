CREATE TABLE api_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    key_prefix VARCHAR(12) NOT NULL,
    label VARCHAR(100) NOT NULL,
    last_used_at DATETIME,
    revoked_at DATETIME,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_api_keys_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_api_keys_key_hash (key_hash),
    INDEX idx_api_keys_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
