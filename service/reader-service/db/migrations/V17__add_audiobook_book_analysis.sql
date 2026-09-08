ALTER TABLE audiobook_generation
    ADD COLUMN analysis_task_instance_id CHAR(36);

ALTER TABLE audiobook_generation
    ADD COLUMN analysis_fingerprint_sha256 CHAR(64);

CREATE TABLE audiobook_character_profile (
    id CHAR(36) PRIMARY KEY,
    generation_id CHAR(36) NOT NULL,
    canonical_name VARCHAR(256) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    presentation VARCHAR(32) NOT NULL,
    character_type VARCHAR(64) NOT NULL,
    traits_json JSON NOT NULL,
    first_chapter_index INT NOT NULL,
    occurrence_count INT NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_character_profile_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id),
    CONSTRAINT uk_audiobook_character_profile_name UNIQUE (generation_id, canonical_name)
);

CREATE INDEX idx_audiobook_character_profile_generation
    ON audiobook_character_profile (generation_id, first_chapter_index, canonical_name);

CREATE TABLE audiobook_character_alias (
    id CHAR(36) PRIMARY KEY,
    character_id CHAR(36) NOT NULL,
    alias VARCHAR(256) NOT NULL,
    alias_type VARCHAR(32) NOT NULL,
    evidence_chapter_index INT,
    evidence_start_codepoint INT,
    evidence_end_codepoint INT,
    confidence DECIMAL(5,4) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_character_alias_character
        FOREIGN KEY (character_id) REFERENCES audiobook_character_profile(id),
    CONSTRAINT uk_audiobook_character_alias UNIQUE (character_id, alias)
);

CREATE INDEX idx_audiobook_character_alias_alias ON audiobook_character_alias (alias);

CREATE TABLE audiobook_character_relationship (
    id CHAR(36) PRIMARY KEY,
    generation_id CHAR(36) NOT NULL,
    source_character_id CHAR(36) NOT NULL,
    target_character_id CHAR(36) NOT NULL,
    relationship_type VARCHAR(64) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    evidence_chapter_index INT,
    evidence_start_codepoint INT,
    evidence_end_codepoint INT,
    confidence DECIMAL(5,4) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_character_relationship_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id),
    CONSTRAINT fk_audiobook_character_relationship_source
        FOREIGN KEY (source_character_id) REFERENCES audiobook_character_profile(id),
    CONSTRAINT fk_audiobook_character_relationship_target
        FOREIGN KEY (target_character_id) REFERENCES audiobook_character_profile(id),
    CONSTRAINT uk_audiobook_character_relationship UNIQUE
        (generation_id, source_character_id, target_character_id, relationship_type, direction)
);

CREATE INDEX idx_audiobook_character_relationship_generation
    ON audiobook_character_relationship (generation_id, source_character_id, target_character_id);

CREATE TABLE audiobook_speech_attribution (
    id CHAR(36) PRIMARY KEY,
    generation_id CHAR(36) NOT NULL,
    chapter_index INT NOT NULL,
    sequence_number INT NOT NULL,
    text_start_codepoint INT NOT NULL,
    text_end_codepoint INT NOT NULL,
    speaker_kind VARCHAR(32) NOT NULL,
    speaker_character_id CHAR(36),
    delivery_tags_json JSON NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_speech_attribution_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id),
    CONSTRAINT fk_audiobook_speech_attribution_character
        FOREIGN KEY (speaker_character_id) REFERENCES audiobook_character_profile(id),
    CONSTRAINT uk_audiobook_speech_attribution_sequence UNIQUE (generation_id, chapter_index, sequence_number)
);

CREATE INDEX idx_audiobook_speech_attribution_chapter
    ON audiobook_speech_attribution (generation_id, chapter_index, text_start_codepoint);

CREATE INDEX idx_audiobook_generation_analysis_task
    ON audiobook_generation (analysis_task_instance_id);
