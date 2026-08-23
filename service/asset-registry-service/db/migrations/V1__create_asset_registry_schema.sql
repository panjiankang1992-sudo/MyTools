CREATE TABLE asset (
    id CHAR(36) PRIMARY KEY,
    content_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_asset_content (content_sha256, size_bytes)
);

CREATE TABLE asset_source (
    id CHAR(36) PRIMARY KEY,
    asset_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_business_id VARCHAR(255) NOT NULL,
    event_key VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_asset_source_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    UNIQUE KEY uk_asset_source_identity (owner_id, source_type, source_business_id)
);

CREATE TABLE asset_location (
    id CHAR(36) PRIMARY KEY,
    asset_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    provider_type VARCHAR(64) NOT NULL,
    storage_uri VARCHAR(4096) NOT NULL,
    provider_version VARCHAR(255),
    availability VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_asset_location_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    UNIQUE KEY uk_asset_location_uri (asset_id, provider_type, storage_uri)
);

CREATE TABLE asset_artifact (
    id CHAR(36) PRIMARY KEY,
    parent_asset_id CHAR(36) NOT NULL,
    artifact_asset_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    artifact_kind VARCHAR(64) NOT NULL,
    generator_name VARCHAR(128) NOT NULL,
    generator_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_asset_artifact_parent FOREIGN KEY (parent_asset_id) REFERENCES asset(id),
    CONSTRAINT fk_asset_artifact_child FOREIGN KEY (artifact_asset_id) REFERENCES asset(id),
    UNIQUE KEY uk_asset_artifact_version (parent_asset_id, artifact_kind, generator_name, generator_version)
);

CREATE TABLE asset_outbox (
    id CHAR(36) PRIMARY KEY,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6)
);

CREATE INDEX idx_asset_outbox_unpublished ON asset_outbox (published_at, created_at);
