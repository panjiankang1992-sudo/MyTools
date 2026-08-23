ALTER TABLE storage_provider
    ADD COLUMN endpoint_uri VARCHAR(2048) NULL AFTER remote_key;
