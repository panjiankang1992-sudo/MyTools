CREATE TABLE storage_access_ticket (
    id CHAR(36) PRIMARY KEY,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    root_id CHAR(36) NOT NULL,
    relative_path VARCHAR(2048) NOT NULL,
    permission VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6),
    revoked_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_storage_ticket_root FOREIGN KEY (root_id) REFERENCES storage_root(id),
    INDEX idx_storage_ticket_expiry (expires_at, consumed_at, revoked_at)
);
