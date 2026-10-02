-- 記事の差し戻し(issue #1344、Epic #1333)の記録を article_reviews に持たせる。
--
-- rejected_by_user_id: 差し戻しを行ったLet's Blogユーザー。submitted_by_user_id等と同じく他サービス
--   (identity-service)が所有する行のIDを参照するだけの素のカラムで、FOREIGN KEYは持たない(ADR-0004)。
-- rejected_at: 差し戻した日時。
-- reject_comment_id: 指摘事項としてPull Requestへ投稿したコメントのID(GitHub側のID)。
--   指摘の本文はGitHub側にだけ保持し、ここには持たない(GitHubで編集されたときに食い違わないように)。
-- いずれもNULL許容にし、既存の行はそのまま有効に保つ。
ALTER TABLE article_reviews
    ADD COLUMN rejected_by_user_id BIGINT NULL COMMENT '差し戻しを行ったLet''s Blogユーザー',
    ADD COLUMN rejected_at DATETIME(6) NULL COMMENT '差し戻した日時',
    ADD COLUMN reject_comment_id BIGINT NULL COMMENT '指摘として投稿したPull RequestコメントのID';
