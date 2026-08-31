-- カテゴリ作成/編集(一括管理)のスラッグ・親カテゴリ・説明・編集/削除対象の指定に対応するため、
-- 実行内容を再現できるよう作業ログ側にも保持する(ロールフォワード時に同じ内容で再現するため)。
-- 親カテゴリ・編集/削除対象はいずれも環境間で不変な「スラッグ」で識別する(term_idは環境ごとに異なるため使えない)。
ALTER TABLE bulk_operation_logs
    ADD COLUMN category_slug VARCHAR(200) NULL AFTER value,
    ADD COLUMN category_parent_slug VARCHAR(200) NULL AFTER category_slug,
    ADD COLUMN category_target_slug VARCHAR(200) NULL AFTER category_parent_slug,
    ADD COLUMN category_description TEXT NULL AFTER category_target_slug;
