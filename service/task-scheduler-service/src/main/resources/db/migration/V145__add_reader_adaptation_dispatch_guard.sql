-- 即使任务尚未创建，取消也先持久保留其确定性业务键，阻止迟到提交。
CREATE TABLE reader_adaptation_dispatch_guard (
    adaptation_id CHAR(36) PRIMARY KEY,
    idempotency_key VARBINARY(255) NOT NULL UNIQUE,
    task_instance_id CHAR(36),
    cancel_requested_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_reader_adaptation_dispatch_task UNIQUE (task_instance_id),
    CONSTRAINT fk_reader_adaptation_dispatch_task FOREIGN KEY (task_instance_id)
        REFERENCES task_instance(id) ON DELETE RESTRICT
);
