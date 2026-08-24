# Drive Service 详细设计

## 职责

负责网盘账户、用户授权、远端元数据索引、文件查询、操作编排和访问票据。底层存储操作统一委托 Storage Gateway。

## 数据模型

- `drive_accounts`、`drive_permissions`。
- `drive_item_index`、`drive_index_cursor`。
- `drive_operations`、`drive_task_bindings`。
- `drive_access_tickets`。

## 任务类型

- `drive_index_account`、`drive_refresh_directory`。
- `drive_copy_tree`、`drive_move_tree`、`drive_delete_tree`。
- `drive_sync_account`、`drive_reconcile_index`。

目录列表优先查询索引；强制刷新创建即时任务。小文件操作可同步，大文件和递归操作必须异步。

## DML

索引脚本可批量 upsert Drive 的索引暂存表，并以游标批次提交。账户配置、权限和访问票据必须走 API，禁止脚本直接读取存储凭据。

## 迁移

1. 已创建独立 `mytools_drive` schema 和 Drive Service MVP，覆盖 Secret 引用账户、权限、索引、可恢复游标、操作、任务绑定、短期票据及 Outbox；现有 `drive` 模块和 rclone 接口仍为主路径。
2. 将旧 WebDAV、Alist 账号迁移为统一账户。
3. 已提供按 run/batch ledger 幂等的索引批次 API和 `drive_index_account` 任务；任务通过仅限回环地址和 `operations/list` 的 connector 递归扫描，批次完成前不会删除旧索引，且脚本无法读取远端凭据或提交任意命令。
4. Gateway 已接入默认关闭的账户目录查询及索引刷新创建、状态查询和取消路由；Gateway 从可信主体注入 owner，Drive 内部接口继续执行账户所有权约束。文件复制、移动和删除仍保留在旧入口。
5. 已提供手工触发的旧账户迁移任务；MyTools 只导出非敏感元数据与 Secret 引用，旧 rclone 账户保持原启用状态，WebDAV/Alist 默认禁用并等待 provider 配置，不自动改变旧查询流量。
6. 新旧接口并行验证后切换 App。
7. 拆独立服务并删除兼容模块。

## 验收

- 索引任务可从游标恢复。
- 用户不能通过任务参数越权访问其他网盘。
- 票据短期、只读且可撤销。
