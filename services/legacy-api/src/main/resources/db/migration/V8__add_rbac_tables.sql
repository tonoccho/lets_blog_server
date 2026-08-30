CREATE TABLE roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    description TEXT,
    INDEX idx_role_name (role_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- デフォルトロールの作成
INSERT INTO roles (role_name, display_name, description) VALUES
('ROLE_ADMIN', '管理者', 'システムの全機能にアクセス可能'),
('ROLE_EDITOR', '編集者', '投稿・サイト管理が可能'),
('ROLE_VIEWER', '閲覧者', 'コンテンツ閲覧のみ可能');

-- ROLE_ADMIN: 全権限
INSERT INTO role_permissions (role_id, permission)
SELECT id, permission FROM roles
CROSS JOIN (
    SELECT 'USER_CREATE' AS permission UNION ALL SELECT 'USER_READ' UNION ALL SELECT 'USER_UPDATE'
    UNION ALL SELECT 'USER_DELETE' UNION ALL SELECT 'USER_ROLE_MANAGE'
    UNION ALL SELECT 'POST_CREATE' UNION ALL SELECT 'POST_READ' UNION ALL SELECT 'POST_UPDATE'
    UNION ALL SELECT 'POST_DELETE' UNION ALL SELECT 'POST_PUBLISH'
    UNION ALL SELECT 'SITE_CREATE' UNION ALL SELECT 'SITE_READ' UNION ALL SELECT 'SITE_UPDATE' UNION ALL SELECT 'SITE_DELETE'
    UNION ALL SELECT 'AUDIT_LOG_VIEW' UNION ALL SELECT 'AUDIT_LOG_DELETE'
    UNION ALL SELECT 'SYSTEM_CONFIG' UNION ALL SELECT 'ROLE_MANAGE'
) AS all_permissions
WHERE role_name = 'ROLE_ADMIN';

-- ROLE_EDITOR: 投稿・サイト管理 + ユーザー閲覧
INSERT INTO role_permissions (role_id, permission)
SELECT id, permission FROM roles
CROSS JOIN (
    SELECT 'USER_READ' AS permission
    UNION ALL SELECT 'POST_CREATE' UNION ALL SELECT 'POST_READ' UNION ALL SELECT 'POST_UPDATE'
    UNION ALL SELECT 'POST_DELETE' UNION ALL SELECT 'POST_PUBLISH'
    UNION ALL SELECT 'SITE_CREATE' UNION ALL SELECT 'SITE_READ' UNION ALL SELECT 'SITE_UPDATE'
) AS editor_permissions
WHERE role_name = 'ROLE_EDITOR';

-- ROLE_VIEWER: 閲覧のみ
INSERT INTO role_permissions (role_id, permission)
SELECT id, permission FROM roles
CROSS JOIN (
    SELECT 'USER_READ' AS permission UNION ALL SELECT 'POST_READ' UNION ALL SELECT 'SITE_READ'
) AS viewer_permissions
WHERE role_name = 'ROLE_VIEWER';

-- 既存ユーザーのrole文字列カラムから、対応するロールへ移行する
-- (role文字列カラム自体はBFFヘッダ互換のため引き続き残す)
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u
JOIN roles r ON r.role_name = 'ROLE_ADMIN'
WHERE u.role = 'admin';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u
JOIN roles r ON r.role_name = 'ROLE_VIEWER'
WHERE u.role = 'user';
