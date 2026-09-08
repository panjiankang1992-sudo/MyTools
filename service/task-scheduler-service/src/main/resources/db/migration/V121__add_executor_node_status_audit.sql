ALTER TABLE executor_node ADD COLUMN status_changed_at TIMESTAMP(6) NULL;
ALTER TABLE executor_node ADD COLUMN status_reason VARCHAR(256) NULL;

UPDATE executor_node
SET status_changed_at = updated_at, status_reason = 'MIGRATED_CURRENT_STATE'
WHERE status_changed_at IS NULL;

CREATE INDEX idx_executor_node_health
    ON executor_node(enabled, status, last_heartbeat_at);
