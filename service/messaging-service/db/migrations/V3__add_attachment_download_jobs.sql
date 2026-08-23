CREATE TABLE attachment_download_job (
    id CHAR(36) PRIMARY KEY,
    inbound_message_id CHAR(36) NOT NULL,
    message_part_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    download_request_id CHAR(36),
    last_error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_attachment_job_message FOREIGN KEY (inbound_message_id) REFERENCES inbound_message(id),
    CONSTRAINT fk_attachment_job_part FOREIGN KEY (message_part_id) REFERENCES inbound_message_part(id),
    UNIQUE KEY uk_attachment_job_part (message_part_id)
);

CREATE INDEX idx_attachment_job_task ON attachment_download_job (task_instance_id);
