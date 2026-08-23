CREATE TABLE storage_move_state (
    operation_id CHAR(36) PRIMARY KEY,
    phase VARCHAR(32) NOT NULL,
    remote_job_id BIGINT,
    desired_terminal_status VARCHAR(32),
    failure_code VARCHAR(128),
    recovery_action VARCHAR(32),
    recovery_required BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_move_state_operation FOREIGN KEY (operation_id) REFERENCES storage_operation(id)
);

CREATE INDEX idx_move_state_phase ON storage_move_state(phase, updated_at);

CREATE TABLE storage_move_target_reservation (
    operation_id CHAR(36) PRIMARY KEY,
    target_provider_id CHAR(36) NOT NULL,
    target_path VARCHAR(2048) NOT NULL,
    target_path_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_move_target UNIQUE (target_provider_id, target_path_sha256),
    CONSTRAINT fk_move_reservation_operation FOREIGN KEY (operation_id) REFERENCES storage_operation(id),
    CONSTRAINT fk_move_reservation_provider FOREIGN KEY (target_provider_id) REFERENCES storage_provider(id)
);
