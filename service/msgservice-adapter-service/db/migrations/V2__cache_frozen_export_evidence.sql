CREATE TABLE legacy_inbound_export_snapshot (
    high_water_sequence BIGINT NOT NULL,
    item_count BIGINT NOT NULL,
    collection_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (high_water_sequence)
);
