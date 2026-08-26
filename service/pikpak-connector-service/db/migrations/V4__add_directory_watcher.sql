CREATE TABLE pikpak_watcher (
    account_id CHAR(36) PRIMARY KEY,
    watch_root VARCHAR(512) NOT NULL,
    backup_root VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    stable_seconds INT NOT NULL DEFAULT 120,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_pikpak_watcher_account FOREIGN KEY (account_id) REFERENCES pikpak_account(id),
    CONSTRAINT ck_pikpak_watcher_roots CHECK (watch_root <> backup_root),
    CONSTRAINT ck_pikpak_watcher_stable CHECK (stable_seconds BETWEEN 1 AND 86400)
);

CREATE TABLE pikpak_watch_batch (
    id CHAR(36) PRIMARY KEY,
    account_id CHAR(36) NOT NULL,
    batch_path VARCHAR(1024) NOT NULL,
    signature CHAR(64) NOT NULL,
    stable_since TIMESTAMP(6) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    remote_job_id BIGINT,
    error_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE (account_id, batch_path),
    CONSTRAINT fk_pikpak_watch_batch_account FOREIGN KEY (account_id) REFERENCES pikpak_account(id),
    CONSTRAINT ck_pikpak_watch_batch_phase CHECK (phase IN ('OBSERVING','READY','MOVING','ARCHIVED','FAILED'))
);

CREATE TABLE pikpak_watch_item (
    batch_id CHAR(36) NOT NULL,
    remote_file_id VARCHAR(255) NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL,
    modified_at VARCHAR(64),
    PRIMARY KEY (batch_id, remote_file_id),
    CONSTRAINT fk_pikpak_watch_item_batch FOREIGN KEY (batch_id) REFERENCES pikpak_watch_batch(id),
    CONSTRAINT ck_pikpak_watch_item_size CHECK (size_bytes >= 0)
);
