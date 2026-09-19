ALTER TABLE automation_action
    ADD (
        next_attempt_at TIMESTAMP(6) NULL
    )
/*!80000 , ADD INDEX idx_automation_action_retry
        (automation_run_id, status, next_attempt_at, updated_at) */;
