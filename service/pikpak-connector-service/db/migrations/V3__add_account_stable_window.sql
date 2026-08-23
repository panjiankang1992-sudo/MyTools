ALTER TABLE pikpak_account ADD COLUMN stable_seconds INT NOT NULL DEFAULT 120;
ALTER TABLE pikpak_account ADD CONSTRAINT ck_pikpak_account_stable_seconds
CHECK (stable_seconds BETWEEN 1 AND 86400);
