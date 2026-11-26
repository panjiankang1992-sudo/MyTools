-- 手动七天任务只由用户请求创建，队列与幂等身份随服务重启保留。
CREATE TABLE news_weekly_lock (id INT PRIMARY KEY);
INSERT INTO news_weekly_lock(id) VALUES (1);

CREATE TABLE news_weekly_job (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    window_end_ms BIGINT NOT NULL,
    created_ms BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL,
    expires_ms BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt INT NOT NULL,
    revision BIGINT NOT NULL,
    dispatch_id VARCHAR(36) NOT NULL UNIQUE,
    task_id VARCHAR(36),
    last_fence BIGINT NOT NULL DEFAULT 0,
    report_revision BIGINT NOT NULL DEFAULT 0,
    progress_json LONGTEXT NOT NULL,
    error_code VARCHAR(80),
    next_dispatch_ms BIGINT NOT NULL,
    dispatch_token VARCHAR(36),
    dispatch_until_ms BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_news_weekly_owner ON news_weekly_job(owner_id, created_ms, id);
CREATE INDEX idx_news_weekly_dispatch ON news_weekly_job(status, next_dispatch_ms, dispatch_until_ms);

-- 每个点击键永久指向同一受理结果；并发误点复用任务也必须登记键。
CREATE TABLE news_weekly_request (
    owner_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    operation VARCHAR(80) NOT NULL,
    created_ms BIGINT NOT NULL,
    PRIMARY KEY(owner_id, request_key)
);

-- 重试产生新的不可变报告修订，同一任务在历史中仍只展示一项。
CREATE TABLE news_weekly_report (
    job_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    created_ms BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    PRIMARY KEY(job_id, revision)
);
