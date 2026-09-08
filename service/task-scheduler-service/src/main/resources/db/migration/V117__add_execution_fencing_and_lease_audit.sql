ALTER TABLE task_execution
    ADD COLUMN fencing_token BIGINT NULL;

ALTER TABLE task_execution
    ADD COLUMN last_heartbeat_at TIMESTAMP(6) NULL;

ALTER TABLE task_execution
    ADD COLUMN lease_lost_at TIMESTAMP(6) NULL;

CREATE UNIQUE INDEX uk_task_execution_fencing
    ON task_execution(task_instance_id, fencing_token);
