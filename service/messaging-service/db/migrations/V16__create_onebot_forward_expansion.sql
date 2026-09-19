CREATE TABLE onebot_forward_expansion (
    id CHAR(36) PRIMARY KEY,
    inbound_message_id CHAR(36) NOT NULL,
    forward_id VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    attempt_token CHAR(36),
    next_attempt_at TIMESTAMP(6),
    lease_expires_at TIMESTAMP(6),
    last_error_code VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_onebot_forward_message
        FOREIGN KEY (inbound_message_id) REFERENCES inbound_message(id),
    UNIQUE KEY uk_onebot_forward_reference (inbound_message_id, forward_id),
    INDEX idx_onebot_forward_due (status, next_attempt_at, lease_expires_at, created_at)
);
