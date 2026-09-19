CREATE TABLE IF NOT EXISTS t_registration_mail_delivery_outbox (
    verification_id BIGINT PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    encrypted_payload TEXT NOT NULL,
    nonce VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    claimed_until DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_registration_mail_delivery_idempotency (idempotency_key),
    KEY idx_registration_mail_delivery_ready (status, available_at)
);
