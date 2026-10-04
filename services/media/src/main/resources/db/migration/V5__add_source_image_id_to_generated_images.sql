-- issue #1601: ギャラリーの画像を参照画像にして生成(img2img)した画像に、参照元の画像IDを残す。
--
-- 詳細画面が「何を元にした画像か」(IDとサムネイル)を示すための列。参照画像を使っていない
-- 画像(txt2img・アップロード・#1601以前の行)はNULL。外部キーは張らない: 参照元の画像が後から
-- 削除されても、生成した画像は残る(IDだけが残り、サムネイルは表示できなくなる)。
ALTER TABLE generated_images
    ADD COLUMN source_image_id BIGINT NULL COMMENT 'img2imgの参照元の画像ID' AFTER folder_id;
