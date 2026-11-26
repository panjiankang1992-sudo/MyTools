ALTER TABLE messaging_outbound MODIFY content_json MEDIUMTEXT NULL;
ALTER TABLE messaging_outbound ADD COLUMN content_identity_hash CHAR(64);
ALTER TABLE messaging_outbound ADD COLUMN content_expired_ms BIGINT;
CREATE INDEX idx_outbound_retention ON messaging_outbound(content_expired_ms,updated_ms);
CREATE INDEX idx_outbound_attempt_retention ON messaging_outbound_attempt(finished_ms);
CREATE TABLE messaging_outbound_retention_state (
    id INT PRIMARY KEY,
    lease_token CHAR(36),
    lease_until_ms BIGINT,
    last_started_ms BIGINT,
    last_finished_ms BIGINT,
    last_status VARCHAR(16) NOT NULL,
    counts_json VARCHAR(1024)
);
INSERT INTO messaging_outbound_retention_state(id,last_status) VALUES(1,'NEVER_RUN');
