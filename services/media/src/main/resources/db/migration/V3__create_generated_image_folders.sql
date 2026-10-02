-- issue #1493: 生成画像ギャラリーの入れ子フォルダ。
--
-- フォルダは横断(プロジェクト単位ではない)の共通ツリーで、管理者だけが作成・変更する。
-- 画像の所在はこのDB上の論理分類で、ファイルの物理配置({projectId|global}/{4桁連番}.png)や
-- GeneratedImageSequenceService の採番規則には一切影響しない。
--
-- parent_id の自己参照FKは同一スキーマ(lbs_media)内なので張れる(ADR-0004が禁じるのは
-- クロススキーマFKのみ)。ただしFKだけでは循環(A→B→A)を防げないため、親の変更時に
-- 「新しい親が自分の子孫でないか」をアプリ側で再帰CTEで確認する(GeneratedImageFolderService)。
-- フォルダの改名・削除は #1494 の範囲なので、ON DELETE は既定(RESTRICT)のままにする。

CREATE TABLE generated_image_folders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL COMMENT 'フォルダ名',
    parent_id BIGINT NULL COMMENT '親フォルダ。NULLなら最上位',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_generated_image_folders_parent_id (parent_id),
    CONSTRAINT fk_generated_image_folders_parent FOREIGN KEY (parent_id) REFERENCES generated_image_folders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1画像は最大1フォルダに属する(排他的な所在)。NULLは「未分類」。既存の行はすべて未分類になる。
ALTER TABLE generated_images
    ADD COLUMN folder_id BIGINT NULL COMMENT '所属フォルダ。NULLなら未分類' AFTER tags_json,
    ADD INDEX idx_generated_images_folder_id (folder_id),
    ADD CONSTRAINT fk_generated_images_folder FOREIGN KEY (folder_id) REFERENCES generated_image_folders (id);
