ALTER TABLE messaging_outbound ADD COLUMN content_until_ms BIGINT;
CREATE INDEX idx_outbound_content_deadline ON messaging_outbound(content_until_ms,content_expired_ms);
ALTER TABLE messaging_outbound ADD CONSTRAINT ck_outbound_deadline_identity CHECK (content_until_ms IS NULL OR content_identity_hash IS NOT NULL);
CREATE TABLE messaging_outbound_deadline_state (
    id INT PRIMARY KEY,
    lease_token CHAR(36),
    lease_until_ms BIGINT,
    last_started_ms BIGINT,
    last_finished_ms BIGINT,
    last_status VARCHAR(16) NOT NULL,
    counts_json VARCHAR(1024)
);
INSERT INTO messaging_outbound_deadline_state(id,last_status) VALUES(1,'NEVER_RUN');
