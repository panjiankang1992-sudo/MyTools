CREATE TABLE identity_user (
 id BIGINT PRIMARY KEY, external_user_id VARCHAR(255) NOT NULL UNIQUE, username VARCHAR(128) NOT NULL UNIQUE,
 email VARCHAR(320) UNIQUE, password_hash VARCHAR(255) NOT NULL, status VARCHAR(32) NOT NULL,
 credential_version BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE identity_role (id CHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL UNIQUE, description VARCHAR(255), created_at TIMESTAMP(6) NOT NULL);
CREATE TABLE identity_user_role (user_id BIGINT NOT NULL, role_id CHAR(36) NOT NULL, created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(user_id,role_id), CONSTRAINT fk_identity_ur_user FOREIGN KEY(user_id) REFERENCES identity_user(id), CONSTRAINT fk_identity_ur_role FOREIGN KEY(role_id) REFERENCES identity_role(id));
CREATE TABLE identity_session (
 id CHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, device_id VARCHAR(255) NOT NULL, refresh_token_sha256 CHAR(64) NOT NULL UNIQUE,
 version BIGINT NOT NULL, credential_version BIGINT NOT NULL, issued_at TIMESTAMP(6) NOT NULL, refresh_expires_at TIMESTAMP(6) NOT NULL,
 revoked_at TIMESTAMP(6), revoke_reason VARCHAR(64), last_seen_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_identity_session_user FOREIGN KEY(user_id) REFERENCES identity_user(id)
);
CREATE INDEX idx_identity_session_user ON identity_session(user_id,revoked_at);
CREATE TABLE identity_verification_code (id CHAR(36) PRIMARY KEY,user_id BIGINT,email VARCHAR(320) NOT NULL,purpose VARCHAR(64) NOT NULL,code_sha256 CHAR(64) NOT NULL,expires_at TIMESTAMP(6) NOT NULL,consumed_at TIMESTAMP(6),attempt_count INT NOT NULL,created_at TIMESTAMP(6) NOT NULL);
CREATE TABLE identity_login_attempt (identity_key_sha256 CHAR(64) PRIMARY KEY,failure_count INT NOT NULL,locked_until TIMESTAMP(6),last_failure_at TIMESTAMP(6),updated_at TIMESTAMP(6) NOT NULL);
CREATE TABLE identity_outbox (id CHAR(36) PRIMARY KEY,aggregate_type VARCHAR(64) NOT NULL,aggregate_id VARCHAR(255) NOT NULL,event_type VARCHAR(128) NOT NULL,payload_json TEXT NOT NULL,created_at TIMESTAMP(6) NOT NULL,published_at TIMESTAMP(6));
