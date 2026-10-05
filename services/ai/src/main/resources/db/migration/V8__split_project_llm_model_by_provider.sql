-- プロジェクト単位のLLMモデルをプロバイダー(OLLAMA / OPENAI / CLAUDE)ごとの列へ分ける(issue #1644)。
-- 既存の llm_model は失わない:
--   * llm_provider が保存されている行は、そのプロバイダーの列へ移し、llm_model は空にする。
--   * llm_provider が未保存(システム既定のプロバイダーに従っている)の行は llm_model を「全プロバイダー共通の
--     旧値」として残す(Flywayからplatform側のシステム既定プロバイダーは読めない: ADR-0004)。
ALTER TABLE project_ai_settings
    ADD COLUMN llm_model_ollama VARCHAR(255) NULL,
    ADD COLUMN llm_model_openai VARCHAR(255) NULL,
    ADD COLUMN llm_model_claude VARCHAR(255) NULL;

UPDATE project_ai_settings SET llm_model_ollama = llm_model, llm_model = NULL
    WHERE llm_provider = 'OLLAMA' AND llm_model IS NOT NULL;

UPDATE project_ai_settings SET llm_model_openai = llm_model, llm_model = NULL
    WHERE llm_provider = 'OPENAI' AND llm_model IS NOT NULL;

UPDATE project_ai_settings SET llm_model_claude = llm_model, llm_model = NULL
    WHERE llm_provider = 'CLAUDE' AND llm_model IS NOT NULL
