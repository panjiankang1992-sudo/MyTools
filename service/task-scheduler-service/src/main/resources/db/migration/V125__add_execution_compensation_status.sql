ALTER TABLE task_execution
    ADD COLUMN compensation_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED';
ALTER TABLE task_execution
    ADD COLUMN compensation_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE task_execution
    ADD COLUMN compensation_error_code VARCHAR(128);

CREATE INDEX idx_task_execution_compensation_attention
    ON task_execution(compensation_required, compensation_status, finished_at);
