ALTER TABLE legacy_reader_migration_item
    ADD COLUMN migration_key VARCHAR(255) NOT NULL DEFAULT 'legacy-unscoped' AFTER owner_id;

CREATE INDEX idx_legacy_reader_migration_evidence
    ON legacy_reader_migration_item (migration_key, entity_type, owner_id);
