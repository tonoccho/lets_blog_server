-- プロジェクト単位のOllama / ComfyUI接続先URLの上書き(issue #1503)。
-- NULL(または空文字)は「上書き無し」で、解決順は プロジェクト設定 → システム設定(DB) → 環境変数既定。
-- 既存行は NULL のまま = 従来どおりの解決結果になる。
ALTER TABLE project_ai_settings
    ADD COLUMN ollama_base_url VARCHAR(500) NULL,
    ADD COLUMN comfyui_base_url VARCHAR(500) NULL;
