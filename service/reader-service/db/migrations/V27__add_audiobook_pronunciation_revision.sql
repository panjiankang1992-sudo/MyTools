ALTER TABLE audiobook_generation
    ADD COLUMN pronunciation_task_instance_id CHAR(36);

ALTER TABLE audiobook_generation
    ADD COLUMN pronunciation_fingerprint_sha256 CHAR(64);

ALTER TABLE audiobook_generation
    ADD COLUMN revision_pronunciation_term VARCHAR(256);

ALTER TABLE audiobook_generation
    ADD COLUMN revision_pronunciation_pinyin VARCHAR(512);

CREATE INDEX idx_audiobook_generation_pronunciation_task
    ON audiobook_generation (pronunciation_task_instance_id);

CREATE INDEX idx_audiobook_generation_pronunciation_revision
    ON audiobook_generation (parent_generation_id, revision_pronunciation_term);

CREATE TABLE audiobook_pronunciation_entry (
    id CHAR(36) PRIMARY KEY,
    generation_id CHAR(36) NOT NULL,
    term VARCHAR(256) NOT NULL,
    pinyin VARCHAR(512) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_pronunciation_entry_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id),
    CONSTRAINT uk_audiobook_pronunciation_entry_term UNIQUE (generation_id, term)
);

CREATE INDEX idx_audiobook_pronunciation_entry_generation
    ON audiobook_pronunciation_entry (generation_id, term);
