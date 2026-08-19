ALTER TABLE projects
    ADD COLUMN buffer_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN buffer_access_token_encrypted VARBINARY(1024) NULL,
    ADD COLUMN buffer_profile_ids VARCHAR(500) NULL,
    ADD COLUMN buffer_post_delay_minutes INT NULL,
    ADD COLUMN buffer_message_template VARCHAR(500) NULL;
