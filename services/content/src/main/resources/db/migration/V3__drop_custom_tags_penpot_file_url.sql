-- issue #1750: Penpotをシステムから削除したため、カスタムタグのAI生成時に作っていた
-- Penpotデザインファイルの共有URL(custom_tags.penpot_file_url)を持たない。
-- 保存済みの値は、削除される同梱Penpotを指すリンクで再利用できないので移行しない(列ごと捨てる)。
-- V1は適用済みで書き換えられないため、列の削除は新しいマイグレーションで行う。
ALTER TABLE custom_tags DROP COLUMN penpot_file_url;
