CREATE TABLE IF NOT EXISTS drive_account (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    remote_key VARCHAR(64) NOT NULL,
    read_only TINYINT NOT NULL DEFAULT 1,
    enabled TINYINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_checked_at DATETIME(6),
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uq_drive_account_remote (user_id, remote_key),
    KEY idx_drive_account_user (user_id, enabled)
);

CREATE TABLE IF NOT EXISTS drive_item_index (
    id BIGINT NOT NULL PRIMARY KEY,
    drive_id BIGINT NOT NULL,
    remote_path VARCHAR(2048) NOT NULL,
    remote_path_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(remote_path, 256))) STORED,
    parent_path VARCHAR(2048) NOT NULL,
    display_name VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255),
    extension VARCHAR(32),
    is_directory TINYINT NOT NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    modified_at DATETIME(6),
    etag VARCHAR(255),
    indexed_at DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_drive_item_path (drive_id, remote_path_hash),
    KEY idx_drive_item_parent (drive_id, parent_path(512), deleted),
    KEY idx_drive_item_name (drive_id, display_name(191), deleted)
);
