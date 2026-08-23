CREATE TABLE inbound_message_part (
    id CHAR(36) PRIMARY KEY,
    inbound_message_id CHAR(36) NOT NULL,
    sequence_number INT NOT NULL,
    part_type VARCHAR(32) NOT NULL,
    text_content MEDIUMTEXT,
    attachment_type VARCHAR(32),
    provider_file_id VARCHAR(512),
    source_url VARCHAR(4096),
    file_name VARCHAR(1024),
    mime_type VARCHAR(255),
    declared_size BIGINT,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_inbound_part_message FOREIGN KEY (inbound_message_id) REFERENCES inbound_message(id),
    UNIQUE KEY uk_inbound_part_sequence (inbound_message_id, sequence_number)
);

CREATE INDEX idx_inbound_part_message ON inbound_message_part (inbound_message_id, part_type);
