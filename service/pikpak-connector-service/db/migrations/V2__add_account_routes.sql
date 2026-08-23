ALTER TABLE pikpak_account ADD COLUMN remote_key VARCHAR(128);
ALTER TABLE pikpak_account ADD COLUMN offline_root VARCHAR(512);
ALTER TABLE pikpak_account ADD COLUMN ready_root VARCHAR(512);

UPDATE pikpak_account
SET remote_key = CONCAT('unconfigured_', REPLACE(id, '-', '')),
    offline_root = 'offline',
    ready_root = 'ready'
WHERE remote_key IS NULL;

ALTER TABLE pikpak_account ADD CONSTRAINT uk_pikpak_account_remote_key UNIQUE (remote_key);
ALTER TABLE pikpak_account ADD CONSTRAINT ck_pikpak_account_routes
CHECK (remote_key IS NOT NULL AND offline_root IS NOT NULL AND ready_root IS NOT NULL);
