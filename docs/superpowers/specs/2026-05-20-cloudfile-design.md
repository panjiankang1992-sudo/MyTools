# 云端文件浏览器 设计规格

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this spec.

**Goal:** 新增"云端文件"菜单，用户可通过 WebDAV 协议浏览、编辑、管理自己云盘中的文件。

**Architecture:** 后端代理模式——前端通过后端 API 访问云盘，后端持有解密后的 WebDAV 密码，对云盘执行所有文件操作。前端无需知道密码，支持跨域，无 CORS 问题。

**Tech Stack:** Spring Boot 3 + Vue3 + TypeScript + NaiveUI + Monaco Editor

---

## 1. 功能范围

### 1.1 目录浏览
- 左侧树形目录导航（可展开/折叠），初始加载根目录
- 右侧文件列表：图标、名称、大小、修改时间
- 顶部面包屑导航，点击跳转目录
- 工具栏：上传文件、新建文件夹、刷新

### 1.2 文件操作
| 操作 | 接口 | 说明 |
|------|------|------|
| 列表 | GET `/api/cloud/files` | 列出目录，含子目录结构 |
| 下载 | GET `/api/cloud/file` | 下载文件 |
| 上传 | POST `/api/cloud/file` | 上传文件到指定目录 |
| 新建目录 | POST `/api/cloud/dir` | 创建空文件夹 |
| 重命名 | POST `/api/cloud/rename` | 重命名文件/目录 |
| 删除 | DELETE `/api/cloud/file` | 删除文件或目录 |
| 移动 | POST `/api/cloud/move` | 移动文件/目录 |
| 复制 | POST `/api/cloud/copy` | 复制文件/目录 |
| 编辑 | GET/PUT `/api/cloud/file` | 文本文件读取/保存 |

### 1.3 文本编辑
- 双击 .txt/.md/.json/.html/.css/.js 等文本文件，用 Monaco Editor 打开
- 保存时自动 PUT 上传覆盖原文件
- 编辑前先获取最新内容（避免覆盖他人修改）

---

## 2. 后端设计

### 2.1 模块结构

```
src/main/java/com/yuyutian/mytools/cloudfile/
├── controller/
│   └── CloudFileController.java      # REST 接口
├── service/
│   ├── CloudFileService.java         # 接口
│   └── impl/
│       ├── CloudFileServiceImpl.java  # WebDAV 代理逻辑
│       └── WebdavClient.java         # WebDAV HTTP 客户端封装
├── model/
│   ├── CloudFileItem.java            # 文件/目录项 DTO
│   ├── CloudFileListResponse.java     # 列表响应
│   └── CloudFileOperationRequest.java # 操作请求
└── config/
    └── WebClientConfig.java          # HTTP 客户端配置（连接池、超时）
```

### 2.2 WebDAV 客户端

使用 Spring `WebClient`（Reactive HTTP Client）实现 WebDAV 协议：

| 方法 | WebDAV 方法 | 说明 |
|------|-------------|------|
| list(path) | PROPFIND + Depth:1 | 列出目录内容 |
| get(path) | GET | 下载文件 |
| put(path, content) | PUT | 上传/覆盖文件 |
| mkdir(path) | MKCOL | 创建目录 |
| delete(path) | DELETE | 删除 |
| move(from, to) | MOVE | 移动/重命名 |
| copy(from, to) | COPY | 复制 |

**超时配置：** 连接 10s，读取 60s（大文件按文件大小动态调整）

**认证：** Basic Auth（用户名 + 明文密码），密码从数据库取出后后端内存解密，不暴露给前端。

### 2.3 接口详细设计

#### GET `/api/cloud/files`
查询目录内容。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| path | string | 否 | 目录路径，默认 `/` |
| depth | integer | 否 | 递归深度，默认 1（用于目录树） |

**响应：**

```json
{
  "code": "0000",
  "data": {
    "path": "/docs",
    "items": [
      {
        "name": "readme.md",
        "path": "/docs/readme.md",
        "isDirectory": false,
        "size": 2048,
        "contentType": "text/markdown",
        "lastModified": "2026-05-10T08:30:00Z",
        "etag": "\"abc123\""
      },
      {
        "name": "images",
        "path": "/docs/images",
        "isDirectory": true,
        "size": 0,
        "lastModified": "2026-05-10T08:30:00Z"
      }
    ]
  }
}
```

#### GET `/api/cloud/file`
下载或预览文件。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| path | string | 是 | 文件路径 |
| preview | boolean | 否 | true=文本预览（直接返回文本），false=附件下载，默认 false |

**响应：**
- preview=true + 文本文件：`Content-Type: text/plain; charset=utf-8`，直接返回内容
- preview=true + 二进制文件：`{"code":"50001","message":"不支持预览该类型文件"}`
- preview=false：`Content-Disposition: attachment; filename="xxx"`，流式输出

#### POST `/api/cloud/file`
上传文件。

