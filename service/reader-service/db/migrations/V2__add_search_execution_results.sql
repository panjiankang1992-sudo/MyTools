ALTER TABLE book_search_request ADD COLUMN parameters_json JSON;

CREATE TABLE book_search_shard_result (
    id CHAR(36) PRIMARY KEY,
    search_request_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NOT NULL,
    target_index INT,
    target_count INT,
    status VARCHAR(32) NOT NULL,
    result_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_book_search_shard_request FOREIGN KEY (search_request_id) REFERENCES book_search_request(id),
    UNIQUE KEY uk_book_search_shard_execution (search_request_id, execution_id)
);

CREATE TABLE book_search_aggregate_result (
    id CHAR(36) PRIMARY KEY,
    search_request_id CHAR(36) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    canonical_book_key VARCHAR(512) NOT NULL,
    result_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_book_search_aggregate_request FOREIGN KEY (search_request_id) REFERENCES book_search_request(id),
    UNIQUE KEY uk_book_search_aggregate (search_request_id, canonical_book_key)
);
