ALTER TABLE automation_outbox
    ADD COLUMN delivery_attempts INT NOT NULL DEFAULT 0;
