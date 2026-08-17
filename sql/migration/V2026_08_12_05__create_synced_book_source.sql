CREATE TABLE IF NOT EXISTS t_synced_book_source (
    user_id BIGINT NOT NULL,
    sync_key VARCHAR(80) NOT NULL,
    source_url VARCHAR(4096) NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, sync_key),
    INDEX idx_synced_book_source_user_updated (user_id, server_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
