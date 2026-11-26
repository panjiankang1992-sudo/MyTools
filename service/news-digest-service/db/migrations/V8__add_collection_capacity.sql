ALTER TABLE news_article ADD COLUMN capacity_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE news_github_snapshot ADD COLUMN capacity_bytes BIGINT NOT NULL DEFAULT 0;
UPDATE news_article SET capacity_bytes=OCTET_LENGTH(payload_json)+128;
UPDATE news_github_snapshot SET capacity_bytes=OCTET_LENGTH(payload_json);
ALTER TABLE news_article ADD CONSTRAINT chk_news_article_capacity CHECK (capacity_bytes>0 AND capacity_bytes>=OCTET_LENGTH(payload_json));
ALTER TABLE news_github_snapshot ADD CONSTRAINT chk_news_snapshot_capacity CHECK (capacity_bytes>0 AND capacity_bytes>=OCTET_LENGTH(payload_json));
ALTER TABLE news_source_withdrawal ADD COLUMN capacity_recorded BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE news_source_withdrawal SET capacity_recorded=TRUE;
ALTER TABLE news_source_withdrawal ADD CONSTRAINT chk_news_withdrawal_capacity CHECK (capacity_recorded=TRUE);
CREATE TABLE news_collection_capacity (
    id INT PRIMARY KEY,
    article_rows BIGINT NOT NULL,
    snapshot_rows BIGINT NOT NULL,
    withdrawal_rows BIGINT NOT NULL,
    payload_bytes BIGINT NOT NULL,
    CONSTRAINT chk_news_capacity_id CHECK (id=1),
    CONSTRAINT chk_news_capacity_usage CHECK (article_rows>=0 AND snapshot_rows>=0 AND withdrawal_rows>=0 AND payload_bytes>=0)
);
INSERT INTO news_collection_capacity(id,article_rows,snapshot_rows,withdrawal_rows,payload_bytes)
SELECT 1,(SELECT COUNT(*) FROM news_article),(SELECT COUNT(*) FROM news_github_snapshot),
    (SELECT COUNT(*) FROM news_source_withdrawal),
    (SELECT COALESCE(SUM(capacity_bytes),0) FROM news_article)+(SELECT COALESCE(SUM(capacity_bytes),0) FROM news_github_snapshot);
