CREATE TABLE identity_refresh_receipt (
 session_id CHAR(36) PRIMARY KEY,
 previous_token_sha256 CHAR(64) NOT NULL UNIQUE,
 rotation_id_sha256 CHAR(64) NOT NULL,
 rotated_version BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_identity_refresh_receipt_session FOREIGN KEY(session_id) REFERENCES identity_session(id)
);
