ALTER TABLE ebook_metadata
    ADD COLUMN failure_count INT NOT NULL DEFAULT 0 COMMENT '连续失败次数' AFTER error_message,
    ADD COLUMN retry_after TIMESTAMP NULL COMMENT '失败后下次重试时间' AFTER failure_count;

UPDATE ebook_metadata
SET failure_count = CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END,
    retry_after = CASE WHEN status = 'FAILED' THEN CURRENT_TIMESTAMP ELSE NULL END;
