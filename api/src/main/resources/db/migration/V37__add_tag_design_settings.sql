CREATE TABLE tag_design_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    tag_type VARCHAR(20) NOT NULL,
    preset_id VARCHAR(50) NOT NULL,
    background_color VARCHAR(7) NOT NULL,
    text_color VARCHAR(7) NOT NULL,
    accent_color VARCHAR(7) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_tag_design_settings_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT uq_tag_design_settings_project_tag UNIQUE (project_id, tag_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
