CREATE TABLE onebot_inbound_event (
    id CHAR(36) PRIMARY KEY,
    account_key VARCHAR(128) NOT NULL,
    event_key CHAR(64) NOT NULL,
    source_message_id VARCHAR(512) NOT NULL,
    payload_json JSON NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    attempt_token CHAR(36) NULL,
    lease_until TIMESTAMP(6) NULL,
    last_error_code VARCHAR(128) NULL,
    delivered_at TIMESTAMP(6) NULL,
    dead_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_onebot_inbound_event_key UNIQUE (account_key, event_key),
    INDEX idx_onebot_inbound_event_due (status, next_attempt_at, created_at, id),
    INDEX idx_onebot_inbound_event_lease (status, lease_until),
    INDEX idx_onebot_inbound_event_terminal (status, updated_at),
    CONSTRAINT fk_onebot_inbound_event_account FOREIGN KEY (account_key)
        REFERENCES onebot_account (external_key)
) ENGINE=InnoDB;
