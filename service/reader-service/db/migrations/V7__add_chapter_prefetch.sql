CREATE TABLE chapter_prefetch_request (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    source_id CHAR(36) NOT NULL,
    source_version INT NOT NULL,
    book_url VARCHAR(4096) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    parameters_json JSON NOT NULL,
    requested_count INT NOT NULL,
    cached_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_chapter_prefetch_source FOREIGN KEY (source_id) REFERENCES book_source(id),
    UNIQUE KEY uk_chapter_prefetch_idempotency (owner_id, idempotency_key)
);

CREATE TABLE chapter_cache (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    source_id CHAR(36) NOT NULL,
    source_version INT NOT NULL,
    book_key CHAR(64) NOT NULL,
    book_url VARCHAR(4096) NOT NULL,
    chapter_key CHAR(64) NOT NULL,
    chapter_url VARCHAR(4096) NOT NULL,
    chapter_index INT NOT NULL,
    chapter_title VARCHAR(500) NOT NULL,
    content_text MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_chapter_cache_source FOREIGN KEY (source_id) REFERENCES book_source(id),
    UNIQUE KEY uk_chapter_cache_identity (owner_id, source_id, book_key, chapter_key)
);

CREATE INDEX idx_chapter_cache_expiry ON chapter_cache (expires_at);

CREATE TABLE chapter_prefetch_cache_link (
    prefetch_request_id CHAR(36) NOT NULL,
    chapter_cache_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (prefetch_request_id, chapter_cache_id),
    CONSTRAINT fk_prefetch_link_request FOREIGN KEY (prefetch_request_id) REFERENCES chapter_prefetch_request(id),
    CONSTRAINT fk_prefetch_link_cache FOREIGN KEY (chapter_cache_id) REFERENCES chapter_cache(id)
);
