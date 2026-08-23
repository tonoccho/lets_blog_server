-- projects god-tableの分割(issue #571): contentサービスが概念上所有する設定(カスタムタグCSSの
-- セレクタプリフィックス)をproject_content_settingsへ切り出す。project_idの扱いは
-- V67__add_project_ai_settings.sqlと同じ規約に従う。

CREATE TABLE project_content_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    css_selector_prefix VARCHAR(100) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_project_content_settings_project_id UNIQUE (project_id),
    CONSTRAINT fk_project_content_settings_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- 既存データの移行: css_selector_prefixが設定済みのプロジェクトのみ1行を作成する。
INSERT INTO project_content_settings (project_id, css_selector_prefix, created_at, updated_at)
SELECT id, css_selector_prefix, NOW(), NOW()
FROM projects
WHERE css_selector_prefix IS NOT NULL;
