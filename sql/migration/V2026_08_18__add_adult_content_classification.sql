ALTER TABLE local_file
    ADD COLUMN adult_status TINYINT NOT NULL DEFAULT 0 COMMENT '成人内容识别状态：0-待识别，1-成功，2-失败' AFTER tagging_status,
    ADD COLUMN adult_content TINYINT(1) NULL COMMENT '是否为R18或成人向内容' AFTER adult_status,
    ADD COLUMN adult_confidence DECIMAL(5,4) NULL COMMENT '成人内容识别置信度' AFTER adult_content,
    ADD INDEX idx_local_file_adult_status (adult_status),
    ADD INDEX idx_local_file_adult_content (adult_content, deleted);
