-- ChatGPT / ClaudeのAPIキーはプロジェクト単位だけにし、システム全体のキーを廃止する(issue #1568)。
-- system_settingsに残る llm_api_key / llm_claude_api_key の行を削除する。プロジェクトへのコピーはしない
-- (利用者決定: 各プロジェクトで入力し直す)。この削除は不可逆で、暗号化済みの値は戻せない。
-- llm_base_url / llm_claude_model など他の行は変更しない。行が無くても失敗せず、何度実行しても結果は同じ。
DELETE FROM system_settings WHERE setting_key IN ('llm_api_key', 'llm_claude_api_key');
