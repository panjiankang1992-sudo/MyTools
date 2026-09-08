ALTER TABLE audiobook_generation
    ADD COLUMN voice_match_task_instance_id CHAR(36);

ALTER TABLE audiobook_generation
    ADD COLUMN voice_plan_fingerprint_sha256 CHAR(64);

CREATE TABLE audiobook_voice_catalog (
    id CHAR(36) PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    voice_type VARCHAR(256) NOT NULL,
    language VARCHAR(32) NOT NULL,
    presentation VARCHAR(32) NOT NULL,
    age_group VARCHAR(32) NOT NULL,
    style_tags_json JSON NOT NULL,
    narrator_eligible BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    catalog_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_audiobook_voice_catalog_provider_voice UNIQUE (provider, voice_type)
);

CREATE INDEX idx_audiobook_voice_catalog_match
    ON audiobook_voice_catalog (enabled, language, presentation, narrator_eligible);

CREATE TABLE audiobook_voice_binding (
    id CHAR(36) PRIMARY KEY,
    generation_id CHAR(36) NOT NULL,
    role_key VARCHAR(320) NOT NULL,
    character_id CHAR(36),
    voice_catalog_id CHAR(36) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    voice_type VARCHAR(256) NOT NULL,
    match_score DECIMAL(5,4) NOT NULL,
    match_method VARCHAR(64) NOT NULL,
    rationale_tags_json JSON NOT NULL,
    locked BOOLEAN NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_voice_binding_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id),
    CONSTRAINT fk_audiobook_voice_binding_character
        FOREIGN KEY (character_id) REFERENCES audiobook_character_profile(id),
    CONSTRAINT fk_audiobook_voice_binding_catalog
        FOREIGN KEY (voice_catalog_id) REFERENCES audiobook_voice_catalog(id),
    CONSTRAINT uk_audiobook_voice_binding_role UNIQUE (generation_id, role_key)
);

CREATE INDEX idx_audiobook_voice_binding_generation
    ON audiobook_voice_binding (generation_id, character_id);

CREATE INDEX idx_audiobook_generation_voice_match_task
    ON audiobook_generation (voice_match_task_instance_id);
