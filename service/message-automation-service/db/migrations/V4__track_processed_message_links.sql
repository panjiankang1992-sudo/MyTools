CREATE TABLE processed_message_link (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    url_sha256 CHAR(64) NOT NULL,
    normalized_url TEXT NOT NULL,
    inbound_message_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_processed_message_link (owner_id, url_sha256),
    CONSTRAINT fk_processed_link_message FOREIGN KEY (inbound_message_id) REFERENCES automation_run(inbound_message_id)
);

CREATE INDEX idx_processed_link_message ON processed_message_link (inbound_message_id, status);
