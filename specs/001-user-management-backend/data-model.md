# Data Model: User Management Backend

**Version**: 1.0.0 | **Date**: 2026-04-21

## Database Configuration

**Primary Database**: `my_tools`
**Secondary Database**: `sales_order` (read-only reference, for future integration)

---

## Entity: User (用户)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, NOT NULL, UNIQUE | 雪花算法生成的全局唯一ID |
| username | VARCHAR(20) | NOT NULL, UNIQUE, INDEX | 用户名，3-20字符，字母数字下划线 |
| password | VARCHAR(255) | NOT NULL | BCrypt加密后的密码 |
| email | VARCHAR(100) | UNIQUE, INDEX | 邮箱 |
| phone | VARCHAR(20) | NULL | 手机号 |
| role | VARCHAR(20) | NOT NULL, DEFAULT 'USER' | 角色：ADMIN / USER |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'ACTIVE' | 状态：ACTIVE / DISABLED |
| register_time | DATETIME | NOT NULL | 注册时间 |
| last_login_time | DATETIME | NULL | 最后登录时间 |
| create_time | DATETIME | NOT NULL | 创建时间 |
| update_time | DATETIME | NOT NULL | 更新时间 |

**Indexes**:
- `uk_username` on `username`
- `uk_email` on `email`
- `idx_status` on `status`

**State Transitions**:
- `ACTIVE` → `DISABLED` (禁用用户)
- `DISABLED` → `ACTIVE` (启用用户)

---

## Entity: Role (角色)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, NOT NULL | 雪花算法生成的角色ID |
| role_name | VARCHAR(50) | NOT NULL, UNIQUE | 角色名称：ADMIN / USER |
| description | VARCHAR(255) | NULL | 角色描述 |
| create_time | DATETIME | NOT NULL | 创建时间 |

---

## Entity: UserRole (用户角色关联)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, NOT NULL | 雪花算法生成的ID |
| user_id | BIGINT | NOT NULL, FK | 关联用户ID |
| role_id | BIGINT | NOT NULL, FK | 关联角色ID |
| create_time | DATETIME | NOT NULL | 创建时间 |

**Constraints**:
- UNIQUE(user_id, role_id) - 同一用户同一角色唯一

---

## Entity: Token (认证令牌)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, NOT NULL | 雪花算法生成的ID |
| user_id | BIGINT | NOT NULL, FK, INDEX | 关联用户ID |
| token | VARCHAR(512) | NOT NULL, UNIQUE | JWT Token |
| device_info | VARCHAR(255) | NULL | 设备信息 |
| expires_at | DATETIME | NOT NULL | 过期时间 |
| create_time | DATETIME | NOT NULL | 创建时间 |

**Indexes**:
- `uk_token` on `token`
- `idx_user_id` on `user_id`
- `idx_expires_at` on `expires_at`

**Note**: Token 表用于 Token 黑名单和失效管理（删除/禁用用户时标记 Token 失效）。

---

## Entity: ErrorCode (错误码)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, NOT NULL | 雪花算法生成的ID |
| code | VARCHAR(20) | NOT NULL, UNIQUE | 错误码编号，如 USER_001 |
| error_key | VARCHAR(50) | NOT NULL | 错误码Key，如 USER_001 |
| message | VARCHAR(255) | NOT NULL | 错误描述（中文） |
| category | VARCHAR(20) | NOT NULL | 分类：USER / AUTH / SYS |
| create_time | DATETIME | NOT NULL | 创建时间 |

---

## Table Creation SQL (my_tools database)

