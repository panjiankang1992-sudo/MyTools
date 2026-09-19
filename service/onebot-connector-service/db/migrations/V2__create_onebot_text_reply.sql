CREATE TABLE onebot_text_reply (
    id CHAR(36) PRIMARY KEY,
    account_key VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(512) NOT NULL,
    text_digest CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    attempt_token CHAR(36) NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    provider_message_id VARCHAR(512) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_onebot_text_reply_key UNIQUE (account_key, idempotency_key),
    INDEX idx_onebot_text_reply_status_due (status, next_attempt_at, updated_at),
    CONSTRAINT fk_onebot_text_reply_account FOREIGN KEY (account_key)
        REFERENCES onebot_account (external_key)
) ENGINE=InnoDB;
