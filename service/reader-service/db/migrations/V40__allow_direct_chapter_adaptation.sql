-- 新请求直接创建，不伪造授权；历史请求保留原授权快照与撤销语义。
ALTER TABLE novel_chapter_adaptation MODIFY COLUMN disclosure_version VARBINARY(64) NULL;
ALTER TABLE novel_chapter_adaptation ADD COLUMN consent_policy VARCHAR(16) NOT NULL DEFAULT 'EXPLICIT';
ALTER TABLE novel_chapter_adaptation ADD CONSTRAINT ck_adaptation_consent_policy
    CHECK ((consent_policy = 'EXPLICIT' AND disclosure_version IS NOT NULL)
        OR (consent_policy = 'DIRECT' AND disclosure_version IS NULL AND consent_revision = 0));
