ALTER TABLE automation_outbox
    ADD (
        next_attempt_at TIMESTAMP(6) NULL,
        last_error VARCHAR(128) NULL,
        dead_at TIMESTAMP(6) NULL
    )
/*!80000 , ADD INDEX idx_automation_outbox_delivery
        (published_at, dead_at, next_attempt_at, created_at) */;
