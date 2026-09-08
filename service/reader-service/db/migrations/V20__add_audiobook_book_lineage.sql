ALTER TABLE audiobook_generation
    ADD COLUMN book_lineage_key VARCHAR(320) NOT NULL DEFAULT '';

UPDATE audiobook_generation
SET book_lineage_key = CONCAT('ebook-asset:', ebook_asset_id)
WHERE book_lineage_key = '';

CREATE INDEX idx_audiobook_generation_lineage
    ON audiobook_generation (owner_id, book_lineage_key, generation_version, status);
