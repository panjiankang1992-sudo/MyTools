ALTER TABLE task_instance
    ADD COLUMN started_at TIMESTAMP(6);

CREATE INDEX idx_task_instance_timeout ON task_instance(status, started_at);
