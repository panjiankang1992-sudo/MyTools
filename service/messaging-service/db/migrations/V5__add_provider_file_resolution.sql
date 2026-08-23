ALTER TABLE inbound_message_part ADD COLUMN provider_account_key VARCHAR(255);

ALTER TABLE attachment_download_job ADD COLUMN resolved_source_url VARCHAR(4096);
ALTER TABLE attachment_download_job ADD COLUMN resolved_at TIMESTAMP(6);
