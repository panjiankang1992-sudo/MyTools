ALTER TABLE task_instance
    ADD COLUMN available_at TIMESTAMP(6) NULL;

CREATE INDEX idx_task_instance_claimable
    ON task_instance(status, priority, available_at, created_at);

ALTER TABLE task_execution
    ADD COLUMN claim_request_id CHAR(36) NULL;

CREATE UNIQUE INDEX uk_task_execution_claim_request
    ON task_execution(claim_request_id);
