CREATE TABLE legacy_reader_key_map (
    owner_id BIGINT NOT NULL,
    legacy_book_id VARCHAR(1000) NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    sync_key VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_id, legacy_book_id),
    CONSTRAINT fk_legacy_reader_shelf FOREIGN KEY (shelf_book_id) REFERENCES shelf_book(id)
);

CREATE TABLE legacy_reader_migration_item (
    entity_type VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    legacy_key VARCHAR(1000) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    target_id CHAR(36),
    migrated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (entity_type, owner_id, idempotency_hash)
);
