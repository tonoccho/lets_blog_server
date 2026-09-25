-- 多段レビュー(#1210)のステップ別AIプロバイダー/モデル設定(issue #1211)。project_ai_settings
-- (プロジェクト既定)へのカラム追加ではなく別テーブルにしたのは、ステップが将来増減しうるため
-- (カラム追加だと都度マイグレーションが必要になる)。project_id + step_keyの複合ユニークで
-- 1プロジェクト×1ステップにつき最大1行を持つ。行が無いステップは「未設定」として扱う。
--
-- lets_blog.projectsへのFOREIGN KEYはADR-0004が禁じるクロススキーマFKのため持たない
-- (V1__create_ai_tables.sqlと同じ方針)。

CREATE TABLE project_review_step_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    step_key VARCHAR(30) NOT NULL,
    llm_provider VARCHAR(20) NULL,
    llm_model VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_project_review_step_settings_project_step UNIQUE (project_id, step_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
