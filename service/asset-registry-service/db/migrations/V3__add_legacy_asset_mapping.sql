CREATE TABLE asset_legacy_mapping (
    id CHAR(36) PRIMARY KEY,
    migration_key VARCHAR(128) NOT NULL,
    source_snapshot_id VARCHAR(128) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_asset_id VARCHAR(255) NOT NULL,
    asset_id CHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_asset_legacy_mapping_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    UNIQUE KEY uk_asset_legacy_identity (source_system, legacy_asset_id)
);

CREATE INDEX idx_asset_legacy_target ON asset_legacy_mapping (asset_id, source_system);
