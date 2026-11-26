ALTER TABLE news_article ADD COLUMN evidence_expired_ms BIGINT;
CREATE INDEX idx_news_article_retention ON news_article(evidence_expired_ms,fetched_ms);
ALTER TABLE news_github_snapshot ADD COLUMN evidence_expired_ms BIGINT;
CREATE INDEX idx_news_github_retention ON news_github_snapshot(evidence_expired_ms,observed_ms);
ALTER TABLE news_edition MODIFY payload_json LONGTEXT NULL;
ALTER TABLE news_edition ADD COLUMN content_expired_ms BIGINT;
CREATE INDEX idx_news_edition_retention ON news_edition(content_expired_ms,created_ms);
ALTER TABLE news_dispatch_outbox MODIFY content_json MEDIUMTEXT NULL;
ALTER TABLE news_dispatch_outbox ADD COLUMN content_expired_ms BIGINT;
CREATE TABLE news_source_retention (
    source_id VARCHAR(64) PRIMARY KEY,
    evidence_hours INT NOT NULL,
    updated_ms BIGINT NOT NULL,
    CONSTRAINT fk_news_retention_source FOREIGN KEY(source_id) REFERENCES news_source(id)
);
CREATE TABLE news_retention_state (
    id INT PRIMARY KEY,
    lease_token CHAR(36),
    lease_until_ms BIGINT,
    last_started_ms BIGINT,
    last_finished_ms BIGINT,
    last_status VARCHAR(16) NOT NULL,
    counts_json VARCHAR(2048)
);
INSERT INTO news_retention_state(id,last_status) VALUES(1,'NEVER_RUN');
