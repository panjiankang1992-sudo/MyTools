CREATE TABLE IF NOT EXISTS t_book_source_search_cache (
    user_id BIGINT NOT NULL,
    normalized_keyword VARCHAR(255) NOT NULL,
    query_mode VARCHAR(16) NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    page INT NOT NULL,
    source_revision BIGINT NOT NULL,
    cache_status VARCHAR(24) NOT NULL DEFAULT 'EMPTY',
    results_json MEDIUMTEXT NOT NULL,
    result_count INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, normalized_keyword, query_mode, source_id, page),
    INDEX idx_book_source_search_cache_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
