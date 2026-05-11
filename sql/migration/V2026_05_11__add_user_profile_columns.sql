-- 用户表扩展字段
-- 支持用户个人信息维护

-- 添加昵称字段
ALTER TABLE t_user
ADD COLUMN nickname VARCHAR(50) DEFAULT NULL COMMENT '用户昵称' AFTER username;

-- 添加头像URL字段
ALTER TABLE t_user
ADD COLUMN avatar VARCHAR(500) DEFAULT NULL COMMENT '头像URL' AFTER nickname;

-- 添加生日字段
ALTER TABLE t_user
ADD COLUMN birthday DATE DEFAULT NULL COMMENT '生日' AFTER gender;

-- 添加地址字段
ALTER TABLE t_user
ADD COLUMN address VARCHAR(255) DEFAULT NULL COMMENT '地址' AFTER birthday;

-- 添加爱好字段
ALTER TABLE t_user
ADD COLUMN hobbies VARCHAR(500) DEFAULT NULL COMMENT '爱好' AFTER address;

-- 添加个人签名字段
ALTER TABLE t_user
ADD COLUMN signature VARCHAR(255) DEFAULT NULL COMMENT '个人签名' AFTER hobbies;

-- 为现有用户设置默认昵称（使用用户名）
UPDATE t_user SET nickname = username WHERE nickname IS NULL;

-- 添加审计日志表
CREATE TABLE IF NOT EXISTS t_api_log (
    id BIGINT PRIMARY KEY COMMENT '日志ID',
    user_id BIGINT DEFAULT NULL COMMENT '用户ID（未登录为NULL）',
    username VARCHAR(50) DEFAULT NULL COMMENT '用户名',
    module VARCHAR(50) DEFAULT NULL COMMENT '模块名称',
    api_path VARCHAR(255) NOT NULL COMMENT '请求路径',
    method VARCHAR(10) NOT NULL COMMENT 'HTTP方法',
    request_method VARCHAR(50) DEFAULT NULL COMMENT '请求方法名',
    success TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否成功：1-成功，0-失败',
    error_message TEXT DEFAULT NULL COMMENT '错误信息',
    duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '请求耗时（毫秒）',
    ip_address VARCHAR(50) DEFAULT NULL COMMENT 'IP地址',
    request_time DATETIME NOT NULL COMMENT '请求时间',
    response_time DATETIME DEFAULT NULL COMMENT '响应时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_api_path (api_path),
    INDEX idx_request_time (request_time),
    INDEX idx_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API审计日志表';
