CREATE TABLE IF NOT EXISTS pikpak_account (
    id CHAR(36) PRIMARY KEY,
    external_key VARCHAR(128) NOT NULL UNIQUE,
    storage_provider_id CHAR(36) NOT NULL,
    secret_ref VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_pikpak_account_secret_ref CHECK (secret_ref LIKE 'secret://%')
);

CREATE TABLE IF NOT EXISTS pikpak_offline_operation (
    id CHAR(36) PRIMARY KEY,
    account_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    business_type VARCHAR(64) NOT NULL,
    business_id VARCHAR(128) NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    work_token VARCHAR(64) NOT NULL UNIQUE,
    phase VARCHAR(32) NOT NULL,
    stable_signature CHAR(64),
    stable_since TIMESTAMP(6),
    remote_job_id BIGINT,
    error_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_pikpak_operation_account FOREIGN KEY (account_id) REFERENCES pikpak_account(id),
    CONSTRAINT ck_pikpak_operation_phase CHECK (phase IN
        ('CREATED','SUBMITTED','OBSERVING','STABLE','MOVING','READY','CANCELLING','CANCELLED','FAILED'))
);

CREATE TABLE IF NOT EXISTS pikpak_operation_item (
    operation_id CHAR(36) NOT NULL,
    remote_file_id VARCHAR(255) NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL,
    modified_at VARCHAR(64),
    PRIMARY KEY (operation_id, remote_file_id),
    CONSTRAINT fk_pikpak_item_operation FOREIGN KEY (operation_id) REFERENCES pikpak_offline_operation(id),
    CONSTRAINT ck_pikpak_item_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_pikpak_item_path CHECK (relative_path <> '' AND relative_path NOT LIKE '/%')
);

CREATE TABLE IF NOT EXISTS pikpak_outbox_event (
    id CHAR(36) PRIMARY KEY,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6)
);
