ALTER TABLE storage_provider
    ADD COLUMN region_name VARCHAR(64) NULL AFTER endpoint_uri;
