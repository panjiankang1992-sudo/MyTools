ALTER TABLE storage_operation
    ADD COLUMN target_provider_id CHAR(36);

ALTER TABLE storage_operation
    ADD COLUMN remote_job_id BIGINT;

ALTER TABLE storage_operation
    ADD CONSTRAINT fk_storage_operation_target_provider
    FOREIGN KEY (target_provider_id) REFERENCES storage_provider(id);

CREATE INDEX idx_storage_operation_remote_job ON storage_operation (remote_job_id);
