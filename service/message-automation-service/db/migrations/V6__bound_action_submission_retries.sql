ALTER TABLE automation_action
    ADD COLUMN submission_attempts INT NOT NULL DEFAULT 0;
