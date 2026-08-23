ALTER TABLE storage_root ADD COLUMN node_affinity_label VARCHAR(128) NOT NULL DEFAULT 'storage.mount.managed';
ALTER TABLE storage_root ADD COLUMN node_affinity_value VARCHAR(256) NOT NULL DEFAULT 'present';

CREATE TABLE storage_checksum_operation (
    id CHAR(36) PRIMARY KEY,
    root_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    relative_path VARCHAR(2048) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    size_bytes BIGINT,
    content_sha256 CHAR(64),
    error_code VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_checksum_operation_root FOREIGN KEY (root_id) REFERENCES storage_root(id)
);

CREATE INDEX idx_checksum_operation_status ON storage_checksum_operation(status, created_at);
