CREATE TABLE news_source (
    id VARCHAR(64) PRIMARY KEY,
    spec_json LONGTEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    etag VARCHAR(1024),
    last_modified VARCHAR(256),
    last_checked_ms BIGINT,
    newest_published_ms BIGINT,
    health VARCHAR(32) NOT NULL,
    lease_token VARCHAR(36),
    lease_until_ms BIGINT
);

CREATE TABLE news_article (
    id CHAR(64) PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL,
    canonical_hash CHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    published_ms BIGINT,
    fetched_ms BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    CONSTRAINT fk_news_article_source FOREIGN KEY (source_id) REFERENCES news_source(id),
    CONSTRAINT uk_news_article_version UNIQUE (source_id, canonical_hash, content_hash)
);
CREATE INDEX idx_news_article_window ON news_article(published_ms, fetched_ms);

CREATE TABLE news_edition (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    cutoff_ms BIGINT NOT NULL,
    created_ms BIGINT NOT NULL,
    quality VARCHAR(16) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    CONSTRAINT uk_news_edition_request UNIQUE (owner_id, idempotency_hash)
);
CREATE INDEX idx_news_edition_owner ON news_edition(owner_id, created_ms);
