CREATE TABLE storage_operation_child (
    parent_operation_id CHAR(36) NOT NULL,
    child_operation_id CHAR(36) NOT NULL,
    source_object_path VARCHAR(2048) NOT NULL,
    target_object_path VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (parent_operation_id, source_object_path),
    CONSTRAINT uq_storage_operation_child UNIQUE (child_operation_id),
    CONSTRAINT fk_storage_operation_child_parent FOREIGN KEY (parent_operation_id) REFERENCES storage_operation(id),
    CONSTRAINT fk_storage_operation_child_child FOREIGN KEY (child_operation_id) REFERENCES storage_operation(id)
);
