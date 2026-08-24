CREATE TABLE legacy_live_bridge_cursor (
    bridge_name VARCHAR(64) NOT NULL,
    last_legacy_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (bridge_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE legacy_live_bridge_rejection (
    id CHAR(36) NOT NULL,
    bridge_name VARCHAR(64) NOT NULL,
    legacy_id BIGINT UNSIGNED NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    detail VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_live_bridge_rejection (bridge_name, legacy_id),
    KEY idx_live_bridge_rejection_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
