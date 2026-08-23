ALTER TABLE attachment_download_job ADD COLUMN resolution_mode VARCHAR(32);

ALTER TABLE attachment_download_job ADD CONSTRAINT ck_attachment_resolution_mode
CHECK (resolution_mode IS NULL OR resolution_mode IN ('PUBLIC_URL', 'STREAM'));
