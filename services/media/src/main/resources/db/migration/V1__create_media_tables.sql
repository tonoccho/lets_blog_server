-- media-serviceへのスキーマ所有権移管(#573)。generated_images/generated_image_sequences/diagrams/
-- project_image_settingsの4テーブルを、legacy-api(lets_blogスキーマ)側のFlyway V27/V28/V47/V55/V60/V68
-- で定義された最終形と同一のカラム構成でlbs_mediaスキーマに作成する。既存データの移行はこの
-- マイグレーション適用後にscripts/migrate-media-tables-to-lbs-media.sqlを実行して行う
-- (このマイグレーション自体は空のテーブルを作るだけで、データはコピーしない)。
--
-- lets_blog.projects(project-service未抽出、legacy-api側に残る)へのFOREIGN KEYはADR-0004が禁じる
-- クロススキーマFKのため、log-writerのV1(#572)と同じ方針で持たない。project_idはIDのみを
-- 参照キーとして保持し、削除時の連動(ON DELETE SET NULL/CASCADE)はアプリケーション側の責務とする。

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
    provider VARCHAR(20) NOT NULL DEFAULT 'COMFYUI' COMMENT 'どの画像生成AIで生成したか(COMFYUI/CHATGPT)',
    tags_json TEXT NULL COMMENT '検索・分類用のタグ(JSON配列文字列)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_generated_images_project_id (project_id),
    INDEX idx_generated_images_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE generated_image_sequences (
    project_key VARCHAR(50) NOT NULL PRIMARY KEY COMMENT 'プロジェクトID文字列、またはグローバル用"global"',
    last_seq INT NOT NULL DEFAULT 0 COMMENT '直近に払い出した連番',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE diagrams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NULL,
    name VARCHAR(255) NOT NULL COMMENT 'ダイアグラム名',
    xml LONGTEXT NOT NULL COMMENT 'draw.io mxGraphソース(再編集用)',
    svg LONGTEXT NOT NULL COMMENT 'レンダリング済みSVG(記事挿入・サムネイル用)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_diagrams_project_id (project_id),
    INDEX idx_diagrams_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- project_image_settingsのJavaコード側の所有権(ImageModelService/ComfyUiModelService等)は
-- #573のstage2で移す予定だが、テーブル自体の作成・データ移行はstage1でまとめて行う
-- (issue #573のstage分割方針)。stage2完了までは、legacy-api側のlets_blog.project_image_settings
-- (issue #571のV68で作成)も並行して残り、両スキーマに同一データのコピーが存在する状態になる。
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
    CONSTRAINT uk_project_image_settings_project_id UNIQUE (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
