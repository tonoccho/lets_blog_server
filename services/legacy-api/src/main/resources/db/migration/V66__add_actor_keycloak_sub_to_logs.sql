-- 監査ログのactor解決をJWTベースへ変更する(issue #569)。JWTのsubクレームを、ローカルUserへの
-- 解決結果(user_id)とは独立に保持し、User未同期・削除済みでもJWT起点の追跡性を保てるようにする。
-- frontend_error_logsはこれまでactor概念自体を持たなかったため、user_idも合わせて追加する。
-- いずれの列もNULL許容で、既存レコードの移行(バックフィル)は行わずNULLのままとする
-- (切替時点で区切る。X-Actor-Idヘッダー経由で記録された行にはJWTのsubに相当する値が
-- 存在しないため、これらは今後もNULLのままとなる想定)。
ALTER TABLE audit_logs ADD COLUMN actor_keycloak_sub VARCHAR(255) NULL AFTER user_id;
ALTER TABLE operation_logs ADD COLUMN actor_keycloak_sub VARCHAR(255) NULL AFTER user_id;
ALTER TABLE frontend_error_logs ADD COLUMN user_id BIGINT NULL AFTER component_stack;
ALTER TABLE frontend_error_logs ADD COLUMN actor_keycloak_sub VARCHAR(255) NULL AFTER user_id;
