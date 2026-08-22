CREATE TABLE generated_image_sequences (
    project_key VARCHAR(50) NOT NULL PRIMARY KEY COMMENT 'プロジェクトID文字列、またはグローバル用"global"',
    last_seq INT NOT NULL DEFAULT 0 COMMENT '直近に払い出した連番',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
