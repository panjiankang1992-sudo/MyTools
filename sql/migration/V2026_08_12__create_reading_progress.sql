CREATE TABLE IF NOT EXISTS t_reading_progress (
    user_id BIGINT NOT NULL,
    book_id VARCHAR(200) NOT NULL,
    chapter_title VARCHAR(500) NOT NULL,
    locator BIGINT NOT NULL,
    percentage INT NOT NULL,
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, book_id),
    INDEX idx_reading_progress_user_updated (user_id, server_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
