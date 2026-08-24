CREATE TABLE legacy_outbound_snapshot (
    sequence_id BIGINT NOT NULL AUTO_INCREMENT,
    source_system VARCHAR(64) NOT NULL,
    legacy_message_id VARCHAR(255) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sequence_id),
    UNIQUE KEY uk_legacy_outbound_identity (source_system, legacy_message_id),
    KEY idx_legacy_outbound_captured (captured_at, sequence_id)
);

CREATE TABLE legacy_outbound_export_snapshot (
    high_water_sequence BIGINT NOT NULL,
    protocol_version VARCHAR(64) NOT NULL,
    item_count BIGINT NOT NULL,
    collection_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (high_water_sequence, protocol_version)
);
