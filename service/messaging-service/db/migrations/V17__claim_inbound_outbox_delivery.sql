ALTER TABLE messaging_outbox
    ADD COLUMN claim_token VARCHAR(64) NULL;

ALTER TABLE messaging_outbox
    ADD COLUMN claim_until TIMESTAMP(6) NULL;

CREATE INDEX idx_messaging_outbox_claim
    ON messaging_outbox
        (published_at, dead_at, event_type, next_attempt_at, claim_until, created_at, id);
