CREATE TABLE news_subscription (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL UNIQUE,
    revision BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    cutoff_time VARCHAR(5) NOT NULL,
    next_date VARCHAR(10),
    next_due_ms BIGINT,
    updated_ms BIGINT NOT NULL
);
CREATE INDEX idx_news_subscription_due ON news_subscription(enabled, next_due_ms);
CREATE TABLE news_daily_run (
    id CHAR(36) PRIMARY KEY,
    subscription_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    subscription_revision BIGINT NOT NULL,
    business_date VARCHAR(10) NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    cutoff_ms BIGINT NOT NULL,
    expires_ms BIGINT NOT NULL,
    skipped_days INT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_id CHAR(36),
    task_status VARCHAR(32),
    edition_id CHAR(36),
    last_fence BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    created_ms BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL,
    CONSTRAINT uk_news_daily_run UNIQUE(subscription_id, business_date),
    CONSTRAINT uk_news_daily_task UNIQUE(task_id)
);
CREATE INDEX idx_news_run_owner ON news_daily_run(owner_id, created_ms);
CREATE TABLE news_task_outbox (
    run_id CHAR(36) PRIMARY KEY,
    state VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_ms BIGINT NOT NULL,
    lease_token CHAR(36),
    lease_until_ms BIGINT,
    error_code VARCHAR(64)
);
CREATE INDEX idx_news_outbox_due ON news_task_outbox(state, available_ms, lease_until_ms);
