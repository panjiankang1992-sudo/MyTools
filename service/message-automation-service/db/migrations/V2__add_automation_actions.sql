CREATE TABLE automation_action (
    id CHAR(36) PRIMARY KEY,
    automation_run_id CHAR(36) NOT NULL,
    sequence_number INT NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    source_url VARCHAR(4096) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    external_request_id CHAR(36),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_automation_action_run FOREIGN KEY (automation_run_id) REFERENCES automation_run(id),
    UNIQUE KEY uk_automation_action_sequence (automation_run_id, sequence_number),
    UNIQUE KEY uk_automation_action_external (external_request_id)
);

CREATE INDEX idx_automation_action_status ON automation_action (automation_run_id, status);
