CREATE TABLE ebook_catalog_entry (
    import_request_id CHAR(36) NOT NULL,
    entry_index INT NOT NULL,
    title VARCHAR(500) NOT NULL,
    resource_ref VARCHAR(4096) NOT NULL,
    start_offset BIGINT,
    end_offset BIGINT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (import_request_id, entry_index),
    CONSTRAINT fk_ebook_catalog_import FOREIGN KEY (import_request_id) REFERENCES ebook_import_request(id)
);
