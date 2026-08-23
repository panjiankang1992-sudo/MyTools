CREATE TABLE inbound_history_migration (
    id CHAR(36) PRIMARY KEY,
    migration_key VARCHAR(128) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_message_id VARCHAR(255) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    inbound_message_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_history_migration_message FOREIGN KEY (inbound_message_id) REFERENCES inbound_message(id),
    UNIQUE KEY uk_history_migration_source (source_system, legacy_message_id),
    KEY idx_history_migration_key (migration_key, created_at)
);
