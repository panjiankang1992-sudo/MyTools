CREATE TABLE media_operation (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    task_instance_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_media_operation_key UNIQUE(owner_id,idempotency_key),
    CONSTRAINT uk_media_operation_task UNIQUE(task_instance_id)
);
