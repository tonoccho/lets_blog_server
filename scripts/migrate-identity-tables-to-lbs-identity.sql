-- identity-serviceへのスキーマ所有権移管(issue #786)データ移行スクリプト。
--
-- 目的: users/roles/role_permissions/user_roles/project_users/user_site_authors の6テーブルを、
-- 分割前のlets_blogスキーマから、identity-serviceが所有するlbs_identityスキーマへコピーする。
--
-- 経緯: #561([B3] identity-serviceを新設する)の受入基準には「lbs_identityスキーマをFlywayで
-- 管理する」があったが未達のままクローズされ、identity-serviceは旧スキーマを直接参照し続けていた。
-- そのため旧スキーマのこれら6テーブルは「移行漏れの残骸」ではなく**現に参照されている生きた
-- テーブル**であり、#785/#583で旧スキーマを削除する前に本移行を済ませる必要がある。
--
-- lbs_identityスキーマの専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、逆も同様の
-- ため、アプリケーションのFlywayではこのクロススキーマのINSERT ... SELECTを実行できない。
-- 両スキーマへアクセスできる特権アカウント(root)で本スクリプトを直接実行する運用とする
-- (#572/#573/#574/#576/#577と同じパターン)。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. identity-serviceのFlyway V1(services/identity/src/main/resources/db/migration/
--      V1__create_identity_tables.sql)を適用済みで、lbs_identityスキーマに空の6テーブルが
--      存在すること。
--   2. docker-compose.ymlのidentity-serviceのデータソースをlbs_identityへ切り替える**前**に
--      本スクリプトを実行すること。切り替え後に実行すると、切り替え〜移行の間に旧スキーマへ
--      書かれた変更が失われる。
--   3. legacy-apiも同じusersテーブルを参照している(#786時点)。legacy-api側の参照を断つのは
--      #583(legacy-api解体)のスコープであり、本移行の時点では**両スキーマに同じデータが
--      並存する**状態になる。その間にどちらか一方へ書き込みが入ると乖離するため、
--      移行はサービス停止中またはアクセスの無い時間帯に行うこと。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-identity-tables-to-lbs-identity.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_identity側の件数が一致していることを確認すること。
--
-- 冪等性: 対象6テーブルはV1で作成された直後の空テーブルであることを前提とする(2回目以降の実行は
-- 主キー/UNIQUE制約違反でエラーになる)。再実行が必要な場合は事前にlbs_identity側を
-- TRUNCATEすること。ただしFK依存があるためTRUNCATE順はINSERT順の逆
-- (user_site_authors -> project_users -> user_roles -> role_permissions -> users -> roles)とし、
-- SET FOREIGN_KEY_CHECKS=0 を使うこと。
--
-- INSERT順序: role_permissions/user_roles -> roles、user_roles/project_users/user_site_authors
-- -> users の実FK制約があるため、参照される側(roles, users)を先に投入する。

INSERT INTO lbs_identity.roles (id, role_name, display_name, description)
SELECT id, role_name, display_name, description
FROM lets_blog.roles;

INSERT INTO lbs_identity.users
    (id, email, keycloak_sub, enabled, password_hash, role, created_at, updated_at,
     first_name, last_name, display_name, nickname, website_url, bio, locale, timezone,
     avatar_url, department, position, social_links, custom_links, github_token_encrypted)
SELECT
    id, email, keycloak_sub, enabled, password_hash, role, created_at, updated_at,
    first_name, last_name, display_name, nickname, website_url, bio, locale, timezone,
    avatar_url, department, position, social_links, custom_links, github_token_encrypted
FROM lets_blog.users;

INSERT INTO lbs_identity.role_permissions (role_id, permission)
SELECT role_id, permission
FROM lets_blog.role_permissions;

INSERT INTO lbs_identity.user_roles (user_id, role_id)
SELECT user_id, role_id
FROM lets_blog.user_roles;

INSERT INTO lbs_identity.project_users (project_id, user_id, wp_role, created_at)
SELECT project_id, user_id, wp_role, created_at
FROM lets_blog.project_users;

INSERT INTO lbs_identity.user_site_authors (user_id, site_id, cms_author_id, updated_at)
SELECT user_id, site_id, cms_author_id, updated_at
FROM lets_blog.user_site_authors;

-- 件数突合。すべての行で source と target が一致していること。
SELECT 'roles' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.roles) AS source_count,
       (SELECT COUNT(*) FROM lbs_identity.roles) AS target_count
UNION ALL
SELECT 'users',
       (SELECT COUNT(*) FROM lets_blog.users),
       (SELECT COUNT(*) FROM lbs_identity.users)
UNION ALL
SELECT 'role_permissions',
       (SELECT COUNT(*) FROM lets_blog.role_permissions),
       (SELECT COUNT(*) FROM lbs_identity.role_permissions)
UNION ALL
SELECT 'user_roles',
       (SELECT COUNT(*) FROM lets_blog.user_roles),
       (SELECT COUNT(*) FROM lbs_identity.user_roles)
UNION ALL
SELECT 'project_users',
       (SELECT COUNT(*) FROM lets_blog.project_users),
       (SELECT COUNT(*) FROM lbs_identity.project_users)
UNION ALL
SELECT 'user_site_authors',
       (SELECT COUNT(*) FROM lets_blog.user_site_authors),
       (SELECT COUNT(*) FROM lbs_identity.user_site_authors);
