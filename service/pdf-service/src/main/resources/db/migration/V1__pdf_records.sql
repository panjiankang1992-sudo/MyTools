-- 领域对象与待调度工作共用持久状态；队列状态同时作为事务内的派发 Outbox。
CREATE TABLE pdf_owner (owner_id BIGINT PRIMARY KEY);
CREATE TABLE pdf_record (
 id CHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL,
 payload_json LONGTEXT NOT NULL,
 result_json LONGTEXT,
 error_code VARCHAR(32),
 idempotency_key VARCHAR(128),
 payload_digest CHAR(64) NOT NULL,
 task_id CHAR(36),
 dispatch_started BOOLEAN NOT NULL DEFAULT FALSE,
 fencing_token BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMP NOT NULL,
 UNIQUE (owner_id, kind, idempotency_key)
);
CREATE INDEX idx_pdf_owner_kind ON pdf_record(owner_id, kind, created_at);
CREATE INDEX idx_pdf_pending ON pdf_record(status, updated_at);
