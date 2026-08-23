CREATE TABLE channel_account (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    account_key VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    credential_ref VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_channel_account_key (owner_id, channel_type, account_key)
);

CREATE TABLE delivery_request (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    account_id CHAR(36),
    recipient VARCHAR(1024) NOT NULL,
    subject_text VARCHAR(998),
    body_text MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    provider_message_id VARCHAR(512),
    last_error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_delivery_account FOREIGN KEY (account_id) REFERENCES channel_account(id),
    UNIQUE KEY uk_delivery_idempotency (owner_id, idempotency_key)
);

CREATE TABLE delivery_attempt (
    id CHAR(36) PRIMARY KEY,
    delivery_request_id CHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_message_id VARCHAR(512),
    error_code VARCHAR(64),
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6),
    CONSTRAINT fk_delivery_attempt_request FOREIGN KEY (delivery_request_id) REFERENCES delivery_request(id),
    UNIQUE KEY uk_delivery_attempt_number (delivery_request_id, attempt_number)
);

CREATE TABLE inbound_message (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    external_message_id VARCHAR(512) NOT NULL,
    conversation_key VARCHAR(512) NOT NULL,
    sender_ref VARCHAR(1024) NOT NULL,
    subject_text VARCHAR(998),
    body_text MEDIUMTEXT NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_inbound_message_external (owner_id, channel_type, external_message_id)
);

CREATE TABLE messaging_outbox (
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6)
);

CREATE INDEX idx_messaging_outbox_unpublished ON messaging_outbox (published_at, created_at);
