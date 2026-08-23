CREATE TABLE book_source (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    sync_key VARCHAR(255) NOT NULL,
    name VARCHAR(300) NOT NULL,
    source_url VARCHAR(2000) NOT NULL,
    enabled BOOLEAN NOT NULL,
    current_version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_book_source_owner_key (owner_id, sync_key)
);

CREATE TABLE book_source_version (
    book_source_id CHAR(36) NOT NULL,
    version INT NOT NULL,
    snapshot_json JSON NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (book_source_id, version),
    CONSTRAINT fk_book_source_version_source FOREIGN KEY (book_source_id) REFERENCES book_source(id)
);

CREATE TABLE book_search_request (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    keyword VARCHAR(200) NOT NULL,
    query_mode VARCHAR(32) NOT NULL,
    page INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE book_search_result (
    id CHAR(36) PRIMARY KEY,
    search_request_id CHAR(36) NOT NULL,
    source_id CHAR(36) NOT NULL,
    canonical_book_key VARCHAR(512) NOT NULL,
    result_json JSON NOT NULL,
    readable BOOLEAN,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_book_search_result_request FOREIGN KEY (search_request_id) REFERENCES book_search_request(id),
    CONSTRAINT fk_book_search_result_source FOREIGN KEY (source_id) REFERENCES book_source(id),
    UNIQUE KEY uk_book_search_result (search_request_id, canonical_book_key)
);

CREATE TABLE book_search_task_binding (
    search_request_id CHAR(36) NOT NULL,
    task_instance_id CHAR(36) NOT NULL,
    shard_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (search_request_id, task_instance_id),
    CONSTRAINT fk_book_search_task_request FOREIGN KEY (search_request_id) REFERENCES book_search_request(id)
);

CREATE TABLE shelf_book (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    book_key VARCHAR(512) NOT NULL,
    metadata_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_shelf_book_owner_key (owner_id, book_key)
);

CREATE TABLE reading_progress (
    shelf_book_id CHAR(36) PRIMARY KEY,
    chapter_index INT NOT NULL,
    chapter_url TEXT,
    position_json JSON NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_reading_progress_book FOREIGN KEY (shelf_book_id) REFERENCES shelf_book(id)
);

CREATE TABLE reader_marker (
    id CHAR(36) PRIMARY KEY,
    shelf_book_id CHAR(36) NOT NULL,
    marker_type VARCHAR(32) NOT NULL,
    chapter_index INT NOT NULL,
    position_json JSON NOT NULL,
    note_text TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_reader_marker_book FOREIGN KEY (shelf_book_id) REFERENCES shelf_book(id)
);
