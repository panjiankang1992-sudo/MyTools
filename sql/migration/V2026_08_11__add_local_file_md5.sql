-- 增加MD5字段，用于完全重复文件识别。
ALTER TABLE local_file
    ADD COLUMN md5_hash VARCHAR(32) NULL COMMENT '文件MD5值' AFTER file_hash,
    ADD INDEX idx_local_file_md5_deleted (md5_hash, deleted);

-- 记录隔离和重命名动作，便于审计与恢复。
CREATE TABLE IF NOT EXISTS file_maintenance_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id VARCHAR(64) NOT NULL COMMENT '维护任务ID',
    file_id BIGINT NOT NULL COMMENT '文件ID',
    action VARCHAR(32) NOT NULL COMMENT '操作类型',
    original_path VARCHAR(1024) NOT NULL COMMENT '原始路径',
    target_path VARCHAR(1024) NOT NULL COMMENT '目标路径',
    reason VARCHAR(512) NULL COMMENT '操作原因',
    score DECIMAL(8, 6) NULL COMMENT '判断分数',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_file_maintenance_task (task_id),
    INDEX idx_file_maintenance_file (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件维护操作记录';
