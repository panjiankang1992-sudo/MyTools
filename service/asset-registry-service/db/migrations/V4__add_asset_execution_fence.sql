CREATE TABLE asset_execution_fence (
    business_key VARCHAR(255) PRIMARY KEY,
    task_instance_id CHAR(36) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    fencing_token BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_asset_execution_fence_task ON asset_execution_fence(task_instance_id, fencing_token);
