ALTER TABLE task_instance
    ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0;

ALTER TABLE task_instance
    ADD COLUMN max_dispatch_attempts INT NOT NULL DEFAULT 3;

CREATE INDEX idx_task_execution_lease ON task_execution(status, lease_until);
