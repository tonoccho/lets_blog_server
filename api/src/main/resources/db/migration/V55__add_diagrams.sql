CREATE TABLE diagrams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NULL,
    name VARCHAR(255) NOT NULL COMMENT 'ダイアグラム名',
    xml LONGTEXT NOT NULL COMMENT 'draw.io mxGraphソース(再編集用)',
    svg LONGTEXT NOT NULL COMMENT 'レンダリング済みSVG(記事挿入・サムネイル用)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_diagrams_project FOREIGN KEY (project_id)
        REFERENCES projects(id) ON DELETE SET NULL,
    INDEX idx_diagrams_project_id (project_id),
    INDEX idx_diagrams_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
