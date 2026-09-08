ALTER TABLE audiobook_generation_chapter
    ADD COLUMN change_type VARCHAR(32) NOT NULL DEFAULT 'FULL';

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN reused_from_generation_id CHAR(36);

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN reused_from_chapter_index INT;

ALTER TABLE audiobook_audio_asset
    ADD COLUMN asset_registry_id CHAR(36);

UPDATE audiobook_audio_asset
SET asset_registry_id = id
WHERE asset_registry_id IS NULL;

ALTER TABLE audiobook_audio_asset
    MODIFY COLUMN asset_registry_id CHAR(36) NOT NULL;

CREATE INDEX idx_audiobook_generation_chapter_reuse
    ON audiobook_generation_chapter (generation_id, change_type, synthesis_status);

CREATE INDEX idx_audiobook_audio_asset_registry
    ON audiobook_audio_asset (asset_registry_id);
