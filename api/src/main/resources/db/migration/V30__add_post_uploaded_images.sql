ALTER TABLE posts
    ADD COLUMN uploaded_images_json TEXT NULL COMMENT '投稿済み画像の参照文字列→{sha256,url,mediaId}のJSONマップ。再投稿時の重複アップロード防止に使用';
