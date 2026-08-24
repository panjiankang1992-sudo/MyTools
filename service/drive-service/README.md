# Drive Service

独立网盘领域服务，使用 `mytools_drive` schema。当前 MVP 提供内部账户登记、可恢复的分批索引写入和只读索引查询；旧 MyTools Drive/rclone 接口仍为权威路径，尚未启用流量切换。

接口：

- `POST /internal/v1/drive/accounts`：按外部账户标识幂等登记账户，只保存 Secret 引用。
- `POST /internal/v1/drive/accounts/{id}/index-batches`：按运行标识和批次游标幂等写入索引。
- `GET /internal/v1/drive/accounts/{id}/items?ownerId=&parentPath=`：按所有者隔离查询索引。
- `GET /internal/v1/drive/accounts/{id}/scan?path=`：通过服务端账户绑定的白名单 rclone RC 列目录。
- `PUT /internal/v1/drive/accounts/{id}/storage-provider`：绑定 Storage Gateway Provider UUID，不复制 remote key 或凭据。

目录扫描支持 `DRIVE_STORAGE_SCAN_MODE=LEGACY|DUAL|STORAGE`。默认 `LEGACY` 保持原 rclone 路径；`DUAL` 仍返回旧路径结果，同时调用 Storage Gateway 做影子比对，Gateway 失败或差异只记录告警；完成 Provider 绑定和对账后才可切换为 `STORAGE`。

批次完成时才把旧 generation 中未出现的项目标记删除，因此失败重试不会提前破坏当前索引。

`drive_index_account` 1.0.0 任务脚本以广度优先方式递归扫描，限制目录数、项目数和单批大小，通过 batch ledger 支持任意已提交批次重放。脚本只持有 Drive 内部令牌，不接收 remote key、远端凭据或任意命令。Scheduler V24 将任务绑定到独立 `drive` 执行集群。

Drive Service 现已提供索引刷新业务闭环：`POST /internal/v1/drive/accounts/{id}/refresh-index` 创建幂等任务，`GET /internal/v1/drive/operations/{id}` 查询，取消接口请求 Scheduler 终止执行。三个接口都要求可信 `ownerId` 与账户所有者一致；Gateway 对应暴露 `/api/app/v1/drive` 路由并从认证主体注入 owner。

首个文件写操作为 `POST /internal/v1/drive/accounts/{id}/copy-object`：来源与目标账户必须属于同一 owner、目标不可只读且两端必须绑定 Storage Provider。Drive 只登记业务操作并调用 Storage Gateway 的 `COPY_OBJECT` 创建、查询和取消契约，不读取 Provider 凭据、不直接执行复制。Gateway 对外响应不暴露 Storage/Scheduler 任务标识。

递归目录复制使用 `POST /internal/v1/drive/accounts/{id}/copy-tree`，请求携带目标账户、来源根、目标根及 `maximumObjects` 硬上限。Drive 调用 Storage `COPY_TREE_NATIVE`，由 Storage 冻结来源清单并拆成受控 `COPY_OBJECT` 子操作；Drive 的统一操作查询和取消会持续同步父操作状态并级联取消。空字符串表示 Provider 根目录，路径仍经过相对路径规范化与越界校验。Gateway 对应开放 `/api/app/v1/drive/accounts/{id}/copy-tree`，沿用默认关闭的 Drive 路由开关和 owner 白名单。

`GET /internal/v1/drive/accounts?ownerId=` 返回当前所有者的账户，用于客户端进入目录前取得账户 UUID。Gateway 只返回显示名称、Provider 类型、只读/启用状态和索引 generation，不暴露外部账户标识、remote key 或 Secret 引用。

Scheduler V25 为失败、超时和取消配置 `drive_finish_index` 特殊步骤，使未完成游标进入明确终态，后续补偿运行可以安全接管；收尾失败采用 `IGNORE`，不会掩盖任务原始终态。

`drive_migrate_legacy_accounts` 任务通过 MyTools 只读分页接口迁移旧 `drive_account` 与 `webdav_account` 元数据。接口只返回 `secret://mytools/...` 引用，不返回加密密码、URL 或用户名；WebDAV/Alist 账户默认禁用，完成 provider 配置和对账后才能启用。Scheduler V26 提供手工即时迁移任务，不会自动执行。

`storage_migrate_drive_providers` 手工任务通过独立 `DRIVE_STORAGE_MIGRATION_TOKEN` 分页读取账户 UUID、remote key、Secret 引用和启用状态，幂等注册 Storage Provider 后回绑 Drive。迁移接口不返回 URL、用户名或密码；Scheduler V31 不会自动执行。

`drive_reconcile_index` 1.1.0 手工任务按共享长度前缀协议比较 Drive 当前有效索引与一次成功的 Storage 根扫描快照，同时校验迁移键、账户、Provider、扫描操作归属、对象数和 SHA-256 集合摘要。报告只返回严格字段和差异原因；`matched` 必须为 `true` 才能作为 `STORAGE` 模式切换证据。协议见 `service/contracts/reconciliation-digest.md`。

## 技术栈

Java 21 / Spring Boot

## 服务职责

网盘账户、索引、文件操作和访问票据。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/12-drive-service.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
