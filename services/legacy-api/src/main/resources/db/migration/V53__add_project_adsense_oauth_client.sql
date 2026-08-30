ALTER TABLE projects
    ADD COLUMN adsense_oauth_client_id VARCHAR(255) NULL,
    ADD COLUMN adsense_oauth_client_secret_encrypted VARBINARY(1024) NULL;
