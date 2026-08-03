CREATE TABLE article_plan_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    history JSON NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_article_plan_sessions_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_article_plan_sessions_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
