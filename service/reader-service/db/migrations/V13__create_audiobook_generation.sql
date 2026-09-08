CREATE TABLE audiobook_generation (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    ebook_asset_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(64) NOT NULL,
    book_content_sha256 CHAR(64) NOT NULL,
    generation_version INT NOT NULL,
    task_instance_id CHAR(36),
    requested_chapter_count INT NOT NULL,
    completed_chapter_count INT NOT NULL,
    failed_chapter_count INT NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audiobook_generation_asset FOREIGN KEY (ebook_asset_id) REFERENCES ebook_asset(id),
    CONSTRAINT uk_audiobook_generation_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_audiobook_generation_asset ON audiobook_generation (owner_id, ebook_asset_id, created_at);

CREATE TABLE audiobook_generation_chapter (
    generation_id CHAR(36) NOT NULL,
    chapter_index INT NOT NULL,
    chapter_identity_sha256 CHAR(64) NOT NULL,
    chapter_content_sha256 CHAR(64),
    chapter_title VARCHAR(500) NOT NULL,
    source_status VARCHAR(32) NOT NULL,
    analysis_status VARCHAR(32) NOT NULL,
    synthesis_status VARCHAR(32) NOT NULL,
    audio_asset_id CHAR(36),
    timing_asset_id CHAR(36),
    duration_ms BIGINT,
    retry_count INT NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (generation_id, chapter_index),
    CONSTRAINT fk_audiobook_generation_chapter_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id)
);

CREATE INDEX idx_audiobook_generation_chapter_identity
    ON audiobook_generation_chapter (chapter_identity_sha256, chapter_content_sha256);
