ALTER TABLE projects
    ADD COLUMN github_token_encrypted VARBINARY(1024) NULL,
    ADD COLUMN brave_search_api_key_encrypted VARBINARY(1024) NULL;
