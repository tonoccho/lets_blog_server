-- projects god-tableの分割(issue #571): AIサービスが概念上所有する設定(LLMモデル/プロバイダー、
-- Brave Search APIキー)をproject_ai_settingsへ切り出す。project_idは外部キーとして持つが、
-- 将来AIサービスが物理分離された際にクロスDB外部キーにならないよう、参照キーとしての利用を想定する
-- (このマイグレーション時点ではprojectsと同一DB内にあるため、custom_tag_templates等の既存の子テーブルと
-- 同じ規約でFK制約を付与する)。

CREATE TABLE project_ai_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    llm_model VARCHAR(255) NULL,
    llm_provider VARCHAR(20) NULL,
    brave_search_api_key_encrypted VARBINARY(1024) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_project_ai_settings_project_id UNIQUE (project_id),
    CONSTRAINT fk_project_ai_settings_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- 既存データの移行: 該当ドメインの値を1つでも持つプロジェクトのみ1行を作成する
-- (未設定のプロジェクトに空行を作らない。アプリ側もこの規約に合わせ、初回書き込み時に行を遅延作成する)。
INSERT INTO project_ai_settings (project_id, llm_model, llm_provider, brave_search_api_key_encrypted, created_at, updated_at)
SELECT id, llm_model, llm_provider, brave_search_api_key_encrypted, NOW(), NOW()
FROM projects
WHERE llm_model IS NOT NULL
   OR llm_provider IS NOT NULL
   OR brave_search_api_key_encrypted IS NOT NULL;
