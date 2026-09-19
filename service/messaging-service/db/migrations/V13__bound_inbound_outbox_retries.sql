ALTER TABLE messaging_outbox
    ADD (
        delivery_attempts INT NOT NULL DEFAULT 0,
        next_attempt_at TIMESTAMP(6) NULL,
        last_error_code VARCHAR(128) NULL
    )
/*!80000 , ADD INDEX idx_messaging_outbox_relay
        (published_at, event_type, next_attempt_at, created_at) */;
