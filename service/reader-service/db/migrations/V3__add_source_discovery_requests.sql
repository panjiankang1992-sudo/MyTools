CREATE TABLE book_source_discovery_request (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    source_url VARCHAR(4096) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    processed_count INT NOT NULL DEFAULT 0,
    saved_count INT NOT NULL DEFAULT 0,
    rejected_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_source_discovery_idempotency (owner_id, idempotency_key)
);
