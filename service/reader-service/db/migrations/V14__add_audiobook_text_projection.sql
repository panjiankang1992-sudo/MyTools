ALTER TABLE audiobook_generation_chapter
    ADD COLUMN source_resource_ref VARCHAR(4096);

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN source_start_offset BIGINT;

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN source_end_offset BIGINT;

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN source_text_uri VARCHAR(2048);

ALTER TABLE audiobook_generation_chapter
    ADD COLUMN source_text_size_bytes BIGINT;

CREATE INDEX idx_audiobook_generation_chapter_source_status
    ON audiobook_generation_chapter (generation_id, source_status, chapter_index);
