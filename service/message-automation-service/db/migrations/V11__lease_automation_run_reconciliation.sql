ALTER TABLE automation_run
    ADD COLUMN reconcile_claimed_by VARCHAR(64) NULL;

ALTER TABLE automation_run
    ADD COLUMN reconcile_claim_until TIMESTAMP(6) NULL;

ALTER TABLE automation_run
    ADD COLUMN reconciliation_failures INT NOT NULL DEFAULT 0;

ALTER TABLE automation_run
    ADD COLUMN next_reconcile_at TIMESTAMP(6) NULL;

ALTER TABLE automation_run
    ADD COLUMN last_reconciliation_error VARCHAR(64) NULL;

CREATE INDEX idx_automation_run_reconciliation
    ON automation_run
        (status, next_reconcile_at, reconcile_claim_until, updated_at, id);
