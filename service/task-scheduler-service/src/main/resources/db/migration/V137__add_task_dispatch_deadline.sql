ALTER TABLE task_instance
    ADD COLUMN dispatch_deadline_at TIMESTAMP(6) NULL;

CREATE INDEX idx_task_instance_dispatch_deadline
    ON task_instance (status, started_at, dispatch_deadline_at);
