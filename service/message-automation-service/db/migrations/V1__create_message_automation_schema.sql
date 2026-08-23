CREATE TABLE automation_rule (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    conversation_key VARCHAR(512),
    sender_ref VARCHAR(1024),
    command_prefix VARCHAR(128) NOT NULL,
    priority INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_automation_rule_name (owner_id, name)
);

CREATE TABLE action_binding (
    id CHAR(36) PRIMARY KEY,
    automation_rule_id CHAR(36) NOT NULL UNIQUE,
    action_type VARCHAR(64) NOT NULL,
    request_kind VARCHAR(64) NOT NULL,
    max_actions INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_action_binding_rule FOREIGN KEY (automation_rule_id) REFERENCES automation_rule(id)
);

CREATE TABLE automation_run (
    id CHAR(36) PRIMARY KEY,
    inbound_message_id CHAR(36) NOT NULL UNIQUE,
    automation_rule_id CHAR(36),
    rule_version INT,
    status VARCHAR(32) NOT NULL,
    action_count INT NOT NULL,
    action_refs_json JSON NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_automation_run_rule FOREIGN KEY (automation_rule_id) REFERENCES automation_rule(id)
);

CREATE TABLE automation_outbox (
    id CHAR(36) PRIMARY KEY,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6)
);

CREATE INDEX idx_automation_outbox_unpublished ON automation_outbox (published_at, created_at);
