ALTER TABLE audiobook_generation
    ADD COLUMN revision_chapter_index INT;

ALTER TABLE audiobook_generation
    ADD COLUMN revision_sequence_number INT;

ALTER TABLE audiobook_generation
    ADD COLUMN revision_speaker_kind VARCHAR(32);

ALTER TABLE audiobook_generation
    ADD COLUMN revision_speaker_canonical_name VARCHAR(256);

CREATE INDEX idx_audiobook_generation_speaker_revision
    ON audiobook_generation (parent_generation_id, revision_chapter_index, revision_sequence_number);
