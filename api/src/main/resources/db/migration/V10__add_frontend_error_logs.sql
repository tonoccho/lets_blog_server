CREATE TABLE frontend_error_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message TEXT NOT NULL,
    stack TEXT,
    component_stack TEXT,
    level VARCHAR(20) NOT NULL,
    context TEXT,
    url TEXT,
    user_agent TEXT,
    timestamp DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_level (level),
    INDEX idx_created_at (created_at),
    INDEX idx_url (url)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
