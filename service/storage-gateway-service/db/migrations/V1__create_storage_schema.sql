CREATE TABLE storage_root (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    purpose VARCHAR(64) NOT NULL,
    base_path VARCHAR(4096) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE storage_upload (
    id CHAR(36) PRIMARY KEY,
    root_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    relative_path VARCHAR(2048) NOT NULL,
    expected_size BIGINT NOT NULL,
    expected_sha256 CHAR(64),
    actual_size BIGINT,
    actual_sha256 CHAR(64),
    status VARCHAR(32) NOT NULL,
    temporary_path VARCHAR(4096),
    final_path VARCHAR(4096),
    error_code VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_storage_upload_root FOREIGN KEY (root_id) REFERENCES storage_root(id)
);
