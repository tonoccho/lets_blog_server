-- プロジェクト単位のChatGPT(OpenAI)APIキー(issue #1506)。CredentialCipherで暗号化した値を保存する。
-- NULL(または空)は「キー無し」で、LLM生成はシステム設定の llm_api_key にフォールバックする。
-- 既存行は NULL のまま = 従来どおりの解決結果になる。
ALTER TABLE project_ai_settings
    ADD COLUMN openai_api_key_encrypted VARBINARY(1024) NULL;
