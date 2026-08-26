ALTER TABLE automation_action
    ADD COLUMN last_progress_percent INT NOT NULL DEFAULT -1;

ALTER TABLE automation_action
    ADD CONSTRAINT ck_automation_action_progress CHECK (last_progress_percent BETWEEN -1 AND 100);
