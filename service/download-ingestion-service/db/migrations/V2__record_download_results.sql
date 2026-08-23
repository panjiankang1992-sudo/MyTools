ALTER TABLE download_item
    ADD COLUMN external_item_id VARCHAR(255),
    ADD COLUMN size_bytes BIGINT,
    ADD COLUMN storage_uri VARCHAR(1024),
    ADD COLUMN asset_id CHAR(36),
    ADD UNIQUE KEY uk_download_item_external (download_request_id, external_item_id);
