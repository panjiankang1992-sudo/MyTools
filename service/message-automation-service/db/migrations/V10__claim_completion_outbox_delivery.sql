ALTER TABLE automation_outbox
    ADD COLUMN claimed_by VARCHAR(64) NULL;

ALTER TABLE automation_outbox
    ADD COLUMN claim_until TIMESTAMP(6) NULL;

CREATE INDEX idx_automation_outbox_claim
    ON automation_outbox
        (published_at, dead_at, next_attempt_at, claim_until, created_at);
