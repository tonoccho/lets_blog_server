-- issue #1101: 生成画像1枚ごとに「バッチ内のどの位置か」を残す。
--
-- batch_size > 1 で生成した複数枚は、これまで全行が同じ内容(seed=NULL、batch_size=N)で
-- 保存され、バッチ内の位置を区別できなかった。ComfyUI の EmptyLatentImage.batch_size は
-- 1個の KSampler seed から N 枚分のノイズをまとめて生成するため、i 番目の画像は
-- 「seed+i, batch_size=1」では再現できず、「同じ seed で batch_size=N を再実行して
-- i 番目を取る」必要がある。その i を保持する列。
--
-- 既存行は NULL のまま(遡って補完する手段は無い)。単発保存(POST /api/generated-images を
-- 位置指定なしで呼ぶ VSCode 拡張等)でも NULL になりうるため NULL 許容とする。
ALTER TABLE generated_images
    ADD COLUMN batch_index INT NULL COMMENT 'バッチ内の位置(0起点)' AFTER batch_size;
