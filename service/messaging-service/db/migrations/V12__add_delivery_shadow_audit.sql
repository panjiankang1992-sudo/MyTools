CREATE TABLE IF NOT EXISTS delivery_shadow_audit (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    recipient_hash CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    legacy_outcome VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_delivery_shadow_owner_key UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_delivery_shadow_created ON delivery_shadow_audit(created_at);
