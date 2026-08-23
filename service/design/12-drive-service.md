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

1. 以现有 `drive` 模块和 rclone 接口为主路径。
2. 将旧 WebDAV、Alist 账号迁移为统一账户。
3. 用任务替换定时索引和大文件操作。
4. 新旧接口并行验证后切换 App。
5. 拆独立服务并删除兼容模块。

## 验收

- 索引任务可从游标恢复。
- 用户不能通过任务参数越权访问其他网盘。
- 票据短期、只读且可撤销。
