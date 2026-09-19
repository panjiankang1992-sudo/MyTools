CREATE TABLE storage_execution_fence (
    business_key VARCHAR(255) PRIMARY KEY,
    task_instance_id VARCHAR(36) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    fencing_token BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
