-- 記事提出(Pull Request経由のレビュー、Epic #1333)の進行状態を持つarticle_reviews(issue #1339)。
--
-- project_id / submitted_by_user_id は、他サービス(project-service / identity-service)が所有する
-- 行のIDを参照するだけの素のカラムで、FOREIGN KEYは持たない(ADR-0004がクロススキーマFKを禁じる)。
-- プロジェクト削除時の掃除(project.deletedの購読)は本Issueでは追加せず、必要になった時点で別Issueとする。
-- stateは文字列(SUBMITTED / IN_REVIEW / CHANGES_REQUESTED / PUBLISHED)。本Issueが使うのはSUBMITTEDだけで、
-- 他の値は後続Issue(#1341 / #1343 / #1344)が遷移させる。DB側にCHECK制約は置かない(値の追加を移行なしで行えるように)。
CREATE TABLE article_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    github_pr_number INT NOT NULL,
    github_issue_number INT NOT NULL,
    article_slug VARCHAR(255) NOT NULL,
    submitted_by_user_id BIGINT NOT NULL COMMENT '提出APIを呼んだLet''s Blogユーザー。GitHubのloginではない',
    state VARCHAR(32) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_article_reviews_project_pr UNIQUE (project_id, github_pr_number)
);
