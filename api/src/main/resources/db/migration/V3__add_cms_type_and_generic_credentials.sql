-- cms_type カラムを追加(既存 WordPress サイトは DEFAULT 'WORDPRESS' で自動設定される)
ALTER TABLE sites
    ADD COLUMN cms_type VARCHAR(50) NOT NULL DEFAULT 'WORDPRESS' AFTER site_key;

-- 汎用認証情報カラムを追加(新規登録サイトはCMS種別を問わずこちらに一本化する)
ALTER TABLE sites
    ADD COLUMN credentials_encrypted VARBINARY(2048) NULL AFTER wp_app_password_encrypted;

-- 既存WordPress専用カラムはNULL許容に変更(新規登録サイトでは使わないため)
ALTER TABLE sites
    MODIFY COLUMN wp_username VARCHAR(255) NULL;

ALTER TABLE sites
    MODIFY COLUMN wp_app_password_encrypted VARBINARY(1024) NULL;

-- posts.wp_post_id を VARCHAR に拡張(microCMS等、数値以外のコンテンツIDに対応するため)
ALTER TABLE posts
    MODIFY COLUMN wp_post_id VARCHAR(255) NULL;
