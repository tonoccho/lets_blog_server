CREATE TABLE generated_images (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NULL,
    prompt TEXT NOT NULL COMMENT 'プロンプト',
    negative_prompt TEXT COMMENT 'ネガティブプロンプト',
    steps INT COMMENT 'サンプリングステップ数',
    cfg_scale DECIMAL(5, 2) COMMENT 'CFG スケール',
    sampler_name VARCHAR(100) COMMENT 'サンプラー名',
    scheduler VARCHAR(100) COMMENT 'スケジューラー名',
    seed BIGINT COMMENT 'シード値',
    width INT COMMENT '画像幅',
    height INT COMMENT '画像高さ',
    batch_size INT COMMENT 'バッチサイズ',
    checkpoint VARCHAR(255) COMMENT 'チェックポイント名',
    lora_name VARCHAR(255) NULL COMMENT 'LoRA モデル名',
    lora_weight DECIMAL(5, 2) NULL COMMENT 'LoRA 適用強度',
    file_path VARCHAR(500) NOT NULL COMMENT 'ディスク保存パス',
    mime_type VARCHAR(100) NOT NULL DEFAULT 'image/png',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_generated_images_project FOREIGN KEY (project_id)
        REFERENCES projects(id) ON DELETE SET NULL,
    INDEX idx_generated_images_project_id (project_id),
    INDEX idx_generated_images_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
