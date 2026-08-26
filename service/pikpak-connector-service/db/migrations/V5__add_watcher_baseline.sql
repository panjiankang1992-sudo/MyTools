ALTER TABLE pikpak_watcher
    ADD COLUMN process_existing BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE pikpak_watcher
    ADD COLUMN baseline_completed BOOLEAN NOT NULL DEFAULT FALSE;
