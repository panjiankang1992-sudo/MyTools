CREATE TABLE news_region_observation (
    id CHAR(36) PRIMARY KEY,
    node_id VARCHAR(128) NOT NULL,
    observed_ms BIGINT NOT NULL,
    received_ms BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    fingerprint CHAR(64),
    payload_hash CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL
);
CREATE INDEX idx_news_region_observation ON news_region_observation(node_id, observed_ms, received_ms);

CREATE TABLE news_region_source_pack (
    region_code VARCHAR(64) NOT NULL,
    version_number INT NOT NULL,
    registered_ms BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    PRIMARY KEY (region_code, version_number)
);

CREATE TABLE news_region_snapshot (
    owner_id BIGINT NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    cutoff_ms BIGINT NOT NULL,
    created_ms BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    PRIMARY KEY (owner_id, idempotency_hash)
);
