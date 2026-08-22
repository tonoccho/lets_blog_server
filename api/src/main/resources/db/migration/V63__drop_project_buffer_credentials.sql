ALTER TABLE projects
    DROP COLUMN buffer_enabled,
    DROP COLUMN buffer_access_token_encrypted,
    DROP COLUMN buffer_profile_ids,
    DROP COLUMN buffer_post_delay_minutes,
    DROP COLUMN buffer_message_template;
