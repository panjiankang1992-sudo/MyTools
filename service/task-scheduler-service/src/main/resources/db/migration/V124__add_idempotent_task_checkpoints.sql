ALTER TABLE task_checkpoint
    ADD COLUMN checkpoint_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE task_checkpoint
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_checkpoint
    ADD COLUMN execution_id CHAR(36);
ALTER TABLE task_checkpoint
    ADD COLUMN last_request_id CHAR(36);

CREATE TABLE task_checkpoint_write (
    request_id CHAR(36) PRIMARY KEY,
    task_instance_id CHAR(36) NOT NULL,
    checkpoint_key VARCHAR(128) NOT NULL,
    expected_version BIGINT NOT NULL,
    applied_version BIGINT NOT NULL,
    checkpoint_json TEXT NOT NULL,
    fencing_token BIGINT NOT NULL,
    execution_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_checkpoint_write_instance
        FOREIGN KEY (task_instance_id) REFERENCES task_instance(id),
    CONSTRAINT fk_task_checkpoint_write_execution
        FOREIGN KEY (execution_id) REFERENCES task_execution(id)
);

CREATE INDEX idx_task_checkpoint_write_task
    ON task_checkpoint_write(task_instance_id, checkpoint_key, created_at);
