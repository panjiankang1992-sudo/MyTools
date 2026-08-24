CREATE TABLE legacy_media_migration (
    id CHAR(36) NOT NULL,
    migration_key VARCHAR(128) NOT NULL,
    source_snapshot_id VARCHAR(128) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_asset_id VARCHAR(255) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    media_item_id CHAR(36) NOT NULL,
    tag_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_legacy_media_source UNIQUE (source_system, legacy_asset_id),
    CONSTRAINT fk_legacy_media_item FOREIGN KEY (media_item_id) REFERENCES media_item(id)
);
CREATE INDEX idx_legacy_media_migration_key ON legacy_media_migration(migration_key);
