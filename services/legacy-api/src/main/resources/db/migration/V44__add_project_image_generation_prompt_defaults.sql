ALTER TABLE projects
    ADD COLUMN default_negative_prompt VARCHAR(1000) NULL,
    ADD COLUMN default_quality_prompt VARCHAR(500) NULL;
