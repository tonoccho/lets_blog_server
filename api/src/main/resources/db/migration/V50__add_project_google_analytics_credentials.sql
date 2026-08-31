ALTER TABLE projects
    ADD COLUMN ga_property_id VARCHAR(64) NULL,
    ADD COLUMN ga_service_account_json_encrypted VARBINARY(4096) NULL;
