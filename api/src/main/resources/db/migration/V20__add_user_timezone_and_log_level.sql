-- システム画面のユーザー設定(言語/タイムゾーン)にタイムゾーンを追加する。
-- localeは既存カラムを流用する。
ALTER TABLE users
    ADD COLUMN timezone VARCHAR(50) NULL DEFAULT 'Asia/Tokyo' AFTER locale;

-- 作業ログのレベルフィルタ用に、実行結果ステータスから算出したレベルを保持する。
ALTER TABLE bulk_operation_logs
    ADD COLUMN level VARCHAR(10) NOT NULL DEFAULT 'INFO' AFTER status;

UPDATE bulk_operation_logs
SET level = CASE status
    WHEN 'SUCCESS' THEN 'INFO'
    WHEN 'SKIPPED' THEN 'WARNING'
    WHEN 'FAILED' THEN 'ERROR'
    ELSE 'INFO'
END;
