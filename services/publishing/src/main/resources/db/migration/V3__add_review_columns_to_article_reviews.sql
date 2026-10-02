-- レビューを開始した記事の確認先と実施者を article_reviews に持たせる(issue #1341、Epic #1333)。
--
-- test_post_url: テスト環境へ投稿した記事のURL。レビュー開始(IN_REVIEW)まではNULL。
-- reviewed_by_user_id: レビュー開始APIを呼んだLet's Blogユーザー。submitted_by_user_idと同じく
--   他サービス(identity-service)が所有する行のIDを参照するだけの素のカラムで、FOREIGN KEYは持たない(ADR-0004)。
-- どちらもNULL許容にし、既存の行(SUBMITTED)はそのまま有効に保つ。
ALTER TABLE article_reviews
    ADD COLUMN test_post_url VARCHAR(2048) NULL COMMENT 'テスト環境へ投稿した記事のURL',
    ADD COLUMN reviewed_by_user_id BIGINT NULL COMMENT 'レビュー開始APIを呼んだLet''s Blogユーザー';
