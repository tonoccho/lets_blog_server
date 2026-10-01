-- サイトごとの管理画面パス(任意)。NULLはグローバル既定を使うことを表す(issue #1081)。
ALTER TABLE sites
    ADD COLUMN admin_path VARCHAR(200) NULL;
