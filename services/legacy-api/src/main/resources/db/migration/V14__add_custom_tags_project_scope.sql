ALTER TABLE custom_tags ADD COLUMN project_id BIGINT;
ALTER TABLE custom_tags ADD FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE custom_tags DROP INDEX tag_name;
ALTER TABLE custom_tags ADD UNIQUE KEY unique_tag_per_project (tag_name, project_id);
