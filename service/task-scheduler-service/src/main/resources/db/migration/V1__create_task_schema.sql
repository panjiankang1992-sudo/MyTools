CREATE TABLE task_definition (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1024),
    task_type VARCHAR(32) NOT NULL,
    timeout_seconds BIGINT NOT NULL,
    cluster_id CHAR(36),
    cron_expression VARCHAR(128),
    cron_timezone VARCHAR(64),
    execution_mode VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    max_concurrency INT NOT NULL,
    overlap_policy VARCHAR(32) NOT NULL,
    misfire_policy VARCHAR(32) NOT NULL,
    parameter_schema TEXT,
    result_schema TEXT,
    version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_task_definition_name_version UNIQUE (name, version)
);

CREATE TABLE task_step_definition (
    id CHAR(36) PRIMARY KEY,
    task_definition_id CHAR(36) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1024),
    step_kind VARCHAR(32) NOT NULL,
    script_package VARCHAR(128) NOT NULL,
    script_version VARCHAR(64) NOT NULL,
    entrypoint VARCHAR(512) NOT NULL,
    arguments_template TEXT,
    enabled BOOLEAN NOT NULL,
    timeout_seconds BIGINT NOT NULL,
    failure_policy VARCHAR(32) NOT NULL,
    sequence_number INT NOT NULL,
    max_attempts INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_task_step_name UNIQUE (task_definition_id, name),
    CONSTRAINT fk_task_step_definition FOREIGN KEY (task_definition_id) REFERENCES task_definition(id)
);

CREATE TABLE execution_cluster (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(1024),
    dispatch_strategy VARCHAR(32) NOT NULL,
    max_concurrent_tasks INT NOT NULL,
    labels_json TEXT,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE executor_node (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    instance_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    capabilities_json TEXT,
    labels_json TEXT,
    max_concurrent_tasks INT NOT NULL,
    running_tasks INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    last_heartbeat_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE cluster_node (
    cluster_id CHAR(36) NOT NULL,
    node_id CHAR(36) NOT NULL,
    weight INT NOT NULL,
    priority INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    joined_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (cluster_id, node_id),
    CONSTRAINT fk_cluster_node_cluster FOREIGN KEY (cluster_id) REFERENCES execution_cluster(id),
    CONSTRAINT fk_cluster_node_node FOREIGN KEY (node_id) REFERENCES executor_node(id)
);

CREATE TABLE task_instance (
    id CHAR(36) PRIMARY KEY,
    task_definition_id CHAR(36),
    task_definition_version INT,
    task_name VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    parent_task_instance_id CHAR(36),
    business_type VARCHAR(64),
    business_id VARCHAR(128),
    priority INT NOT NULL,
    parameters_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL,
    cancel_requested_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_instance_definition FOREIGN KEY (task_definition_id) REFERENCES task_definition(id),
    CONSTRAINT fk_task_instance_parent FOREIGN KEY (parent_task_instance_id) REFERENCES task_instance(id)
);

CREATE INDEX idx_task_instance_status_priority ON task_instance(status, priority, created_at);
CREATE INDEX idx_task_instance_parent ON task_instance(parent_task_instance_id);
CREATE INDEX idx_task_instance_business ON task_instance(business_type, business_id);

CREATE TABLE task_execution (
    id CHAR(36) PRIMARY KEY,
    task_instance_id CHAR(36) NOT NULL,
    node_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_token CHAR(36),
    lease_until TIMESTAMP(6),
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_execution_instance FOREIGN KEY (task_instance_id) REFERENCES task_instance(id),
    CONSTRAINT fk_task_execution_node FOREIGN KEY (node_id) REFERENCES executor_node(id)
);

CREATE TABLE step_execution (
    id CHAR(36) PRIMARY KEY,
    task_execution_id CHAR(36) NOT NULL,
    step_definition_id CHAR(36) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    exit_code INT,
    result_json TEXT,
    error_code VARCHAR(128),
    error_message VARCHAR(2048),
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_step_execution_attempt UNIQUE (task_execution_id, step_definition_id, attempt),
    CONSTRAINT fk_step_execution_task FOREIGN KEY (task_execution_id) REFERENCES task_execution(id),
    CONSTRAINT fk_step_execution_definition FOREIGN KEY (step_definition_id) REFERENCES task_step_definition(id)
);

CREATE TABLE task_checkpoint (
    task_instance_id CHAR(36) NOT NULL,
    checkpoint_key VARCHAR(128) NOT NULL,
    checkpoint_json TEXT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (task_instance_id, checkpoint_key),
    CONSTRAINT fk_task_checkpoint_instance FOREIGN KEY (task_instance_id) REFERENCES task_instance(id)
);

CREATE TABLE task_outbox (
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6)
);

CREATE INDEX idx_task_outbox_status ON task_outbox(status, created_at);
