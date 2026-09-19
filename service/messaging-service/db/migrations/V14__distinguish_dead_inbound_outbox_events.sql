ALTER TABLE messaging_outbox
    ADD (
        dead_at TIMESTAMP(6) NULL
    )
/*!80000 , ADD INDEX idx_messaging_outbox_pending
        (published_at, dead_at, event_type, next_attempt_at, created_at) */;
