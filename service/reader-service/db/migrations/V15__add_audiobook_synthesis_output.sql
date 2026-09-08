ALTER TABLE audiobook_generation
    ADD COLUMN synthesis_task_instance_id CHAR(36);

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN audio_storage_uri VARCHAR(2048);

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN audio_content_sha256 CHAR(64);

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN audio_size_bytes BIGINT;

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN audio_format VARCHAR(16);

CREATE TABLE audiobook_audio_asset (
    id CHAR(36) PRIMARY KEY,
    generation_id CHAR(36) NOT NULL,
    chapter_index INT NOT NULL,
    storage_uri VARCHAR(2048) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    format VARCHAR(16) NOT NULL,
    size_bytes BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_audio_asset_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id),
    CONSTRAINT uk_audiobook_audio_asset_chapter UNIQUE (generation_id, chapter_index)
);

CREATE INDEX idx_audiobook_generation_chapter_synthesis
    ON audiobook_generation_chapter (generation_id, synthesis_status, chapter_index);
