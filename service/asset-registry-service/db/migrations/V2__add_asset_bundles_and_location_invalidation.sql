ALTER TABLE asset_location ADD COLUMN invalidated_at TIMESTAMP(6);
ALTER TABLE asset_location ADD COLUMN invalidation_reason VARCHAR(255);

CREATE TABLE asset_location_invalidation (
    id CHAR(36) PRIMARY KEY,
    asset_id CHAR(36) NOT NULL,
    location_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_location_invalidation_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    CONSTRAINT fk_location_invalidation_location FOREIGN KEY (location_id) REFERENCES asset_location(id),
    UNIQUE KEY uk_location_invalidation_location (location_id)
);

CREATE TABLE asset_bundle (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1024),
    manifest_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE asset_bundle_item (
    id CHAR(36) PRIMARY KEY,
    bundle_id CHAR(36) NOT NULL,
    asset_id CHAR(36) NOT NULL,
    asset_version BIGINT NOT NULL,
    logical_path VARCHAR(1024) NOT NULL,
    item_role VARCHAR(64) NOT NULL,
    sequence_number INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_asset_bundle_item_bundle FOREIGN KEY (bundle_id) REFERENCES asset_bundle(id),
    CONSTRAINT fk_asset_bundle_item_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    UNIQUE KEY uk_asset_bundle_path (bundle_id, logical_path),
    UNIQUE KEY uk_asset_bundle_sequence (bundle_id, sequence_number)
);

CREATE INDEX idx_asset_bundle_owner ON asset_bundle (owner_id, published_at, id);
CREATE UNIQUE INDEX uk_asset_bundle_manifest ON asset_bundle (owner_id, manifest_sha256);

CREATE TABLE asset_bundle_idempotency (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    bundle_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_asset_bundle_idempotency_bundle FOREIGN KEY (bundle_id) REFERENCES asset_bundle(id)
);

CREATE TABLE asset_registry_revision (
    singleton_id INT PRIMARY KEY,
    revision BIGINT NOT NULL
);

INSERT INTO asset_registry_revision (singleton_id, revision)
SELECT 1, COUNT(*) FROM asset_outbox;
