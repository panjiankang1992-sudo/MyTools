ALTER TABLE novel_chapter_adaptation_validation ADD COLUMN content_policy_outcome VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE novel_chapter_adaptation_validation ADD CONSTRAINT ck_adaptation_validation_policy
    CHECK (content_policy_outcome IN ('PASS', 'BLOCKED', 'UNKNOWN'));
CREATE INDEX idx_adaptation_validation_visibility
    ON novel_chapter_adaptation_validation(owner_id, adaptation_id, candidate_attempt_id, content_policy_outcome);
