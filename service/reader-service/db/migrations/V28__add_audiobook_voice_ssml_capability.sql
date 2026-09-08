ALTER TABLE audiobook_voice_catalog
    ADD COLUMN ssml_supported BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE audiobook_voice_binding
    ADD COLUMN ssml_supported BOOLEAN NOT NULL DEFAULT FALSE;
