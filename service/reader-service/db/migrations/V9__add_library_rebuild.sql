CREATE TABLE library_rebuild_request (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    snapshot_at TIMESTAMP(6) NOT NULL,
    batch_size INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    indexed_count BIGINT NOT NULL,
    last_cursor CHAR(36),
    last_error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_library_rebuild_key (owner_id, idempotency_key)
);

CREATE TABLE library_index_generation (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    snapshot_at TIMESTAMP(6) NOT NULL,
    active BOOLEAN NOT NULL,
    entry_count BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    UNIQUE KEY uk_library_generation_owner_snapshot (owner_id, snapshot_at)
);

CREATE TABLE library_index_entry (
    generation_id CHAR(36) NOT NULL,
    ebook_asset_id CHAR(36) NOT NULL,
    book_key CHAR(64) NOT NULL,
    title VARCHAR(300) NOT NULL,
    author VARCHAR(200),
    format VARCHAR(32) NOT NULL,
    storage_uri VARCHAR(4096) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    metadata_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (generation_id, ebook_asset_id),
    CONSTRAINT fk_library_entry_generation FOREIGN KEY (generation_id) REFERENCES library_index_generation(id),
    CONSTRAINT fk_library_entry_asset FOREIGN KEY (ebook_asset_id) REFERENCES ebook_asset(id),
    UNIQUE KEY uk_library_entry_book (generation_id, book_key)
);

CREATE INDEX idx_library_generation_active ON library_index_generation (owner_id, active);
