-- identity-serviceが所有する6テーブル(issue #786)。
--
-- ADR-0004(スキーマ・パー・サービス)に従い、identity-serviceは専用スキーマlbs_identityを
-- 自身のFlywayで管理する。#561([B3] identity-serviceを新設する)の受入基準にあった
-- 「lbs_identityスキーマをFlywayで管理する」は未達のままクローズされており、それまで
-- identity-serviceは分割前の旧スキーマ(lets_blog)を直接参照し続けていた。
--
-- DDLは移行元(lets_blogスキーマ)の実テーブル定義をSHOW CREATE TABLEで取得したものに
-- 合わせている。カラム・型・制約を変えると、既存データの移行
-- (scripts/migrate-identity-tables-to-lbs-identity.sql)が通らなくなるため、
-- この時点では**一切変更しない**(改善が必要なら移行完了後に別マイグレーションで行う)。
--
-- 作成順序: FK制約があるため参照される側を先に作る
-- (roles -> users -> role_permissions -> user_roles -> project_users -> user_site_authors)。

CREATE TABLE roles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description TEXT,
    PRIMARY KEY (id),
    UNIQUE KEY role_name (role_name),
    KEY idx_role_name (role_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    keycloak_sub VARCHAR(255) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT '1',
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    first_name VARCHAR(100) DEFAULT NULL,
    last_name VARCHAR(100) DEFAULT NULL,
    display_name VARCHAR(100) DEFAULT NULL,
    nickname VARCHAR(100) DEFAULT NULL,
    website_url VARCHAR(500) DEFAULT NULL,
    bio TEXT,
    locale VARCHAR(10) DEFAULT 'ja_JP',
    timezone VARCHAR(50) DEFAULT 'Asia/Tokyo',
    avatar_url VARCHAR(500) DEFAULT NULL,
    department VARCHAR(100) DEFAULT NULL,
    position VARCHAR(100) DEFAULT NULL,
    social_links JSON DEFAULT NULL,
    custom_links JSON DEFAULT NULL,
    -- GitHubトークンはAPP_ENCRYPTION_KEYで暗号化して保持する(legacy-apiと同一の鍵を共有)。
    github_token_encrypted VARBINARY(1024) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY email (email),
    UNIQUE KEY keycloak_sub (keycloak_sub)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY fk_user_roles_role (role_id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- project_id / site_id は project-service(lbs_project)が所有するprojects/sitesを指すが、
-- スキーマを跨ぐためFK制約は張らない(ADR-0004。移行元でもprojects側へのFKは無く、
-- users側へのFKのみが張られていた)。
CREATE TABLE project_users (
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    wp_role VARCHAR(50) NOT NULL DEFAULT 'contributor',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, user_id),
    KEY user_id (user_id),
    CONSTRAINT project_users_ibfk_2 FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_site_authors (
    user_id BIGINT NOT NULL,
    site_id BIGINT NOT NULL,
    cms_author_id VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, site_id),
    KEY site_id (site_id),
    CONSTRAINT user_site_authors_ibfk_1 FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
