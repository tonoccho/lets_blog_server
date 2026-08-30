-- 旧認証機構の撤去(issue #566)。ADR-0003(旧認証機構は並行運用せず一括で切り替える)に基づき、
-- 旧ヘッダベースのAPIキー認証(api_keys)・独自TOTP(two_factor_secrets)・独自パスワードリセット
-- (password_reset_tokens)はいずれもKeycloak(OIDC)に置き換わったため撤去する。
--
-- いずれのテーブルもusers(id)へのFOREIGN KEY(ON DELETE CASCADE)を持つのみで、他のテーブルから
-- 参照されていないため、参照元を先に外す必要は無い。
DROP TABLE IF EXISTS api_keys;
DROP TABLE IF EXISTS two_factor_secrets;
DROP TABLE IF EXISTS password_reset_tokens;
