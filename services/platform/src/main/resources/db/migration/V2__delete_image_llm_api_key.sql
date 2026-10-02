-- 画像生成(ChatGPT)のAPIキーはプロジェクト単位だけにし、システム全体のキーを廃止する(issue #1521)。
-- system_settingsに残る image_llm_api_key の行を削除する。プロジェクトへのコピーはしない(利用者決定)。
-- image_llm_base_url など他の行は変更しない。行が無くても失敗せず、何度実行しても結果は同じ。
DELETE FROM system_settings WHERE setting_key = 'image_llm_api_key';
