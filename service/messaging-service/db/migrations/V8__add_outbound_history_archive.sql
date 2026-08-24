CREATE TABLE outbound_message_history (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_message_id VARCHAR(255) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    sender_ref VARCHAR(1024),
    recipients_json JSON NOT NULL,
    subject_text VARCHAR(998),
    body_text MEDIUMTEXT,
    body_html MEDIUMTEXT,
    attachments_json JSON NOT NULL,
    template_ref VARCHAR(255),
    provider_message_id VARCHAR(512),
    error_code VARCHAR(255),
    sent_at TIMESTAMP(6),
    legacy_created_at TIMESTAMP(6) NOT NULL,
    archived_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_outbound_history_source (source_system, legacy_message_id),
    KEY idx_outbound_history_owner_time (owner_id, legacy_created_at)
);

CREATE TABLE outbound_history_migration (
    id CHAR(36) PRIMARY KEY,
    migration_key VARCHAR(128) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_message_id VARCHAR(255) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    outbound_history_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_outbound_history_migration_message
        FOREIGN KEY (outbound_history_id) REFERENCES outbound_message_history(id),
    UNIQUE KEY uk_outbound_history_migration_source (source_system, legacy_message_id),
    KEY idx_outbound_history_migration_key (migration_key, created_at)
);
