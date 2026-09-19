-- 来源核验是短期派生信息，不创建或改写业务版本、上下文及调用记录。
CREATE TABLE novel_adaptation_source_check_capacity (
    id INT PRIMARY KEY,
    CONSTRAINT ck_source_check_capacity CHECK (id = 1)
);
INSERT INTO novel_adaptation_source_check_capacity (id) VALUES (1);

CREATE TABLE novel_adaptation_source_check (
    adaptation_id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    check_id CHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    claimed_until TIMESTAMP(6),
    checked_at TIMESTAMP(6),
    valid_until TIMESTAMP(6),
    manifest_sha256 CHAR(64),
    content_tokens_json JSON,
    reason_code VARCHAR(32),
    CONSTRAINT fk_source_check_owner FOREIGN KEY (adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_source_check_status CHECK (status IN ('QUEUED', 'CHECKING', 'CURRENT', 'STALE', 'UNKNOWN')),
    CONSTRAINT ck_source_check_time CHECK (deadline_at > requested_at),
    CONSTRAINT ck_source_check_claim CHECK (status <> 'CHECKING' OR claimed_until IS NOT NULL),
    CONSTRAINT ck_source_check_current CHECK (status <> 'CURRENT' OR
        (checked_at IS NOT NULL AND valid_until IS NOT NULL AND valid_until > checked_at
            AND manifest_sha256 IS NOT NULL AND content_tokens_json IS NOT NULL))
);
CREATE INDEX idx_source_check_queue ON novel_adaptation_source_check (status, requested_at);
CREATE INDEX idx_source_check_owner ON novel_adaptation_source_check (owner_id, status, deadline_at);
