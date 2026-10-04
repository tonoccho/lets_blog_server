-- issue #1599: 利用者が手元の画像をアップロードして生成画像ギャラリーへ登録できるようにする。
--
-- アップロード画像はAIで生成したものではないためpromptを持たない。prompt を NULL 許可にする
-- (既存の行はすべて値を持つので、データの書き換えは要らない)。出所は provider 列の値
-- 'UPLOAD' で区別する(列は VARCHAR(20) のまま。'UPLOAD' は収まる)。
ALTER TABLE generated_images
    MODIFY COLUMN prompt TEXT NULL COMMENT 'プロンプト。アップロード画像(provider=UPLOAD)はNULL',
    MODIFY COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'COMFYUI'
        COMMENT '出所(COMFYUI/CHATGPT=画像生成AI、UPLOAD=利用者がアップロードした画像)';
