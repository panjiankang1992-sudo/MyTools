CREATE TABLE storage_provider (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    provider_type VARCHAR(32) NOT NULL,
    remote_key VARCHAR(128) NOT NULL UNIQUE,
    secret_ref VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE storage_operation (
    id CHAR(36) PRIMARY KEY,
    provider_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    operation_type VARCHAR(64) NOT NULL,
    source_path VARCHAR(2048),
    target_path VARCHAR(2048),
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    result_json JSON,
    error_code VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_storage_operation_provider FOREIGN KEY (provider_id) REFERENCES storage_provider(id),
    INDEX idx_storage_operation_status (status, updated_at)
);
