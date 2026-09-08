CREATE TABLE audiobook_export (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    generation_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    format VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(64) NOT NULL,
    task_instance_id CHAR(36),
    archive_storage_uri VARCHAR(2048),
    archive_content_sha256 CHAR(64),
    archive_size_bytes BIGINT,
    chapter_count INT NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_export_generation FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id),
    CONSTRAINT uk_audiobook_export_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_audiobook_export_generation ON audiobook_export (owner_id, generation_id, created_at);
