-- 作業ログの障害解析を容易にするため、失敗時の例外メッセージ(error_message)に加えて
-- フルスタックトレースも保持する(作業ログ画面のコピーボタンでこのチャットに貼り付けて使う想定)。
ALTER TABLE bulk_operation_logs
    ADD COLUMN stack_trace TEXT NULL AFTER error_message;
