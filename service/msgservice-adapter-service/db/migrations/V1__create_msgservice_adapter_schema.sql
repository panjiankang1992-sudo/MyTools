CREATE TABLE legacy_inbound_snapshot (
    sequence_id BIGINT NOT NULL AUTO_INCREMENT,
    source_system VARCHAR(64) NOT NULL,
    legacy_message_id VARCHAR(255) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sequence_id),
    UNIQUE KEY uk_legacy_inbound_identity (source_system, legacy_message_id),
    KEY idx_legacy_inbound_captured (captured_at, sequence_id)
);
