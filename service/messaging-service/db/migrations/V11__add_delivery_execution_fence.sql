CREATE TABLE delivery_execution_fence (
    delivery_request_id CHAR(36) PRIMARY KEY,
    task_instance_id CHAR(36) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    business_key VARCHAR(255) NOT NULL,
    fencing_token BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_delivery_execution_fence_request
        FOREIGN KEY (delivery_request_id) REFERENCES delivery_request(id)
);

CREATE INDEX idx_delivery_execution_fence_task
    ON delivery_execution_fence(task_instance_id, fencing_token);
