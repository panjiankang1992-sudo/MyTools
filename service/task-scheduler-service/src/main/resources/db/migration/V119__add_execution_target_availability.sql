ALTER TABLE task_execution_target
    ADD COLUMN available_at TIMESTAMP(6) NULL;

CREATE INDEX idx_task_execution_target_claimable
    ON task_execution_target(node_id, status, available_at, created_at);
