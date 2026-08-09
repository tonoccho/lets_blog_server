CREATE TABLE content_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    url VARCHAR(2048) NOT NULL,
    url_hash CHAR(64) NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    data_json TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    last_checked_at DATETIME NOT NULL,
    last_updated_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uq_content_cache_url_hash UNIQUE (url_hash),
    INDEX idx_content_type (content_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
