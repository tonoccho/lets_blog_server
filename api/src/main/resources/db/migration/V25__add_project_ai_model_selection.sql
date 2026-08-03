ALTER TABLE projects
    ADD COLUMN ollama_model VARCHAR(255) NULL,
    ADD COLUMN comfyui_checkpoint VARCHAR(255) NULL;
