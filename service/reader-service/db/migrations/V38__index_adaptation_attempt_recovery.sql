-- 通过状态缩小未决调用范围，已发送调用按绝对结算截止时间扫描。
CREATE INDEX idx_adaptation_attempt_recovery
    ON novel_chapter_adaptation_attempt(status, settlement_expires_at, adaptation_id);

-- 过期预留和未知发送结果也需等待调度取消屏障，保留各自可解释的停止原因。
ALTER TABLE novel_chapter_adaptation DROP CONSTRAINT ck_adaptation_dispatch_abort;
ALTER TABLE novel_chapter_adaptation ADD CONSTRAINT ck_adaptation_dispatch_abort
    CHECK (dispatch_abort_error_code IS NULL OR dispatch_abort_error_code IN ('READER_035', 'READER_044', 'READER_048', 'READER_054', 'READER_058'));
