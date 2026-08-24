ALTER TABLE app_catalog_file ADD COLUMN content_sha256 CHAR(64);
ALTER TABLE app_catalog_file ADD COLUMN storage_uri VARCHAR(4096);
ALTER TABLE app_catalog_file ADD COLUMN migrated_at TIMESTAMP(6);
CREATE INDEX idx_app_catalog_file_unresolved ON app_catalog_file(asset_id,id);
