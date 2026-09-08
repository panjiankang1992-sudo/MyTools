ALTER TABLE audiobook_voice_catalog
    ADD COLUMN preview_storage_uri VARCHAR(2048);

ALTER TABLE audiobook_voice_catalog
    ADD COLUMN preview_content_sha256 CHAR(64);

ALTER TABLE audiobook_voice_catalog
    ADD COLUMN preview_size_bytes BIGINT;

ALTER TABLE audiobook_voice_catalog
    ADD COLUMN preview_format VARCHAR(16);

ALTER TABLE audiobook_voice_catalog
    ADD COLUMN preview_duration_ms BIGINT;
