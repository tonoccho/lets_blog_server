-- generation_jobs に所有者(ジョブを起こした利用者)を持たせる(issue #1406)。
-- ADR-0004によりクロススキーマFKは持てないため、identity-serviceのユーザーIDのみを保持する
-- (CurrentActorService#getCurrentActorId と同じ値)。
-- 既存行は NULL のまま = 所有者不明。消さず、推定・backfillもしない。
-- 所有者不明の行は管理者にだけ見える(GenerationJobController)。
ALTER TABLE generation_jobs
    ADD COLUMN owner_user_id BIGINT NULL,
    ADD INDEX idx_generation_jobs_owner_user_id (owner_user_id);
