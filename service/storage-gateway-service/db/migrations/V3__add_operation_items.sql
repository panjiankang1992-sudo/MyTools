CREATE TABLE storage_operation_item (
    operation_id CHAR(36) NOT NULL,
    object_path VARCHAR(2048) NOT NULL,
    object_path_sha256 CHAR(64) NOT NULL,
    object_name VARCHAR(512) NOT NULL,
    directory BOOLEAN NOT NULL,
    size_bytes BIGINT NOT NULL,
    modified_at TIMESTAMP(6),
    content_sha256 CHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (operation_id, object_path_sha256),
    CONSTRAINT fk_storage_operation_item FOREIGN KEY (operation_id) REFERENCES storage_operation(id)
);

ALTER TABLE storage_operation
    ADD COLUMN item_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE storage_operation
    ADD COLUMN maximum_objects INT NOT NULL DEFAULT 100000;