**请求：** `Content-Type: multipart/form-data`

| 字段 | 类型 | 说明 |
|------|------|------|
| file | binary | 文件内容 |
| path | string | 上传到的目录路径 |
| filename | string | 保存的文件名（可选，默认用原文件名） |

**响应：**

```json
{
  "code": "0000",
  "message": "上传成功",
  "data": {
    "name": "report.pdf",
    "path": "/docs/report.pdf",
    "size": 102400,
    "lastModified": "2026-05-20T10:00:00Z"
  }
}
```

#### POST `/api/cloud/dir`
创建目录。

**请求体：**

```json
{
  "path": "/docs/new-folder"
}
```

#### POST `/api/cloud/rename`
重命名文件或目录。

**请求体：**

```json
{
  "path": "/docs/readme.md",
  "newName": "readme-v2.md"
}
```

#### POST `/api/cloud/move`
移动或重命名。

**请求体：**

```json
{
  "from": "/docs/readme.md",
  "to": "/archive/readme.md"
}
```

#### POST `/api/cloud/copy`
复制文件或目录。

**请求体：**

```json
{
  "from": "/docs/readme.md",
  "to": "/backup/readme.md"
}
```

#### DELETE `/api/cloud/file`
删除文件或目录。

**请求参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| path | string | 要删除的路径 |
| recursive | boolean | 递归删除目录，默认 false |

---

## 3. 前端设计

### 3.1 路由

```
/cloud-file → views/cloudfile/index.vue
```

路由守卫：需要登录，未配置 WebDAV 时提示去"个人信息"页面配置。

### 3.2 页面布局

```
┌──────────────────────────────────────────────────────┐
│  面包屑: / docs images archive             [上传] [新建目录] [刷新] │
├────────────┬─────────────────────────────────────────┤
│            │  📄 readme.md     2KB   2026-05-10     │
│  📁 docs   │  📁 images        -     2026-05-10     │
│   └ 📁 images│  📄 notes.txt    512B   2026-05-09    │
│  📁 archive│  📄 data.json    1KB    2026-05-08     │
│            │                                          │
│            │                                          │
└────────────┴─────────────────────────────────────────┘
```

- **左侧目录树**：`NTree`，点击节点加载子目录，支持懒加载
- **右侧文件列表**：`NDataTable`，列：图标、名称、大小、修改时间、操作
- **工具栏**：上传按钮、新建文件夹按钮、刷新按钮
- **右键菜单**：打开、下载、重命名、复制、移动、删除
- **拖拽排序**：暂不实现

### 3.3 Monaco Editor 弹窗

- 双击文本文件打开
- 标题栏显示文件名和路径
- 底部状态栏：文件大小、编码、修改状态
- 保存按钮：PUT 上传，保存成功后关闭弹窗
- 取消按钮：未保存时提示"有未保存的更改，确认关闭？"

### 3.4 文件上传

- 工具栏"上传"按钮 → `NUpload` 组件
- 支持拖拽上传（整个列表区域支持拖拽）
- 上传进度条
- 上传失败重试

### 3.5 状态管理

```typescript
// webapp/src/store/modules/cloudfile/
interface CloudFileState {
  currentPath: string;       // 当前目录路径
  items: CloudFileItem[];    // 当前目录文件列表
  treeData: CloudTreeNode[]; // 目录树数据
  loading: boolean;
}
```

---

## 4. 错误处理

| 场景 | 后端返回 |
|------|---------|
| WebDAV 连接失败 | `{"code":"50001","message":"无法连接到云盘服务，请检查配置"}` |
| 路径不存在 | `{"code":"40401","message":"文件或目录不存在"}` |
| 权限不足 | `{"code":"40301","message":"无权限执行此操作"}` |
| 操作失败 | `{"code":"50001","message":"操作失败：<具体错误>"}` |
| 未配置 WebDAV | `{"code":"40001","message":"请先在个人信息中配置 WebDAV"}` |

前端：未配置 WebDAV 时显示引导页面（有"去配置"按钮跳转个人信息页）。

---

## 5. 安全设计

- WebDAV 密码仅在后端内存中解密，用于构造 Basic Auth 头，不日志记录
- 所有 `/api/cloud/**` 接口需要登录认证
- 用户只能操作本人的 WebDAV 云盘
- 文件操作超时保护：大文件上传超时动态计算（`size / 1024KB/s + 30s`，上限 5 分钟）

---

## 6. 前端依赖

| 包 | 用途 |
|----|------|
| `@monaco-editor/react` 或 `monaco-editor` | 代码编辑器 |
| `@vueuse/core` | useDraggable 等工具 |
| `axios` | 已有 |
| `naive-ui` | 已有 |

Monaco Editor 通过 `NModal` 弹窗集成。

---

## 7. 不包含在本版本

- 本地文件与云端同步
- 文件版本历史
- 共享链接生成
- 云盘配额显示
- 多选批量操作（后续版本）
