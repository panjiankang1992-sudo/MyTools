-- 派发失败或截止先等待 Scheduler 取消屏障，再决定 Reader 终态，不能直接遗弃未知任务。
ALTER TABLE novel_chapter_adaptation ADD COLUMN dispatch_abort_error_code VARCHAR(32);
ALTER TABLE novel_chapter_adaptation ADD CONSTRAINT ck_adaptation_dispatch_abort
    CHECK (dispatch_abort_error_code IS NULL OR dispatch_abort_error_code IN ('READER_035', 'READER_048', 'READER_058'));
CREATE INDEX idx_adaptation_reconcile_due ON novel_chapter_adaptation(next_dispatch_at, dispatch_claimed_until, status);
