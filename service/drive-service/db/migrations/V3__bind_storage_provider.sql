CREATE TABLE drive_storage_provider_binding (
    account_id CHAR(36) PRIMARY KEY,
    storage_provider_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_drive_storage_binding_account FOREIGN KEY (account_id) REFERENCES drive_account(id),
    CONSTRAINT uk_drive_storage_provider UNIQUE (storage_provider_id)
);
