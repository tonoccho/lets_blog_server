ALTER TABLE projects
    ADD COLUMN adsense_account_id VARCHAR(64) NULL,
    ADD COLUMN adsense_refresh_token_encrypted VARBINARY(1024) NULL;
