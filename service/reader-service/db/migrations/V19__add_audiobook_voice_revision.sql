ALTER TABLE audiobook_generation
    ADD COLUMN parent_generation_id CHAR(36);

ALTER TABLE audiobook_generation
    ADD COLUMN revision_role_key VARCHAR(320);

ALTER TABLE audiobook_generation
    ADD COLUMN revision_provider VARCHAR(64);

ALTER TABLE audiobook_generation
    ADD COLUMN revision_voice_type VARCHAR(256);

ALTER TABLE audiobook_generation
    ADD CONSTRAINT fk_audiobook_generation_parent
        FOREIGN KEY (parent_generation_id) REFERENCES audiobook_generation(id);

CREATE INDEX idx_audiobook_generation_parent
    ON audiobook_generation (parent_generation_id, generation_version);
