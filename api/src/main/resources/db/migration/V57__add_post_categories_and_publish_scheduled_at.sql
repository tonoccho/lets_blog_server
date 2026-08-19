ALTER TABLE posts
    ADD COLUMN categories TEXT NULL COMMENT 'WordPressへ送信したカテゴリ名のJSON配列',
    ADD COLUMN publish_scheduled_at DATETIME NULL COMMENT 'WordPressへ送信した予約投稿の公開予定日時';
