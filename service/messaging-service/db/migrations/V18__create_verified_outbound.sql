CREATE TABLE messaging_destination (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    binding_key VARCHAR(128) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    label VARCHAR(120) NOT NULL,
    secret_cipher TEXT NOT NULL,
    verification_reference VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_ms BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL,
    CONSTRAINT uk_destination_binding UNIQUE(owner_id, binding_key)
);
CREATE INDEX idx_destination_owner ON messaging_destination(owner_id, created_ms);
CREATE TABLE messaging_outbound (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    destination_id CHAR(36) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    generation INT NOT NULL DEFAULT 1,
    task_id CHAR(36),
    attempt_id CHAR(36),
    last_fence BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    provider_reference VARCHAR(255),
    expires_ms BIGINT NOT NULL,
    next_attempt_ms BIGINT NOT NULL,
    relay_token CHAR(36),
    relay_until_ms BIGINT,
    relay_attempts INT NOT NULL DEFAULT 0,
    created_ms BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL,
    CONSTRAINT uk_outbound_key UNIQUE(owner_id, idempotency_key),
    CONSTRAINT fk_outbound_destination FOREIGN KEY(destination_id) REFERENCES messaging_destination(id)
);
CREATE INDEX idx_outbound_relay ON messaging_outbound(status, next_attempt_ms, relay_until_ms);
CREATE TABLE messaging_outbound_attempt (
    id CHAR(36) PRIMARY KEY,
    delivery_id CHAR(36) NOT NULL,
    generation INT NOT NULL,
    fencing_token BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_code VARCHAR(64),
    resolution VARCHAR(255),
    started_ms BIGINT NOT NULL,
    finished_ms BIGINT,
    CONSTRAINT uk_outbound_attempt UNIQUE(delivery_id, generation)
);
