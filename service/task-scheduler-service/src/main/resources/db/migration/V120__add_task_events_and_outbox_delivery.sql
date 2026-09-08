CREATE TABLE task_event (
    id CHAR(36) PRIMARY KEY,
    task_instance_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    old_status VARCHAR(32) NULL,
    new_status VARCHAR(32) NULL,
    reason VARCHAR(128) NULL,
    actor_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_task_event_source UNIQUE (task_instance_id, event_type, source_id),
    CONSTRAINT fk_task_event_instance FOREIGN KEY (task_instance_id) REFERENCES task_instance(id)
);

CREATE INDEX idx_task_event_timeline ON task_event(task_instance_id, created_at);

ALTER TABLE task_outbox ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE task_outbox ADD COLUMN next_attempt_at TIMESTAMP(6) NULL;
ALTER TABLE task_outbox ADD COLUMN locked_at TIMESTAMP(6) NULL;
ALTER TABLE task_outbox ADD COLUMN last_error VARCHAR(1024) NULL;

CREATE INDEX idx_task_outbox_delivery
    ON task_outbox(status, next_attempt_at, created_at);
