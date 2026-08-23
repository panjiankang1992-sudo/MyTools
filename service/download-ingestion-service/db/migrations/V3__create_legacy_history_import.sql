CREATE TABLE legacy_download_history (
    id CHAR(36) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    legacy_id VARCHAR(255) NOT NULL,
    source_key VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    first_migration_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_legacy_download_identity (source_system, item_type, legacy_id),
    KEY idx_legacy_download_source (source_system, source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE legacy_download_migration_rejection (
    id CHAR(36) NOT NULL,
    migration_key VARCHAR(128) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    legacy_id VARCHAR(255) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_legacy_download_rejection
        (migration_key, source_system, item_type, legacy_id),
    KEY idx_legacy_download_rejection_reason (migration_key, reason_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
