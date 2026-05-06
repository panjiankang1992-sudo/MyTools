-- 本地文件表
CREATE TABLE IF NOT EXISTS local_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    filename VARCHAR(255) NOT NULL COMMENT '文件名',
    file_path VARCHAR(1024) NOT NULL COMMENT '文件路径',
    file_size BIGINT COMMENT '文件大小（字节）',
    mime_type VARCHAR(100) COMMENT '文件类型（MIME type）',
    extension VARCHAR(50) COMMENT '文件扩展名',
    file_hash VARCHAR(64) COMMENT '文件哈希值（SHA-256）',
    thumbnail_path VARCHAR(1024) COMMENT '缩略图路径（图片/视频）',
    tagging_status TINYINT DEFAULT 0 COMMENT '标签状态：0-未打标签，1-已打标签，2-打标签失败',
    scan_time TIMESTAMP COMMENT '扫描时间',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_file_hash (file_hash),
    INDEX idx_tagging_status (tagging_status),
    INDEX idx_scan_time (scan_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地文件表';

-- 文件标签表
CREATE TABLE IF NOT EXISTS file_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_id BIGINT NOT NULL COMMENT '文件ID',
    tag_name VARCHAR(100) NOT NULL COMMENT '标签名称',
    tag_type VARCHAR(50) COMMENT '标签类型',
    confidence DOUBLE COMMENT '置信度',
    tagging_time TIMESTAMP COMMENT '打标签时间',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_file_id (file_id),
    INDEX idx_tag_name (tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件标签表';

-- 刷新日志表（如果不存在）
CREATE TABLE IF NOT EXISTS refresh_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    account_id BIGINT NOT NULL COMMENT '账号ID',
    operate_type TINYINT NOT NULL COMMENT '操作类型：0-刷新任务列表，1-发布朋友圈，2-手动刷新，3-自动刷新',
    success_count INT DEFAULT 0 COMMENT '成功数量',
    fail_count INT DEFAULT 0 COMMENT '失败数量',
    operate_time TIMESTAMP COMMENT '操作时间',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_account_id (account_id),
    INDEX idx_operate_time (operate_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='刷新日志表';
