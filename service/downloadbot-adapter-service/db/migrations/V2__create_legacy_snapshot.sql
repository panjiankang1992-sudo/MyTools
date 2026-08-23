CREATE TABLE legacy_snapshot (
    id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_schema VARCHAR(128) NOT NULL,
    source_version VARCHAR(64) NOT NULL,
    high_water_json JSON NOT NULL,
    item_count INT NOT NULL DEFAULT 0,
    rejection_count INT NOT NULL DEFAULT 0,
    collection_sha256 CHAR(64) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    sealed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    KEY idx_legacy_snapshot_status_started (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE legacy_snapshot_item (
    id CHAR(36) NOT NULL,
    snapshot_id CHAR(36) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    legacy_id VARCHAR(255) NOT NULL,
    source_key VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_legacy_snapshot_item_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES legacy_snapshot(id),
    UNIQUE KEY uk_legacy_snapshot_item (snapshot_id, item_type, legacy_id),
    KEY idx_legacy_snapshot_item_source (snapshot_id, source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE legacy_snapshot_rejection (
    id CHAR(36) NOT NULL,
    snapshot_id CHAR(36) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    legacy_id VARCHAR(255) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    detail VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_legacy_snapshot_rejection_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES legacy_snapshot(id),
    UNIQUE KEY uk_legacy_snapshot_rejection (snapshot_id, item_type, legacy_id),
    KEY idx_legacy_snapshot_rejection_reason (snapshot_id, reason_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
