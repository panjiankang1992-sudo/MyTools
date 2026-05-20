# WebDAV 信息维护 — 设计规格

## 1. 概述

在"个人信息维护"页面新增 **WebDAV 信息维护** 区块，允许用户配置个人 WebDAV 账号。每用户维护一套 WebDAV 信息，独立存储。

## 2. 数据模型

### 2.1 数据库表 `webdav_account`

```sql
CREATE TABLE webdav_account (
    id           BIGINT        NOT NULL  AUTO_INCREMENT  PRIMARY KEY,
    user_id      BIGINT        NOT NULL  UNIQUE,
    type         VARCHAR(32)   NOT NULL  DEFAULT 'jianguoyun',
    url          VARCHAR(512)  NOT NULL,
    username     VARCHAR(128)  NOT NULL,
    password     VARCHAR(256)  NOT NULL,
    is_active    TINYINT       NOT NULL  DEFAULT 1,
    create_time  DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.2 支持的服务类型

| type 值 | 名称 | 说明 |
|---------|------|------|
| `jianguoyun` | 坚果云 | 默认值 |
| `nextcloud` | Nextcloud | |
| `owncloud` | ownCloud | |
| `synology` | 群晖/NAS | |
| `alist` | Alist | |
| `s3` | S3/WebDAV网关 | |
| `custom` | 自定义 | |

### 2.3 安全

- 密码字段使用 **AES-128/256** 加密后存储，密钥从 `application.yml` 或环境变量读取
- 实体类中密码字段标注 `@JsonIgnore`，API 响应不返回明文密码

## 3. 后端

### 3.1 新增文件

| 文件 | 说明 |
|------|------|
| `webdav/model/WebdavAccount.java` | 实体类 |
| `webdav/mapper/WebdavAccountMapper.java` | MyBatis Mapper XML |
| `webdav/service/WebdavAccountService.java` | 服务接口 |
| `webdav/service/impl/WebdavAccountServiceImpl.java` | 服务实现 |
| `webdav/controller/WebdavAccountController.java` | REST 控制器 |
| `webdav/model/WebdavAccountResponse.java` | 响应 DTO |
| `webdav/model/UpdateWebdavAccountRequest.java` | 请求 DTO |
| `utils/AesUtil.java` | AES 加解密工具 |

### 3.2 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/user/webdav` | 获取当前用户的 WebDAV 配置 |
| PUT | `/api/user/webdav` | 创建或更新 WebDAV 配置 |

**GET 响应示例：**
```json
{
  "code": 0,
  "data": {
    "id": 1,
    "userId": 100,
    "type": "jianguoyun",
    "url": "https://dav.jianguoyun.com/dav/",
    "username": "example@email.com",
    "passwordSet": true
  }
}
```
> `passwordSet` 为 true 表示已设置密码（明文不返回），前端据此决定显示"已设置"还是"未设置"。

**PUT 请求示例：**
```json
{
  "type": "jianguoyun",
  "url": "https://dav.jianguoyun.com/dav/",
  "username": "example@email.com",
  "password": "my-secret-password"
}
```
> `password` 为空字符串时，视为不修改密码；非空时更新密码。

### 3.3 校验规则

| 字段 | 规则 |
|------|------|
| `type` | 必须在支持类型列表中 |
| `url` | 必填，最大 512 字符 |
| `username` | 必填，最大 128 字符 |
| `password` | 可选，最大 128 字符（加密后存储） |

## 4. 前端

### 4.1 页面变更

**文件：** `webapp/src/views/profile/index.vue`

在个人信息表单下方新增 WebDAV 信息区块，作为独立 Card：

```
┌──────────────────────────────────────────────┐
│ WebDAV 信息维护                      [编辑]    │
├──────────────────────────────────────────────┤
│ 类型    [坚果云              ▼]              │
│ 地址    [https://dav.jianguoyun.com/dav/]   │
│ 用户名  [example@email.com        ]          │
│ 密码    [••••••••••] [👁]                    │
│                              [保存] [取消]    │
└──────────────────────────────────────────────┘
```

- 类型：`n-select` 下拉选择 7 种服务
- 地址：`n-input` 文本输入
- 用户名：`n-input` 文本输入
- 密码：`n-input` 密码模式 + 显示/隐藏切换
- 编辑模式下才可编辑，保存后调用 `PUT /api/user/webdav`

### 4.2 新增 API 函数

**文件：** `webapp/src/service/api/user.ts`

```typescript
// 获取 WebDAV 配置
export const fetchWebdavAccount = () =>
  request<Api.User.WebdavAccountResponse>('get', '/api/user/webdav');

// 更新 WebDAV 配置
export const updateWebdavAccount = (data: Api.User.UpdateWebdavAccountRequest) =>
  request<Api.User.WebdavAccountResponse>('put', '/api/user/webdav', data);
```

### 4.3 类型定义

**文件：** `webapp/src/typings/api/user-role.d.ts`

```typescript
namespace Api.User {
  interface WebdavAccountResponse {
    id: string;
    userId: string;
    type: string;
    url: string;
    username: string;
    passwordSet: boolean;
  }

  interface UpdateWebdavAccountRequest {
    type: string;
    url: string;
    username: string;
    password?: string;
  }
}
```

### 4.4 国际化

**文件：** `webapp/src/locales/langs/zh-cn.ts`

新增 `webdavAccount` 节点，包含所有字段和操作按钮的文案。

## 5. 实现步骤（概要）

1. 新增数据库表 `webdav_account`
2. 新增后端实体、Mapper、Service、Controller
3. 实现 AES 加解密工具
4. 新增前端 API 函数和类型定义
5. 在个人信息页面嵌入 WebDAV 信息维护区块
6. 添加国际化文案
