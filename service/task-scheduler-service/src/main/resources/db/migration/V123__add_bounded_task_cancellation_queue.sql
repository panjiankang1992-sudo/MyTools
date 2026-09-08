CREATE TABLE task_cancellation_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_task_instance_id CHAR(36) NOT NULL,
    task_instance_id CHAR(36) NOT NULL,
    depth INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP(6),
    CONSTRAINT uk_task_cancellation_queue_task UNIQUE (task_instance_id),
    CONSTRAINT fk_task_cancellation_queue_root FOREIGN KEY (root_task_instance_id) REFERENCES task_instance(id),
    CONSTRAINT fk_task_cancellation_queue_task FOREIGN KEY (task_instance_id) REFERENCES task_instance(id)
);

CREATE INDEX idx_task_cancellation_queue_claim
    ON task_cancellation_queue(status, id);
