CREATE TABLE onebot_inbound_processing (
    inbound_message_id CHAR(36) PRIMARY KEY,
    account_key VARCHAR(255) NOT NULL,
    raw_event_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    has_failures BOOLEAN NOT NULL DEFAULT FALSE,
    notified_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_onebot_processing_message
        FOREIGN KEY (inbound_message_id) REFERENCES inbound_message(id)
);
