CREATE TABLE drive_account (
    id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, external_account_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL, provider_type VARCHAR(64) NOT NULL,
    provider_secret_ref VARCHAR(512) NOT NULL, remote_key VARCHAR(128) NOT NULL,
    read_only BOOLEAN NOT NULL, enabled BOOLEAN NOT NULL, index_generation BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_drive_account_external UNIQUE (owner_id, external_account_id),
    CONSTRAINT uk_drive_account_remote UNIQUE (remote_key)
);
CREATE TABLE drive_permission (
    id CHAR(36) PRIMARY KEY, account_id CHAR(36) NOT NULL, principal_type VARCHAR(32) NOT NULL,
    principal_id VARCHAR(255) NOT NULL, permission VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_drive_permission UNIQUE (account_id, principal_type, principal_id, permission),
    CONSTRAINT fk_drive_permission_account FOREIGN KEY (account_id) REFERENCES drive_account(id)
);
CREATE TABLE drive_item_index (
    id CHAR(36) PRIMARY KEY, account_id CHAR(36) NOT NULL, remote_id VARCHAR(255),
    remote_path VARCHAR(2048) NOT NULL, path_sha256 CHAR(64) NOT NULL, parent_path VARCHAR(2048) NOT NULL, display_name VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255), size_bytes BIGINT NOT NULL, directory BOOLEAN NOT NULL,
    modified_at TIMESTAMP(6), content_sha256 CHAR(64), generation BIGINT NOT NULL, deleted BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_drive_item_path UNIQUE (account_id, path_sha256),
    CONSTRAINT fk_drive_item_account FOREIGN KEY (account_id) REFERENCES drive_account(id)
);
CREATE INDEX idx_drive_item_parent ON drive_item_index(account_id, deleted);
CREATE TABLE drive_index_cursor (
    account_id CHAR(36) PRIMARY KEY, run_id CHAR(36) NOT NULL, generation BIGINT NOT NULL,
    last_batch_key VARCHAR(255), next_cursor VARCHAR(2048), status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_drive_cursor_account FOREIGN KEY (account_id) REFERENCES drive_account(id)
);
CREATE TABLE drive_operation (
    id CHAR(36) PRIMARY KEY, account_id CHAR(36) NOT NULL, operation_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE, status VARCHAR(32) NOT NULL, parameters_json TEXT NOT NULL,
    error_code VARCHAR(128), created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_drive_operation_account FOREIGN KEY (account_id) REFERENCES drive_account(id)
);
CREATE TABLE drive_task_binding (
    operation_id CHAR(36) NOT NULL, task_instance_id CHAR(36) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (operation_id, task_instance_id),
    CONSTRAINT fk_drive_task_operation FOREIGN KEY (operation_id) REFERENCES drive_operation(id)
);
CREATE TABLE drive_access_ticket (
    id CHAR(36) PRIMARY KEY, account_id CHAR(36) NOT NULL, item_id CHAR(36) NOT NULL,
    token_sha256 CHAR(64) NOT NULL UNIQUE, permission VARCHAR(32) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6), revoked_at TIMESTAMP(6), created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_drive_ticket_account FOREIGN KEY (account_id) REFERENCES drive_account(id),
    CONSTRAINT fk_drive_ticket_item FOREIGN KEY (item_id) REFERENCES drive_item_index(id)
);
CREATE TABLE drive_outbox (
    id CHAR(36) PRIMARY KEY, aggregate_type VARCHAR(64) NOT NULL, aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL, payload_json TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6)
);
