# 应用市场 (App Market) 设计文档

## 概述

内部团队工具共享平台，支持上架、浏览、下载团队内部开发的 CLI 工具、MCP 服务器、Claude Skill 等应用。所有登录用户均可上架，编辑/删除仅限管理员或所有者。

---

## 一、功能范围

### 1.1 应用类型

| 类型 | 内容简介 | 下载文件格式 |
|------|---------|------------|
| app | 文本介绍(TEXT) | HTML富文本文件 |
| cli | 文本介绍 | 可执行二进制文件 |
| mcp | 文本介绍 | JSON配置文件 |
| skill | 文本介绍 | ZIP压缩包 |

### 1.2 核心功能

- **应用列表** — 支持按类型、名称搜索，支持"查看历史版本"开关
- **上架** — 侧滑页表单，支持缩略图上传、按类型上传对应文件、简介富文本编辑
- **详情** — 侧滑页展示，包含内容预览、版本信息、操作按钮
- **编辑** — 自动保存历史版本，支持查看历史
- **删除** — 管理员或应用所有者可删除
- **下架** — 管理员或应用所有者可下架(软删除)
- **下载** — 所有登录用户可下载

---

## 二、数据库设计

### 2.1 应用主表 `t_app_market`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 主键(Snowflake) |
| user_id | Long | 发布人用户ID |
| name | String | 应用名称 |
| type | String | app/cli/mcp/skill |
| version | String | 当前版本号 |
| thumbnail_id | String | 缩略图文件ID |
| content | Text | 应用简介(富文本HTML) |
| install_cmd | String | 安装命令(可选) |
| download_url | String | 外部下载链接(可选) |
| status | String | PUBLISHED/DRAFT |
| created_time | DateTime | 创建时间 |
| update_time | DateTime | 更新时间 |

### 2.2 历史版本表 `t_app_version`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 主键 |
| app_id | String | 所属应用ID |
| version | String | 版本号 |
| content | Text | 该版本的简介 |
| file_id | String | 该版本的文件ID |
| created_time | DateTime | 发布时间 |

### 2.3 应用文件表 `t_app_file`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 主键 |
| app_id | String | 所属应用ID |
| version_id | String | 所属版本ID(可为null表示当前版本) |
| file_type | String | thumbnail/binary/json/zip/html |
| file_name | String | 文件名 |
| file_path | String | 存储路径 |
| file_size | Long | 文件大小(字节) |
| created_time | DateTime | 上传时间 |

---

## 三、后端接口

### 3.1 应用管理

```
GET    /api/market/apps          -- 列表(分页,支持type/name/history过滤)
GET    /api/market/apps/:id     -- 详情(含当前版本内容)
POST   /api/market/apps         -- 上架新应用
PUT    /api/market/apps/:id     -- 编辑(自动保存历史版本)
DELETE /api/market/apps/:id     -- 删除(含文件清理)
```

### 3.2 文件管理

```
POST   /api/market/apps/:id/files           -- 上传文件(缩略图/内容文件)
GET    /api/market/apps/:id/files/:fileId  -- 下载文件
DELETE /api/market/apps/:id/files/:fileId  -- 删除文件
```

### 3.3 历史版本

```
GET    /api/market/apps/:id/versions       -- 历史版本列表
GET    /api/market/apps/:id/versions/:vid  -- 某版本详情
```

---

## 四、前端设计

### 4.1 菜单结构

```
应用市场
├── app-market       (应用列表页)
```

路由: `/app-market`
大菜单图标: `mdi:store` 或 `mdi:application-cog`
子菜单无独立图标,共用父级

### 4.2 列表页 `app-market/index.vue`

```
┌─────────────────────────────────────────────────┐
│ 搜索栏                                             │
│ [类型▼] [名称输入框] [☑历史版本] [搜索]   [上架] │
├─────────────────────────────────────────────────┤
│ 编号 │ 类型 │ 缩略图 │ 名称 │ 版本 │ ... │ 操作 │
│  1   │ app  │  [图]  │ XX   │ v1.0 │     │下载 │
│  2   │ cli  │  [图]  │ YY   │ v2.1 │     │下载 │
└─────────────────────────────────────────────────┘
```

列表字段: 编号、类型、缩略图、应用名称、版本、简介(截断)、上架时间、操作(下载/编辑/删除)

"查看历史版本"开关打开时,每行展开显示历史版本列表

### 4.3 侧滑组件 `AppMarketDrawer.vue`

共用组件,两种模式通过 `mode` prop 区分:

```typescript
mode: 'detail' | 'publish'
```

**detail模式** — 点击列表项打开
- 展示: 缩略图、名称、类型、版本、发布人、简介(富文本渲染)、上架时间
- 操作按钮: 下载(所有人)、编辑/删除(管理员或所有者)

**publish模式** — 点击"上架"按钮打开
- 新增/编辑表单
- 缩略图上传
- 按类型显示不同内容上传控件:
  - app: 富文本编辑器(Tiptap)
  - cli: 二进制文件上传
  - mcp: JSON文件上传
  - skill: ZIP压缩包上传

### 4.4 富文本编辑器

使用项目现有的 Tiptap 编辑器,参考 `webapp/src/views/localfile/` 下的使用方式。

---

## 五、权限矩阵

| 操作 | 管理员 | 应用所有者 | 其他登录用户 |
|------|--------|-----------|------------|
| 浏览列表 | ✅ | ✅ | ✅ |
| 下载 | ✅ | ✅ | ✅ |
| 上架 | ✅ | ✅ | ✅ |
| 编辑 | ✅ | ✅ | ❌ |
| 删除 | ✅ | ✅ | ❌ |
| 下架 | ✅ | ✅ | ❌ |

权限判断: 管理员 = `role === 'ADMIN'`, 所有者 = `userId === app.userId`

---

## 六、技术要点

### 6.1 文件存储

应用文件存储在服务器本地目录: `/opt/yuyutian/MyTools/app-market-files/`
- 子目录结构: `{appId}/{fileType}/{filename}`
- 文件名使用UUID避免冲突

### 6.2 历史版本

每次调用编辑接口(`PUT`)时:
1. 将当前内容保存到 `t_app_version`
2. 更新 `t_app_market` 的当前内容
3. 新文件上传到 `t_app_file`,关联到新版本

### 6.3 下载逻辑

根据应用类型从 `t_app_file` 读取文件路径,返回文件流。
对于 app 类型,可选: 直接将 `content` 字段导出为 HTML 文件下载。

### 6.4 前端路由

使用 Elegant Router,新增:
- `webapp/src/views/app-market/index.vue` (列表页)

路由自动生成: `/app-market`

### 6.5 ID类型

所有Snowflake ID在前端使用 `string` 类型,禁止使用 `number`。

---

## 七、实现顺序

1. 后端: 数据库表 + 文件上传下载接口
2. 后端: 应用CRUD接口
3. 后端: 历史版本接口
4. 前端: 菜单配置
5. 前端: 列表页
6. 前端: 侧滑详情
7. 前端: 侧滑上架
8. 联调测试
