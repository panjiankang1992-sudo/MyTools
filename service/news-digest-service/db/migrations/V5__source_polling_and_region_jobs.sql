ALTER TABLE news_task_outbox ADD COLUMN job_kind VARCHAR(16) NOT NULL DEFAULT 'EDITION';
CREATE INDEX idx_news_outbox_kind ON news_task_outbox(job_kind, state, available_ms);
CREATE TABLE news_source_poll (
    source_id VARCHAR(128) PRIMARY KEY,
    next_due_ms BIGINT NOT NULL,
    lease_token CHAR(36),
    lease_until_ms BIGINT,
    failures INT NOT NULL DEFAULT 0,
    last_status VARCHAR(64),
    last_finished_ms BIGINT
);
CREATE INDEX idx_news_source_poll_due ON news_source_poll(next_due_ms, lease_until_ms);
CREATE TABLE news_region_job (
    id CHAR(36) PRIMARY KEY,
    node_id VARCHAR(128) NOT NULL,
    bucket_ms BIGINT NOT NULL,
    expires_ms BIGINT NOT NULL,
    task_id CHAR(36) UNIQUE,
    task_status VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    last_fence BIGINT NOT NULL DEFAULT 0,
    observation_id CHAR(36),
    observation_status VARCHAR(32),
    error_code VARCHAR(64),
    created_ms BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL,
    CONSTRAINT uk_news_region_job UNIQUE(node_id, bucket_ms)
);
CREATE TABLE news_region_schedule (node_id VARCHAR(128) PRIMARY KEY);
