CREATE TABLE drive_account_migration (
    migration_key VARCHAR(128) NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    legacy_account_id BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    target_account_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (source_system, legacy_account_id),
    CONSTRAINT fk_drive_account_migration_target
        FOREIGN KEY (target_account_id) REFERENCES drive_account(id)
);
CREATE INDEX idx_drive_account_migration_key
    ON drive_account_migration(migration_key, source_system, legacy_account_id);
