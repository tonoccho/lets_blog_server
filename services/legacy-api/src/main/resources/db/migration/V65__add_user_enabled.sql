-- Keycloakユーザー同期(#562)。「無効化(deactivate)」状態を表す列。Keycloak側でユーザーを
-- 無効化した場合、およびKeycloak側で削除されたユーザーを検出した場合(孤児検出/論理削除)に、
-- こちらもFALSEに揃える。既存ユーザーは全員有効(TRUE)のまま挙動が変わらないようにする。
ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER keycloak_sub;
