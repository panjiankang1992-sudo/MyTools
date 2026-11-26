CREATE TABLE news_content_identity (
    owner_id BIGINT NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    identity_kind VARCHAR(16) NOT NULL,
    run_id CHAR(36) UNIQUE,
    PRIMARY KEY(owner_id,idempotency_hash),
    CONSTRAINT chk_news_identity_kind CHECK (identity_kind IN ('LEGACY','PREVIEW','SCHEDULED')),
    CONSTRAINT chk_news_identity_run CHECK ((identity_kind='SCHEDULED' AND run_id IS NOT NULL) OR (identity_kind<>'SCHEDULED' AND run_id IS NULL))
);
ALTER TABLE news_daily_run ADD COLUMN delivery_captured BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE news_daily_run SET delivery_captured=TRUE;
INSERT INTO news_content_identity(owner_id,idempotency_hash,request_hash,identity_kind)
SELECT owner_id,idempotency_hash,request_hash,'LEGACY' FROM news_region_snapshot
UNION
SELECT owner_id,idempotency_hash,request_hash,'LEGACY' FROM news_edition;
ALTER TABLE news_region_snapshot ADD CONSTRAINT fk_news_region_identity FOREIGN KEY(owner_id,idempotency_hash) REFERENCES news_content_identity(owner_id,idempotency_hash);
ALTER TABLE news_edition ADD CONSTRAINT fk_news_edition_identity FOREIGN KEY(owner_id,idempotency_hash) REFERENCES news_content_identity(owner_id,idempotency_hash);
CREATE TABLE news_control_capacity (
    id INT PRIMARY KEY,
    subscription_rows BIGINT NOT NULL,
    target_owners BIGINT NOT NULL,
    daily_runs BIGINT NOT NULL,
    preview_identities BIGINT NOT NULL,
    CONSTRAINT chk_news_control_capacity CHECK(id=1 AND subscription_rows>=0 AND target_owners>=0 AND daily_runs>=0 AND preview_identities>=0)
);
INSERT INTO news_control_capacity(id,subscription_rows,target_owners,daily_runs,preview_identities)
SELECT 1,(SELECT COUNT(*) FROM news_subscription),(SELECT COUNT(*) FROM news_delivery_settings),
    (SELECT COUNT(*) FROM news_daily_run),(SELECT COUNT(*) FROM news_content_identity);
