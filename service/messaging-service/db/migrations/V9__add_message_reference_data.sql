CREATE TABLE message_template (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_template_id VARCHAR(255) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    template_name VARCHAR(255) NOT NULL,
    description_text VARCHAR(2048),
    subject_text VARCHAR(998),
    body_text MEDIUMTEXT,
    body_html MEDIUMTEXT,
    variables_json JSON,
    legacy_created_at TIMESTAMP(6) NOT NULL,
    legacy_updated_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_message_template_legacy (source_system, legacy_template_id),
    KEY idx_message_template_owner (owner_id, channel_type, template_name)
);

CREATE TABLE known_recipient (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_recipient_id VARCHAR(255) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    recipient_address VARCHAR(1024) NOT NULL,
    display_name VARCHAR(255),
    legacy_created_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_known_recipient_legacy (source_system, legacy_recipient_id),
    UNIQUE KEY uk_known_recipient_address (owner_id, channel_type, recipient_address)
);

CREATE TABLE message_reference_data_migration (
    id CHAR(36) PRIMARY KEY,
    migration_key VARCHAR(128) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    legacy_entity_id VARCHAR(255) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    target_entity_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_reference_migration_source (entity_type, source_system, legacy_entity_id),
    KEY idx_reference_migration_key (migration_key, entity_type, created_at)
);
