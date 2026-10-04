-- ChatGPT(OpenAI)のベースURLをコード内の定数に固定し、設定項目を廃止する(issue #1569)。
-- system_settingsに残る llm_base_url / image_llm_base_url の行を削除する。この削除は不可逆。
-- llm_ollama_base_url / comfyui_base_url など他の行は変更しない。行が無くても失敗せず、何度実行しても結果は同じ。
DELETE FROM system_settings WHERE setting_key IN ('llm_base_url', 'image_llm_base_url');
