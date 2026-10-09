-- 初回管理者セットアップ(POST /api/auth/setup)の直列化用ロック行(issue #1718)。
--
-- 「users が空か」の確認から Keycloak 作成・ローカル保存までを同時に通すと、メールの異なる
-- リクエストが複数とも確認を通過して管理者が複数人作られる。空テーブルへの COUNT(*) FOR UPDATE は
-- InnoDB のギャップロックでデッドロックになりうるため、常に1行だけ存在するこの表の行を
-- SELECT ... FOR UPDATE で取り、トランザクションが終わるまで他のセットアップを待たせる。
CREATE TABLE initial_setup_lock (
    id TINYINT NOT NULL PRIMARY KEY
);

INSERT INTO initial_setup_lock (id) VALUES (1);
