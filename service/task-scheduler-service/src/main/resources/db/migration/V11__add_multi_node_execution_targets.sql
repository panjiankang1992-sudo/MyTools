CREATE TABLE task_execution_target (
    id CHAR(36) PRIMARY KEY,
    task_instance_id CHAR(36) NOT NULL,
    node_id CHAR(36) NOT NULL,
    target_index INT NOT NULL,
    target_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    dispatch_attempts INT NOT NULL DEFAULT 0,
    max_dispatch_attempts INT NOT NULL DEFAULT 3,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_task_execution_target_node UNIQUE (task_instance_id, node_id),
    CONSTRAINT fk_task_execution_target_instance FOREIGN KEY (task_instance_id) REFERENCES task_instance(id),
    CONSTRAINT fk_task_execution_target_node FOREIGN KEY (node_id) REFERENCES executor_node(id)
);

CREATE INDEX idx_task_execution_target_claim ON task_execution_target(node_id, status, created_at);

ALTER TABLE task_execution
    ADD COLUMN execution_target_id CHAR(36);

ALTER TABLE task_execution
    ADD CONSTRAINT fk_task_execution_target
        FOREIGN KEY (execution_target_id) REFERENCES task_execution_target(id);

CREATE INDEX idx_task_execution_target_history ON task_execution(execution_target_id, created_at);
