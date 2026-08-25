CREATE TABLE qq_connector_checkpoint (
    account_key VARCHAR(128) PRIMARY KEY,
    session_id VARCHAR(255) NULL,
    sequence_number BIGINT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE qq_command_outbox (
    id CHAR(36) PRIMARY KEY,
    account_key VARCHAR(128) NOT NULL,
    external_message_id VARCHAR(255) NOT NULL,
    sender_id VARCHAR(255) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    task_instance_id CHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_qq_command_message (account_key, external_message_id, command_type),
    INDEX idx_qq_command_status (status, updated_at)
);
