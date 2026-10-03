-- レビュー完了(本番投稿・マージ)の記録を article_reviews に持たせる(issue #1343、Epic #1333)。
--
-- production_post_url: 本番環境へ投稿した記事のURL。公開済み(PUBLISHED)まではNULL。
-- published_by_user_id: レビュー完了APIを呼んだLet's Blogユーザー。submitted_by_user_idと同じく
--   他サービス(identity-service)が所有する行のIDを参照するだけの素のカラムで、FOREIGN KEYは持たない(ADR-0004)。
-- どちらもNULL許容にし、既存の行はそのまま有効に保つ。
ALTER TABLE article_reviews
    ADD COLUMN production_post_url VARCHAR(2048) NULL COMMENT '本番環境へ投稿した記事のURL',
    ADD COLUMN published_by_user_id BIGINT NULL COMMENT 'レビュー完了APIを呼んだLet''s Blogユーザー';
