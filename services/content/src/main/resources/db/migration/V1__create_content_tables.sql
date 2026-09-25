-- content-serviceへのスキーマ所有権移管(#576)。posts/custom_tags/custom_tag_templates/content_cache/
-- project_content_settingsの5テーブルを、legacy-api(lets_blogスキーマ)側のFlyway V1/V9/V11/V14/V30/
-- V33/V36/V38/V42/V49/V57/V70で定義された最終形と同一のカラム構成でlbs_contentスキーマに作成する。
-- 既存データの移行はこのマイグレーション適用後にscripts/migrate-content-tables-to-lbs-content.sqlを
-- 実行して行う(このマイグレーション自体は空のテーブルを作るだけで、データはコピーしない)。
--
-- lets_blog.sites/lets_blog.projects/lets_blog.users(project-service/identity-service未抽出、または
-- 抽出済みだが本サービスからは引き続き外部の別スキーマ)へのFOREIGN KEYはADR-0004が禁じるクロス
-- スキーマFKのため、ai(#574)/media(#573)/log-writer(#572)のV1と同じ方針で持たない。site_id/
-- project_id/created_byはIDのみを参照キーとして保持し、削除時の連動(ON DELETE CASCADE相当)は
-- アプリケーション側の責務とする(WordPressSiteProvisioningService#deleteSiteが内部ブリッジ経由で
-- posts一括削除を行う等)。

CREATE TABLE posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_id BIGINT NOT NULL,
    wp_post_id VARCHAR(255) NULL,
    slug VARCHAR(255) NULL,
    local_file_hash VARCHAR(64) NULL,
    uploaded_images_json TEXT NULL COMMENT '投稿済み画像の参照文字列→{sha256,url,mediaId}のJSONマップ。再投稿時の重複アップロード防止に使用',
    categories TEXT NULL COMMENT 'WordPressへ送信したカテゴリ名のJSON配列',
    publish_scheduled_at DATETIME NULL COMMENT 'WordPressへ送信した予約投稿の公開予定日時',
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    last_published_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_posts_site_id (site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE custom_tags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_name VARCHAR(100) NOT NULL,
    html_template TEXT NOT NULL,
    description VARCHAR(500),
    css_content TEXT,
    tag_format VARCHAR(20) NOT NULL DEFAULT 'BLOCK',
    project_id BIGINT NULL,
    penpot_file_url VARCHAR(500),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT unique_tag_per_project UNIQUE (tag_name, project_id),
    INDEX idx_custom_tags_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE custom_tag_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    category VARCHAR(100),
    html_template TEXT NOT NULL,
    css_content TEXT,
    version INT NOT NULL DEFAULT 1,
    is_published BOOLEAN NOT NULL DEFAULT false,
    original_tag_id BIGINT,
    project_id BIGINT,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_custom_tag_templates_original_tag_id FOREIGN KEY (original_tag_id) REFERENCES custom_tags(id) ON DELETE SET NULL,
    INDEX idx_category (category),
    INDEX idx_project_id (project_id),
    INDEX idx_is_published (is_published),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE project_content_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    css_selector_prefix VARCHAR(100) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_project_content_settings_project_id UNIQUE (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
