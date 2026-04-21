# User Management API Contracts

**Version**: 1.0.0 | **Date**: 2026-04-21

## Overview

所有接口遵循 RESTful 风格，JSON 格式请求/响应。认证通过 JWT Bearer Token。

**Base URL**: `/api`

**认证头**: `Authorization: Bearer <token>`

---

## 通用响应格式

### 成功响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { ... }
}
```

### 错误响应

```json
{
  "code": 40001,
  "message": "用户名已存在",
  "data": null
}
```

### 分页响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "list": [ ... ],
    "total": 100,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## 错误码定义

| 错误码 | Key | 说明 |
|--------|-----|------|
| 200 | SUCCESS | 成功 |
| 40001 | USER_001 | 用户不存在 |
| 40002 | USER_002 | 用户名已存在 |
| 40003 | USER_003 | 密码不符合规范 |
| 40004 | USER_004 | 邮箱格式错误 |
| 40005 | USER_005 | 用户名或密码错误 |
| 40006 | USER_006 | 账户已禁用 |
| 40007 | USER_007 | 邮箱已被使用 |
| 40008 | USER_008 | 旧密码错误 |
| 40009 | USER_009 | 无效的用户状态 |
| 50001 | AUTH_001 | Token已过期 |
| 50002 | AUTH_002 | 无效Token |
| 50003 | AUTH_003 | 权限不足 |
| 50004 | AUTH_004 | Token格式错误 |
| 50005 | AUTH_005 | Token刷新失败 |
| 60001 | SYS_001 | 系统内部错误 |
| 60002 | SYS_002 | 参数校验失败 |
| 60003 | SYS_003 | 数据库操作失败 |

---

## API Endpoints

### 认证接口

#### POST /api/auth/register - 用户注册

**Request**:
```json
{
  "username": "string (3-20字符，字母数字下划线)",
  "password": "string (至少8位，大小写字母和数字)",
  "email": "string (有效邮箱格式)",
  "phone": "string (可选，手机号格式)"
}
```

**Response** (201 Created):
```json
{
  "code": 201,
  "message": "注册成功",
  "data": {
    "userId": 1234567890123456789,
    "username": "testuser",
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 86400
  }
}
```

**错误码**: USER_002, USER_003, USER_004

---

#### POST /api/auth/login - 用户登录

**Request**:
```json
{
  "account": "string (用户名或邮箱)",
  "password": "string"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "userId": 1234567890123456789,
    "username": "testuser",
    "role": "USER",
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 86400
  }
}
```

**错误码**: USER_005, USER_006

---

#### POST /api/auth/refresh - 刷新Token

**Headers**: `Authorization: Bearer <token>`

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "刷新成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 86400
  }
}
```

**错误码**: AUTH_001, AUTH_002

---

### 用户接口（需认证）

#### GET /api/user/info - 获取当前用户信息

**Headers**: `Authorization: Bearer <token>`

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "userId": 1234567890123456789,
    "username": "testuser",
    "email": "test@example.com",
    "phone": "13800138000",
    "role": "USER",
    "status": "ACTIVE",
    "registerTime": "2026-04-21T10:00:00Z",
    "lastLoginTime": "2026-04-21T12:00:00Z"
  }
}
```

**错误码**: AUTH_002

---

#### PUT /api/user/info - 更新用户信息

**Headers**: `Authorization: Bearer <token>`

**Request**:
```json
{
  "email": "string (可选，有效邮箱格式)",
  "phone": "string (可选，手机号格式)"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "更新成功",
  "data": {
    "userId": 1234567890123456789,
    "username": "testuser",
    "email": "newemail@example.com",
    "phone": "13800138000",
    "role": "USER",
    "status": "ACTIVE"
  }
}
```

**错误码**: AUTH_002, USER_007, SYS_002

---

#### PUT /api/user/password - 修改密码

**Headers**: `Authorization: Bearer <token>`

**Request**:
```json
{
  "oldPassword": "string",
  "newPassword": "string (至少8位，大小写字母和数字)"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "密码修改成功",
  "data": null
}
```

**错误码**: AUTH_002, USER_003, USER_008

---

### 管理员接口（需ADMIN角色）

#### PUT /api/user/{id}/status - 禁用/启用用户

**Headers**: `Authorization: Bearer <token>` (需ADMIN角色)

**Path Parameters**: `id` - 用户ID（雪花算法生成）

**Request**:
```json
{
  "status": "ACTIVE | DISABLED"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "userId": 1234567890123456789,
    "status": "DISABLED"
  }
}
```

**错误码**: AUTH_002, AUTH_003, USER_001, USER_009

---

#### DELETE /api/user/{id} - 删除用户

**Headers**: `Authorization: Bearer <token>` (需ADMIN角色)

**Path Parameters**: `id` - 用户ID

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "删除成功",
  "data": null
}
```

**错误码**: AUTH_002, AUTH_003, USER_001

---

## 数据类型定义

### UserStatus
- `ACTIVE` - 正常
- `DISABLED` - 已禁用

### Role
- `USER` - 普通用户
- `ADMIN` - 管理员

### Token payload 结构
```json
{
  "sub": "1234567890123456789",
  "username": "testuser",
  "role": "USER",
  "iat": 1713683200,
  "exp": 1713769600
}
```
