CREATE TABLE task_schedule_cursor (
    task_definition_id CHAR(36) PRIMARY KEY,
    next_fire_at TIMESTAMP(6) NOT NULL,
    last_scheduled_at TIMESTAMP(6),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_schedule_definition FOREIGN KEY (task_definition_id) REFERENCES task_definition(id)
);

CREATE INDEX idx_task_schedule_due ON task_schedule_cursor(next_fire_at, lease_until);
