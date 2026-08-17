CREATE TABLE IF NOT EXISTS t_shelf_book (
    user_id BIGINT NOT NULL,
    sync_key VARCHAR(80) NOT NULL,
    book_id VARCHAR(1000) NOT NULL,
    name VARCHAR(300) NOT NULL,
    author VARCHAR(200) NOT NULL,
    origin VARCHAR(20) NOT NULL,
    format VARCHAR(20) NOT NULL,
    resource_uri VARCHAR(4096) NOT NULL,
    source_id VARCHAR(4096) NOT NULL,
    remote_cover_url VARCHAR(4096) NOT NULL DEFAULT '',
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, sync_key),
    INDEX idx_shelf_book_user_updated (user_id, server_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
