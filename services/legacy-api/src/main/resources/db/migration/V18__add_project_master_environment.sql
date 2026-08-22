ALTER TABLE projects
    ADD COLUMN master_environment VARCHAR(20) NOT NULL DEFAULT 'test';
