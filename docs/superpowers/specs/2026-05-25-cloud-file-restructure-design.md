# 云端文件页面重构设计

## 目标

将"云端文件"从三个子页面（坚果云 + WebDAV 管理 + Alist）简化为两个子页面（WebDAV + Alist），每个页面内嵌各自的账号管理能力。

## 当前结构

```
云端文件 (cloud-file)
├── 坚果云 (cloud-file_browse) — WebDAV 文件浏览 + 账号选择器 + 跳转管理页按钮
├── WebDAV 管理 (cloud-file_accounts) — 独立账号 CRUD 页面
└── Alist (cloud-file_alist) — 只读文件浏览 + 账号选择器
```

问题：
1. WebDAV 管理是独立页面，管理账号时看不到文件列表
2. Alist 页面没有账号管理，需要跳转到 WebDAV 管理页
3. 三个页面中"坚果云"命名不准确（实际支持所有 WebDAV 类型）
4. 后端 `listFiles()` 对 Alist 账号直接报错（`AlistClient.list()` 已实现但未接入）

## 目标结构

```
云端文件 (cloud-file)
├── WebDAV (cloud-file_browse) — 文件浏览 + 内嵌账号管理抽屉
└── Alist (cloud-file_alist) — 文件浏览 + 内嵌账号管理抽屉
```

变更：
- 删除 `cloud-file_accounts` 路由和页面
- "坚果云" → "WebDAV"（改 i18n）
- 两个页面各自内嵌账号管理（NDrawer 抽屉）

## 内嵌账号管理交互

### 触发方式

账号选择器旁的"管理"按钮，点击后右侧展开 `NDrawer`（宽度 480px）。

### 抽屉内容

- 标题："WebDAV 账号管理" 或 "Alist 账号管理"
- 账号列表（NDataTable）：名称、类型（仅 WebDAV 页面显示）、地址、用户名、是否默认、操作
- 添加账号按钮 → 弹出 NModal 表单
- 编辑、删除、设为默认操作
- WebDAV 页面显示所有非 Alist 类型账号；Alist 页面只显示 Alist 类型账号

### 抽屉 vs 弹窗

选择抽屉因为用户可能需要一边浏览文件一边管理账号，抽屉不会完全遮挡文件内容。

### 账号操作后的行为

- 创建/编辑/删除账号后，刷新账号选择器的选项列表
- 如果删除的是当前选中账号，自动切换到默认账号或第一个账号
- 如果新创建的账号被设为默认，选择器自动选中它

## 账号表单字段

### WebDAV 页面的表单

过滤类型：jianguoyun / nextcloud / owncloud / synology / s3 / custom（排除 alist）

字段顺序：服务类型 → 账号名称 → WebDAV 地址 → 用户名 → 密码 → 设为默认

### Alist 页面的表单

类型固定为 alist（隐藏类型选择器），表单简化为：

字段顺序：账号名称 → Alist 地址 → 用户名 → 密码 → 设为默认

其中"密码"字段说明改为"Alist API 密码"。

## 后端修复

### CloudFileServiceImpl 路由 Alist 账号

当前 `listFiles()` 和 `getFileContent()` / `downloadFile()` 始终调用 `buildClient()`，对 Alist 账号直接抛异常。需要：

1. `listFiles()` 检测账号类型，Alist 走 `buildAlistClient().list()`，其他走 `buildClient()`
2. `getFileContent()` 和 `downloadFile()` 对 Alist 账号，通过 `buildAlistClient().getRawUrl()` 获取直链后重定向/下载

其他写操作（upload、delete、rename、move、copy、mkdir、saveTextFile）保持拒绝 Alist 账号不变（Alist 只读）。

### AlistClient 方法签名

`AlistClient.list(path)` 返回 `CloudFileListResponse`，已在当前代码中实现。`AlistClient.getRawUrl(path)` 返回直链 URL，也已实现。无需新增后端方法。

## 前端文件变更

### 删除

- `webapp/src/views/cloud-file/accounts/index.vue`

### 修改

- `webapp/src/views/cloud-file/browse/index.vue` — 添加账号管理抽屉，移除跳转按钮
- `webapp/src/views/cloud-file/alist/index.vue` — 添加账号管理抽屉
- `webapp/src/locales/langs/zh-cn.ts` — `cloud-file_browse: '坚果云'` → `'WebDAV'`，删除 `cloud-file_accounts`
- `webapp/src/locales/langs/en-us.ts` — 同上
- `webapp/src/router/elegant/routes.ts` — 删除 `cloud-file_accounts` 子路由
- `webapp/src/router/elegant/imports.ts` — 删除 accounts 的 import
- `webapp/src/router/elegant/transform.ts` — 删除 RouteMap 中的 `cloud-file_accounts`
- `webapp/src/typings/elegant-router.d.ts` — 删除 `cloud-file_accounts` 相关类型
- `webapp/src/service/api/cloudfile.ts` — `fetchAlistFiles` 和 `fetchAlistRawUrl` 可能需要调整（如果后端路由修复后端点不变则无需调整）
- 后端 `CloudFileServiceImpl.java` — `listFiles()` 和文件获取方法添加 Alist 路由

### 不变

- `webapp/src/service/api/webdav.ts` — 账号 CRUD API 不变
- `webapp/src/service/api/alist.ts` — Alist API 不变
- `webapp/src/store/modules/cloudfile/` — store 不变
- `webapp/src/store/modules/alist/` — store 不变
- `webapp/src/typings/api/webdav.d.ts` — 类型不变
- `webapp/src/typings/api/alist.d.ts` — 类型不变

## 不做的事

- 不合并 cloudfile store 和 alist store（职责不同，Alist 只读 vs WebDAV 读写）
- 不给 Alist 添加文件写操作
- 不给 Alist 添加存储驱动管理等 Alist 管理端特有功能
- 不重构后端 AlistClient（现有 list + getRawUrl 足够）
