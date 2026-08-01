-- カテゴリ作成(一括管理)のスラッグ・親カテゴリ・説明の指定に対応するため、
-- 実行内容を再現できるよう作業ログ側にも保持する(ロールフォワード時に同じ内容で再作成するため)
ALTER TABLE bulk_operation_logs
    ADD COLUMN category_slug VARCHAR(200) NULL AFTER value,
    ADD COLUMN category_parent_name VARCHAR(200) NULL AFTER category_slug,
    ADD COLUMN category_description TEXT NULL AFTER category_parent_name;
