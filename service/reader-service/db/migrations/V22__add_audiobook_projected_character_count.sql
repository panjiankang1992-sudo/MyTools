ALTER TABLE audiobook_generation_chapter
    ADD COLUMN source_character_count BIGINT NOT NULL DEFAULT 0;

UPDATE audiobook_generation_chapter
SET source_character_count = source_text_size_bytes
WHERE source_character_count = 0 AND source_text_size_bytes IS NOT NULL;
