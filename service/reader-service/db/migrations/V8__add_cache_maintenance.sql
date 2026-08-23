CREATE TABLE chapter_cache_maintenance (
    id CHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    maintenance_type VARCHAR(32) NOT NULL,
    cutoff_at TIMESTAMP(6) NOT NULL,
    batch_size INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    deleted_count BIGINT NOT NULL,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_chapter_cache_maintenance_key (idempotency_key)
);

CREATE INDEX idx_chapter_cache_maintenance_status ON chapter_cache_maintenance (status, created_at);
