-- H2 Schema for Integration Tests
-- Mode: MySQL compatible

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(20) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100) UNIQUE,
    phone VARCHAR(20),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    register_time TIMESTAMP NOT NULL,
    last_login_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

-- 角色表
CREATE TABLE IF NOT EXISTS t_role (
    id BIGINT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    create_time TIMESTAMP NOT NULL
);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS t_user_role (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    UNIQUE (user_id, role_id)
);

-- Token表
CREATE TABLE IF NOT EXISTS t_token (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL UNIQUE,
    device_info VARCHAR(255),
    expires_at TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL
);

-- 错误码表
CREATE TABLE IF NOT EXISTS t_error_code (
    id BIGINT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    error_key VARCHAR(50) NOT NULL,
    message VARCHAR(255) NOT NULL,
    category VARCHAR(20) NOT NULL,
    create_time TIMESTAMP NOT NULL
);

-- 初始化默认角色
INSERT INTO t_role (id, role_name, description, create_time) VALUES
(1, 'ADMIN', '管理员', CURRENT_TIMESTAMP),
(2, 'USER', '普通用户', CURRENT_TIMESTAMP);

-- 初始化错误码
INSERT INTO t_error_code (id, code, error_key, message, category) VALUES
(1, 'USER_001', 'USER_NOT_FOUND', '用户不存在', 'USER'),
(2, 'USER_002', 'USERNAME_EXISTS', '用户名已存在', 'USER'),
(3, 'USER_003', 'PASSWORD_INVALID', '密码不符合规范', 'USER'),
(4, 'USER_004', 'EMAIL_INVALID', '邮箱格式错误', 'USER'),
(5, 'USER_005', 'CREDENTIALS_INVALID', '用户名或密码错误', 'USER'),
(6, 'USER_006', 'ACCOUNT_DISABLED', '账户已禁用', 'USER'),
(7, 'USER_007', 'EMAIL_EXISTS', '邮箱已被使用', 'USER'),
(8, 'USER_008', 'OLD_PASSWORD_ERROR', '旧密码错误', 'USER'),
(9, 'USER_009', 'INVALID_STATUS', '无效的用户状态', 'USER'),
(10, 'AUTH_001', 'TOKEN_EXPIRED', 'Token已过期', 'AUTH'),
(11, 'AUTH_002', 'TOKEN_INVALID', '无效Token', 'AUTH'),
(12, 'AUTH_003', 'ACCESS_DENIED', '权限不足', 'AUTH'),
(13, 'AUTH_004', 'TOKEN_FORMAT_ERROR', 'Token格式错误', 'AUTH'),
(14, 'AUTH_005', 'TOKEN_REFRESH_FAILED', 'Token刷新失败', 'AUTH'),
(15, 'SYS_001', 'INTERNAL_ERROR', '系统内部错误', 'SYS'),
(16, 'SYS_002', 'VALIDATION_FAILED', '参数校验失败', 'SYS'),
(17, 'SYS_003', 'DB_OPERATION_FAILED', '数据库操作失败', 'SYS');