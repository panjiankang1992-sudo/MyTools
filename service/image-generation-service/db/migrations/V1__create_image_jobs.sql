CREATE TABLE image_upload (
 id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, mime_type VARCHAR(40) NOT NULL,
 size_bytes BIGINT NOT NULL, content_sha256 CHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_image_upload_owner ON image_upload(owner_id,created_at);
CREATE TABLE image_job (
 id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 request_sha256 CHAR(64) NOT NULL, request_json TEXT NOT NULL,
 status VARCHAR(32) NOT NULL, task_id CHAR(36), dispatch_started BOOLEAN NOT NULL DEFAULT FALSE, result_json TEXT,
 error_code VARCHAR(64), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_image_owner_request UNIQUE(owner_id,idempotency_key)
);
CREATE INDEX idx_image_job_status ON image_job(status,created_at);
CREATE INDEX idx_image_job_owner ON image_job(owner_id,created_at);
