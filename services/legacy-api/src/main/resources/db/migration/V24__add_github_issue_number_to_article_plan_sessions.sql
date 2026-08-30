ALTER TABLE article_plan_sessions
    ADD COLUMN github_issue_number INT NULL AFTER project_id,
    ADD INDEX idx_article_plan_sessions_issue (project_id, github_issue_number);
