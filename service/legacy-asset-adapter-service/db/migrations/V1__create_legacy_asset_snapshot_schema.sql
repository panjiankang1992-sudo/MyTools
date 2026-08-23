CREATE TABLE legacy_asset_snapshot (
    snapshot_id VARCHAR(128) PRIMARY KEY,
    source_system VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    high_water_id BIGINT NOT NULL,
    captured_count BIGINT NOT NULL,
    rejected_count BIGINT NOT NULL,
    digest_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    sealed_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE legacy_asset_snapshot_item (
    sequence_id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id VARCHAR(128) NOT NULL,
    legacy_asset_id VARCHAR(255) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sequence_id),
    CONSTRAINT fk_legacy_asset_snapshot_item_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES legacy_asset_snapshot(snapshot_id),
    UNIQUE KEY uk_legacy_asset_snapshot_identity (snapshot_id, legacy_asset_id)
);

CREATE INDEX idx_legacy_asset_snapshot_page
    ON legacy_asset_snapshot_item (snapshot_id, sequence_id);

CREATE TABLE legacy_asset_snapshot_rejection (
    id CHAR(36) PRIMARY KEY,
    snapshot_id VARCHAR(128) NOT NULL,
    legacy_asset_id VARCHAR(255) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_legacy_asset_snapshot_rejection_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES legacy_asset_snapshot(snapshot_id)
);