```sql
CREATE DATABASE IF NOT EXISTS my_tools DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE my_tools;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT PRIMARY KEY COMMENT '用户ID（雪花算法）',
    username VARCHAR(20) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    email VARCHAR(100) UNIQUE COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    role VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    register_time DATETIME NOT NULL COMMENT '注册时间',
    last_login_time DATETIME COMMENT '最后登录时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 角色表
CREATE TABLE IF NOT EXISTS t_role (
    id BIGINT PRIMARY KEY COMMENT '角色ID',
    role_name VARCHAR(50) NOT NULL UNIQUE COMMENT '角色名称',
    description VARCHAR(255) COMMENT '角色描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS t_user_role (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_role (user_id, role_id),
    INDEX idx_user_id (user_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- Token表
CREATE TABLE IF NOT EXISTS t_token (
    id BIGINT PRIMARY KEY COMMENT 'Token记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    token VARCHAR(512) NOT NULL UNIQUE COMMENT 'JWT Token',
    device_info VARCHAR(255) COMMENT '设备信息',
    expires_at DATETIME NOT NULL COMMENT '过期时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_token (token(255)),
    INDEX idx_user_id (user_id),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token表';

-- 错误码表
CREATE TABLE IF NOT EXISTS t_error_code (
    id BIGINT PRIMARY KEY COMMENT '错误码ID',
    code VARCHAR(20) NOT NULL UNIQUE COMMENT '错误码编号',
    error_key VARCHAR(50) NOT NULL COMMENT '错误码Key',
    message VARCHAR(255) NOT NULL COMMENT '错误描述',
    category VARCHAR(20) NOT NULL COMMENT '分类',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='错误码表';

-- 初始化默认角色
INSERT INTO t_role (id, role_name, description) VALUES
(1, 'ADMIN', '管理员'),
(2, 'USER', '普通用户')
ON DUPLICATE KEY UPDATE description = VALUES(description);

-- 初始化错误码
INSERT INTO t_error_code (id, code, error_key, message, category) VALUES
-- 用户错误码
(1, 'USER_001', 'USER_NOT_FOUND', '用户不存在', 'USER'),
(2, 'USER_002', 'USERNAME_EXISTS', '用户名已存在', 'USER'),
(3, 'USER_003', 'PASSWORD_INVALID', '密码不符合规范', 'USER'),
(4, 'USER_004', 'EMAIL_INVALID', '邮箱格式错误', 'USER'),
(5, 'USER_005', 'CREDENTIALS_INVALID', '用户名或密码错误', 'USER'),
(6, 'USER_006', 'ACCOUNT_DISABLED', '账户已禁用', 'USER'),
(7, 'USER_007', 'EMAIL_EXISTS', '邮箱已被使用', 'USER'),
(8, 'USER_008', 'OLD_PASSWORD_ERROR', '旧密码错误', 'USER'),
(9, 'USER_009', 'INVALID_STATUS', '无效的用户状态', 'USER'),
-- 认证错误码
(10, 'AUTH_001', 'TOKEN_EXPIRED', 'Token已过期', 'AUTH'),
(11, 'AUTH_002', 'TOKEN_INVALID', '无效Token', 'AUTH'),
(12, 'AUTH_003', 'ACCESS_DENIED', '权限不足', 'AUTH'),
(13, 'AUTH_004', 'TOKEN_FORMAT_ERROR', 'Token格式错误', 'AUTH'),
(14, 'AUTH_005', 'TOKEN_REFRESH_FAILED', 'Token刷新失败', 'AUTH'),
-- 系统错误码
(15, 'SYS_001', 'INTERNAL_ERROR', '系统内部错误', 'SYS'),
(16, 'SYS_002', 'VALIDATION_FAILED', '参数校验失败', 'SYS'),
(17, 'SYS_003', 'DB_OPERATION_FAILED', '数据库操作失败', 'SYS')
ON DUPLICATE KEY UPDATE message = VALUES(message);
```

---

## Relationships

```
User (1) ---> (N) UserRole (N) ---> (1) Role
User (1) ---> (N) Token
```

---

## Validation Rules

| Entity | Field | Rule |
|--------|-------|------|
| User | username | 3-20字符，字母、数字、下划线，不能以数字开头 |
| User | password | 至少8位，必须包含大小写字母和数字 |
| User | email | 有效邮箱格式（RFC 5322） |
| User | phone | 可选，11位数字，以1开头 |
| Token | token | JWT格式，有效期默认24小时 |
