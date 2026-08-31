-- RBACのロールと権限のマスタデータを投入する(issue #956)。
--
-- ■ なぜ必要か
--
-- このデータは元々 legacy-api の V8(commit 50a23ae4「RBAC基盤を追加」)で投入されていた。
-- issue #583 で identity-service を切り出した際、V1__create_identity_tables.sql は
-- **DDLだけ**を移し、マスタデータを持ってこなかった。既存の開発環境では
-- scripts/migrate-identity-tables-to-lbs-identity.sql が行ごと移送したため気づかれず、
-- issue #945 で「9スキーマを drop して Flyway で作り直す」ようになって初めて露見した。
--
-- 投入されていないと、UserService の
--   roleRepository.findByRoleName("ROLE_ADMIN").ifPresent(role -> user.getRoles().add(role))
-- が**静かに何もしない**。ユーザー作成は成功するのにロールだけ付かず、
-- GET /api/identity/me/permissions が全ユーザーに対して [] を返す。
--
-- ■ 冪等性
--
-- 既にデータがある環境(移送済みの開発環境)で流しても壊れないよう、
-- roles は INSERT IGNORE(role_name が UNIQUE)、role_permissions と user_roles は
-- 複合主キーに対する INSERT IGNORE を使う。行が既にあれば何もしない。
--
-- ■ 内容は commit 50a23ae4 の定義をそのまま復元したものである
--
--   ROLE_ADMIN  : 全18権限
--   ROLE_EDITOR : 投稿・サイト管理 + ユーザー閲覧
--   ROLE_VIEWER : 閲覧のみ(USER_READ / POST_READ / SITE_READ)
--
-- 権限の識別子は com.letsblog.identity.domain.Permission と一対一で対応する。
-- Permission に値を足したら、どのロールへ与えるかをここでも決めること。

INSERT IGNORE INTO roles (role_name, display_name, description) VALUES
('ROLE_ADMIN', '管理者', 'システムの全機能にアクセス可能'),
('ROLE_EDITOR', '編集者', '投稿・サイト管理が可能'),
('ROLE_VIEWER', '閲覧者', 'コンテンツ閲覧のみ可能');

-- ROLE_ADMIN: 全権限
INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT id, permission FROM roles
CROSS JOIN (
    SELECT 'USER_CREATE' AS permission UNION ALL SELECT 'USER_READ' UNION ALL SELECT 'USER_UPDATE'
    UNION ALL SELECT 'USER_DELETE' UNION ALL SELECT 'USER_ROLE_MANAGE'
    UNION ALL SELECT 'POST_CREATE' UNION ALL SELECT 'POST_READ' UNION ALL SELECT 'POST_UPDATE'
    UNION ALL SELECT 'POST_DELETE' UNION ALL SELECT 'POST_PUBLISH'
    UNION ALL SELECT 'SITE_CREATE' UNION ALL SELECT 'SITE_READ' UNION ALL SELECT 'SITE_UPDATE'
    UNION ALL SELECT 'SITE_DELETE'
    UNION ALL SELECT 'AUDIT_LOG_VIEW' UNION ALL SELECT 'AUDIT_LOG_DELETE'
    UNION ALL SELECT 'SYSTEM_CONFIG' UNION ALL SELECT 'ROLE_MANAGE'
) AS all_permissions
WHERE role_name = 'ROLE_ADMIN';

-- ROLE_EDITOR: 投稿・サイト管理 + ユーザー閲覧
INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT id, permission FROM roles
CROSS JOIN (
    SELECT 'USER_READ' AS permission
    UNION ALL SELECT 'POST_CREATE' UNION ALL SELECT 'POST_READ' UNION ALL SELECT 'POST_UPDATE'
    UNION ALL SELECT 'POST_DELETE' UNION ALL SELECT 'POST_PUBLISH'
    UNION ALL SELECT 'SITE_CREATE' UNION ALL SELECT 'SITE_READ' UNION ALL SELECT 'SITE_UPDATE'
) AS editor_permissions
WHERE role_name = 'ROLE_EDITOR';

-- ROLE_VIEWER: 閲覧のみ
INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT id, permission FROM roles
CROSS JOIN (
    SELECT 'USER_READ' AS permission UNION ALL SELECT 'POST_READ' UNION ALL SELECT 'SITE_READ'
) AS viewer_permissions
WHERE role_name = 'ROLE_VIEWER';

-- 既存ユーザーの role 文字列カラムから、対応するロールへ割り当てる。
-- 新規環境ではユーザーが0人なので何も起きない。既にユーザーが居て、かつ
-- roles が空だったせいでロールが付かなかった環境を救済するために入れている。
INSERT IGNORE INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u
JOIN roles r ON r.role_name = 'ROLE_ADMIN'
WHERE u.role = 'admin';

INSERT IGNORE INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u
JOIN roles r ON r.role_name = 'ROLE_VIEWER'
WHERE u.role = 'user';
