CREATE TABLE IF NOT EXISTS t_reader_marker (
    user_id BIGINT NOT NULL,
    marker_id VARCHAR(100) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    book_id VARCHAR(200) NOT NULL,
    chapter_title VARCHAR(500) NOT NULL,
    locator BIGINT NOT NULL,
    note VARCHAR(2000) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, marker_id),
    INDEX idx_reader_marker_user_updated (user_id, server_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
