ALTER TABLE news_source ADD COLUMN next_attempt_ms BIGINT;
ALTER TABLE news_source ADD COLUMN coverage_json LONGTEXT;
ALTER TABLE news_source ADD COLUMN newest_observed_ms BIGINT;
ALTER TABLE news_article ADD COLUMN ranking_ms BIGINT;
ALTER TABLE news_article ADD COLUMN external_id VARCHAR(64);
UPDATE news_article SET ranking_ms = published_ms;
CREATE INDEX idx_news_article_ranking ON news_article(ranking_ms, fetched_ms);

CREATE TABLE news_github_snapshot (
    source_id VARCHAR(64) NOT NULL,
    repository_id BIGINT NOT NULL,
    bucket_ms BIGINT NOT NULL,
    observed_ms BIGINT NOT NULL,
    full_name VARCHAR(256) NOT NULL,
    stars BIGINT NOT NULL,
    forks BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    PRIMARY KEY (source_id, repository_id, bucket_ms),
    CONSTRAINT fk_news_github_source FOREIGN KEY (source_id) REFERENCES news_source(id)
);
CREATE INDEX idx_news_github_observed ON news_github_snapshot(source_id, observed_ms);

CREATE TABLE news_source_withdrawal (
    source_id VARCHAR(64) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    observed_ms BIGINT NOT NULL,
    PRIMARY KEY (source_id, external_id, observed_ms),
    CONSTRAINT fk_news_withdrawal_source FOREIGN KEY (source_id) REFERENCES news_source(id)
);

CREATE TABLE news_provider_cooldown (
    provider_kind VARCHAR(32) PRIMARY KEY,
    retry_ms BIGINT NOT NULL
);
