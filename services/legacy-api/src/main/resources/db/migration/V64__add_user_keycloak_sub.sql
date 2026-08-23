-- identity-service(#561)がKeycloakのユーザー識別子(sub claim)と突き合わせるための列。
-- 現時点ではまだKeycloakユーザー同期(#562)が実装されていないため、既存ユーザーはNULLのまま。
ALTER TABLE users ADD COLUMN keycloak_sub VARCHAR(255) NULL UNIQUE AFTER email;
