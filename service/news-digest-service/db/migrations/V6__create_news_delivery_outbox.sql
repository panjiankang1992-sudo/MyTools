CREATE TABLE news_delivery_settings (
    owner_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL
);
CREATE TABLE news_subscription_target (
    owner_id BIGINT NOT NULL,
    destination_id CHAR(36) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    render_mode VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY(owner_id,destination_id)
);
CREATE TABLE news_delivery_batch (
    id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    subscription_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    destination_id CHAR(36) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    render_mode VARCHAR(16) NOT NULL,
    render_version VARCHAR(32),
    edition_id CHAR(36),
    state VARCHAR(24) NOT NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    expires_ms BIGINT NOT NULL,
    created_ms BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL,
    error_code VARCHAR(64),
    CONSTRAINT uk_news_delivery_batch UNIQUE(run_id,destination_id)
);
CREATE INDEX idx_news_delivery_owner ON news_delivery_batch(owner_id,created_ms);
CREATE TABLE news_dispatch_outbox (
    id CHAR(36) PRIMARY KEY,
    batch_id CHAR(36) NOT NULL,
    part_index INT NOT NULL,
    part_count INT NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    remote_id CHAR(36),
    remote_generation INT NOT NULL DEFAULT 1,
    retry_generation INT,
    retry_ack BOOLEAN NOT NULL DEFAULT FALSE,
    available_ms BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_token CHAR(36),
    lease_until_ms BIGINT,
    error_code VARCHAR(64),
    updated_ms BIGINT NOT NULL,
    CONSTRAINT uk_news_dispatch_part UNIQUE(batch_id,part_index)
);
CREATE INDEX idx_news_dispatch_due ON news_dispatch_outbox(available_ms,lease_until_ms);
