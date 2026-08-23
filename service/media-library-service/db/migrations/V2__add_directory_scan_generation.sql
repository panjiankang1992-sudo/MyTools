ALTER TABLE media_item ADD COLUMN last_seen_scan_id CHAR(36);

CREATE TABLE media_scan (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    directory_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    root_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_count INT NOT NULL,
    imported_count INT NOT NULL,
    manifest_sha256 CHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT uk_media_scan_idempotency UNIQUE(owner_id,idempotency_key),
    CONSTRAINT fk_media_scan_directory FOREIGN KEY(directory_id) REFERENCES media_directory(id)
);

CREATE TABLE media_scan_entry (
    scan_id CHAR(36) NOT NULL,
    source_business_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    media_item_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    imported_at TIMESTAMP(6),
    PRIMARY KEY(scan_id,source_business_id),
    CONSTRAINT fk_media_scan_entry_scan FOREIGN KEY(scan_id) REFERENCES media_scan(id),
    CONSTRAINT fk_media_scan_entry_item FOREIGN KEY(media_item_id) REFERENCES media_item(id)
);

CREATE TABLE media_directory_entry (
    directory_id CHAR(36) NOT NULL,
    source_business_id VARCHAR(255) NOT NULL,
    media_item_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_seen_scan_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(directory_id,source_business_id),
    CONSTRAINT fk_media_directory_entry_directory FOREIGN KEY(directory_id) REFERENCES media_directory(id),
    CONSTRAINT fk_media_directory_entry_item FOREIGN KEY(media_item_id) REFERENCES media_item(id),
    CONSTRAINT fk_media_directory_entry_scan FOREIGN KEY(last_seen_scan_id) REFERENCES media_scan(id)
);

CREATE INDEX idx_media_item_scan ON media_item(directory_id,source_type,last_seen_scan_id);
CREATE INDEX idx_media_scan_entry_status ON media_scan_entry(scan_id,status);
CREATE INDEX idx_media_directory_entry_item ON media_directory_entry(media_item_id,status);
