-- Token表结构更新
-- 支持多令牌管理、版本控制、状态追踪

-- 删除旧表（如果存在且结构不兼容）
-- DROP TABLE IF EXISTS t_token;

-- 创建新的Token表
CREATE TABLE IF NOT EXISTS t_token (
    id BIGINT PRIMARY KEY COMMENT '令牌记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    access_token VARCHAR(512) NOT NULL UNIQUE COMMENT 'Access Token',
    refresh_token VARCHAR(512) COMMENT 'Refresh Token',
    token_type VARCHAR(20) DEFAULT 'Bearer' COMMENT 'Token类型',
    expire_time BIGINT NOT NULL COMMENT 'Access Token过期时间戳（毫秒）',
    refresh_expire_time BIGINT COMMENT 'Refresh Token过期时间戳（毫秒）',
    version INT DEFAULT 1 COMMENT '版本号（乐观锁）',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-有效，INVALID-失效',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户令牌表';
