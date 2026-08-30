-- projects god-tableの分割(issue #571): mediaサービスが概念上所有する設定(画像生成プロバイダー、
-- ComfyUIチェックポイント、画像生成デフォルト、不適切コンテンツフィルタ)をproject_image_settingsへ
-- 切り出す。project_idの扱いはV67__add_project_ai_settings.sqlと同じ規約に従う。

CREATE TABLE project_image_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    image_provider VARCHAR(20) NULL,
    comfyui_checkpoint VARCHAR(255) NULL,
    default_negative_prompt VARCHAR(1000) NULL,
    default_quality_prompt VARCHAR(500) NULL,
    default_generated_image_width INT NULL,
    default_generated_image_height INT NULL,
    default_article_image_long_edge_px INT NULL,
    block_sexual_content BOOLEAN NULL,
    block_violent_content BOOLEAN NULL,
    block_discriminatory_content BOOLEAN NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_project_image_settings_project_id UNIQUE (project_id),
    CONSTRAINT fk_project_image_settings_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- 既存データの移行: 該当ドメインの値を1つでも持つプロジェクトのみ1行を作成する。
INSERT INTO project_image_settings (
    project_id, image_provider, comfyui_checkpoint, default_negative_prompt, default_quality_prompt,
    default_generated_image_width, default_generated_image_height, default_article_image_long_edge_px,
    block_sexual_content, block_violent_content, block_discriminatory_content, created_at, updated_at)
SELECT id, image_provider, comfyui_checkpoint, default_negative_prompt, default_quality_prompt,
       default_generated_image_width, default_generated_image_height, default_article_image_long_edge_px,
       block_sexual_content, block_violent_content, block_discriminatory_content, NOW(), NOW()
FROM projects
WHERE image_provider IS NOT NULL
   OR comfyui_checkpoint IS NOT NULL
   OR default_negative_prompt IS NOT NULL
   OR default_quality_prompt IS NOT NULL
   OR default_generated_image_width IS NOT NULL
   OR default_generated_image_height IS NOT NULL
   OR default_article_image_long_edge_px IS NOT NULL
   OR block_sexual_content IS NOT NULL
   OR block_violent_content IS NOT NULL
   OR block_discriminatory_content IS NOT NULL;
