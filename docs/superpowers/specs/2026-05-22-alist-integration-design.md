# Alist 集成设计方案

> **Goal:** 在"云端文件"下新增独立的 Alist 子页面，实现 Alist 存储的文件浏览和预览能力。

## 背景

Alist 是一个支持多种存储后端的文件列表程序（如 OneDrive、Google Drive、S3、阿里云盘等）。用户配置 Alist 服务器的地址和账号后，应能在工具箱中直接浏览 Alist 挂载的存储文件，并预览图片、文本、Markdown 等类型文件。

## 架构

### 后端：AlistClient

新增 `AlistClient`，与现有 `WebdavClient` 同级，各自实现独立的 HTTP 通信逻辑。`CloudFileServiceImpl.buildClient()` 根据 `account.type` 分发：

```java
if ("alist".equals(account.getType())) {
    return new AlistClient(account.getUrl(), token);
} else {
    return new WebdavClient(...);
}
```

**认证流程：**

1. 首次创建/更新 Alist 账号时，后端用 `username` + SHA-256 哈希后的 `password` 调用 `POST /api/auth/login/hash`
2. 响应中的 `token` 字段存储到 `webdav_account.password` 字段（AES 加密，与 WebDAV 密码相同机制）
3. 后续请求在 Header 中传递 `Authorization: Bearer <token>`
4. 如果 API 返回 401，自动重新登录并刷新 Token，重试原请求（类似 Token 刷新机制）

**文件 API：**

| 目的 | 方法 | 请求体 |
|------|------|--------|
| 列出目录 | `POST /api/fs/list` | `{ "path": "/xxx", "password": "" }` |
| 获取直链/元数据 | `POST /api/fs/get` | `{ "path": "/xxx/file.txt", "password": "" }` |

`/api/fs/list` 响应中的 `content[]` 直接映射到 `CloudFileItem`：

```java
// Alist -> CloudFileItem 映射
item.is_dir      -> CloudFileItem.isDirectory
item.name        -> CloudFileItem.name
item.size        -> CloudFileItem.size
item.modified     -> CloudFileItem.lastModified
// 以下字段 Alist 不返回，设为 null
item.thumb        -> 暂不使用（直链预览已够用）
// contentType, etag -> null
```

`/api/fs/get` 响应中的 `raw_url` 字段用于文件预览：
- 图片文件：直接将 `raw_url` 设为 `<img src>` 或 `<n-image>`
- 文本/Markdown 文件：将 `raw_url` 设为 `<iframe src>` 加载

### 前端：新增 Alist 页面

```
views/alist/
  index.vue       ← 新页面（基于现有 cloud-file/browse 简化）
```

**路由：**
- `src/router/elegant/routes.ts` 中新增 `alist` 路由（父级 `cloud-file` 下新增 `alist` 子路由，或同级）
- 菜单项：名称"Alist"，图标 `mdi:cloud-outline`，放在"云端文件"分组内

**账号选择：** 调用 `fetchWebdavAccounts()` 后过滤 `a.type === 'alist'` 的账号，其他逻辑与现有 browse 页面相同。

**预览实现：**
- 图片：`POST /api/fs/get` 获取 `raw_url`，用 `<n-image>` 渲染
- 文本/Markdown：`POST /api/fs/get` 获取 `raw_url`，用 `<iframe>` 加载（部分 Alist 存储支持直链下载）

## 数据模型

### Alist API 响应

**`POST /api/auth/login/hash` 请求：**
```json
{
  "username": "admin",
  "password": "<SHA-256_hex_of_plain_password>"
}
```

**`POST /api/auth/login/hash` 响应：**
```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGc...",
    "device_key": "..."
  }
}
```

**`POST /api/fs/list` 请求：**
```json
{
  "path": "/",
  "password": ""
}
```

**`POST /api/fs/list` 响应：**
```json
{
  "code": 200,
  "data": {
    "content": [
      {
        "name": "Documents",
        "size": 0,
        "is_dir": true,
        "modified": "2024-03-15T10:30:00Z",
        "created": "2024-03-15T10:00:00Z",
        "type": 1
      },
      {
        "name": "report.pdf",
        "size": 204800,
        "is_dir": false,
        "modified": "2024-03-15T10:30:00Z",
        "created": "2024-03-15T10:00:00Z",
        "type": 4,
        "thumb": "..."
      }
    ],
    "total": 2,
    "provider": "OneDrive"
  }
}
```

**`POST /api/fs/get` 响应：**
```json
{
  "code": 200,
  "data": {
    "name": "readme.md",
    "size": 1234,
    "is_dir": false,
    "raw_url": "https://...",
    "sign": ""
  }
}
```

## 实现范围（V1）

- [x] 后端：AlistClient（认证 + 文件列表 + 获取直链）
- [x] 后端：CloudFileServiceImpl 支持 Alist 类型账号
- [x] 前端：Alist 路由 + 菜单
- [x] 前端：Alist 页面（账号选择 + 树形目录 + 文件列表）
- [x] 前端：文件预览（图片 + 文本/Markdown）

**不在 V1 范围内：**
- 文件上传、删除、重命名等管理操作
- 密码保护目录（`password` 字段传空）
- 多存储路径选择（展示 Alist 根目录下的所有挂载存储）
