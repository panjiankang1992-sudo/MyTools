CREATE TABLE IF NOT EXISTS t_email_verification_code (
    id BIGINT PRIMARY KEY COMMENT '验证码记录ID',
    purpose VARCHAR(32) NOT NULL COMMENT '验证码用途',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    email VARCHAR(100) NOT NULL COMMENT '邮箱',
    phone VARCHAR(20) NOT NULL COMMENT '手机号',
    code_hash VARCHAR(128) NOT NULL COMMENT '验证码哈希值',
    expire_time DATETIME NOT NULL COMMENT '过期时间',
    used_time DATETIME DEFAULT NULL COMMENT '使用时间',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE、USED、INVALID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_email_purpose_status (email, purpose, status),
    INDEX idx_register_fields (username, email, phone, purpose, status),
    INDEX idx_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮箱验证码表';
