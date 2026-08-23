CREATE TABLE download_request (
    id CHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    source_type VARCHAR(64) NOT NULL,
    source_key VARCHAR(255) NOT NULL,
    request_kind VARCHAR(64) NOT NULL,
    parameters_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_download_source (source_type, source_key, request_kind)
);

CREATE TABLE download_item (
    id CHAR(36) PRIMARY KEY,
    download_request_id CHAR(36) NOT NULL,
    source_index INT NOT NULL,
    source_url TEXT,
    file_name VARCHAR(255),
    declared_size BIGINT,
    content_sha256 CHAR(64),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_download_item_request FOREIGN KEY (download_request_id) REFERENCES download_request(id),
    UNIQUE KEY uk_download_item_source (download_request_id, source_index)
);

CREATE TABLE source_reference (
    id CHAR(36) PRIMARY KEY,
    download_request_id CHAR(36) NOT NULL,
    reference_type VARCHAR(64) NOT NULL,
    reference_key VARCHAR(255) NOT NULL,
    metadata_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_source_reference_request FOREIGN KEY (download_request_id) REFERENCES download_request(id),
    UNIQUE KEY uk_source_reference (reference_type, reference_key)
);

CREATE TABLE download_task_binding (
    download_request_id CHAR(36) NOT NULL,
    task_instance_id CHAR(36) NOT NULL,
    binding_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (download_request_id, task_instance_id),
    CONSTRAINT fk_download_task_request FOREIGN KEY (download_request_id) REFERENCES download_request(id)
);

CREATE TABLE download_outbox (
    id CHAR(36) PRIMARY KEY,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    INDEX idx_download_outbox_status (status, created_at)
);
