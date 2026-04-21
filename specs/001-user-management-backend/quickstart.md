# Quickstart: Spring Boot User Management Backend

**Date**: 2026-04-21 | **Feature**: 001-user-management-backend

## 环境要求

- Java 21+
- Maven 3.9+
- MySQL 8.x

## 1. 数据库初始化

连接到 MySQL 服务器（192.168.1.8:3306）并执行以下 SQL：

```sql
-- 创建数据库
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
(17, 'SYS_003', 'DB_OPERATION_FAILED', '数据库操作失败', 'SYS')
ON DUPLICATE KEY UPDATE message = VALUES(message);
```

## 2. 配置文件

创建或修改 `src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: mytools

  datasource:
    dynamic:
      primary: my_tools
      datasource:
        my_tools:
          url: jdbc:mysql://192.168.1.8:3306/my_tools?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=UTC
          username: root
          password: YUyutian/1015
          driver-class-name: com.mysql.cj.jdbc.Driver
          hikari:
            maximum-pool-size: 20
            minimum-idle: 5
        sales_order:
          url: jdbc:mysql://192.168.1.8:3306/sales_order?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=UTC
          username: root
          password: YUyutian/1015
          driver-class-name: com.mysql.cj.jdbc.Driver
          hikari:
            maximum-pool-size: 30
            minimum-idle: 10

  profiles:
    active: dev

mybatis:
  mapper-locations: classpath*:mapper/**/*.xml
  type-aliases-package: com.mytools.**.Model
  configuration:
    map-underscore-to-camel-case: true

jwt:
  secret: ${JWT_SECRET:dGhpcyBpcyBhIHZlcnkgc2VjcmV0IGtleSBmb3IgZGV2ZWxvcG1lbnQgb25seSE=}
  access-expiration-ms: 900000
  refresh-expiration-ms: 604800000

snowflake:
  datacenter-id: 1
  worker-id: 1
  enabled: true

password:
  salt: ${PASSWORD_SALT:your-secret-salt-value-here}

logging:
  path: logs
  max-size: 10MB
  max-history: 7
  total-size-cap: 100MB
```

## 3. 构建和运行

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包
mvn package -DskipTests

# 运行
java -jar target/mytools-1.0.0.jar
```

## 4. API 测试

### 注册用户
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"Test1234","email":"test@example.com"}'
```

### 用户登录
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"account":"testuser","password":"Test1234"}'
```

### 获取用户信息（需认证）
```bash
curl http://localhost:8080/api/user/info \
  -H "Authorization: Bearer <token>"
```

## 5. 日志查看

日志文件位于 `logs/` 目录：
- `application.log` — 主应用日志
- `sql.log` — SQL日志
- `access.log` — HTTP访问日志

日志轮转配置：10MB/文件，保留7天，最多10个文件。