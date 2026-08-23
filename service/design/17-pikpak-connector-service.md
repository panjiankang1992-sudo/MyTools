# PikPak Connector Service 详细设计

## 职责边界

PikPak Connector 是外部网盘协议适配服务，只负责账户引用、离线任务提交、云端稳定性观察和受控目录移动。它不保存 PikPak 明文凭据，不下载文件到本地，不登记资产，也不承担通用网盘索引；通用远端复制、校验和本地落盘仍由 Storage Gateway 负责。

Download Ingestion 将 `MAGNET` 请求映射为 `download_pikpak_magnet` 父任务。父任务只携带下载请求 UUID、PikPak 账户 UUID 和 magnet URI；脚本调用 Connector 创建幂等操作并反复推进状态机，云端结果稳定后由后续阶段通过 Storage Gateway 托管传输，并创建原子下载子任务。

## 独立数据模型

使用独立 `mytools_pikpak_connector` schema：

- `pikpak_account`：账户 UUID、外部键、rclone Provider UUID、Secret 引用、启用状态和账户级稳定窗口。
- `pikpak_offline_operation`：幂等键、输入摘要、服务端受控工作目录、阶段、稳定签名与时间、错误码。
- `pikpak_operation_item`：稳定后的远端文件 ID、相对路径、大小和修改时间。
- `pikpak_outbox_event`：状态变化事件；不包含 magnet URI、凭据或物理路径。

magnet URI 仅用于首次提交，不持久化原文；数据库保存 SHA-256。rclone remote key、离线目录根和观察目录根均由账户配置在服务端解析，任务参数不得覆盖。

## 持久化状态机

`CREATED -> SUBMITTED -> OBSERVING -> STABLE -> MOVING -> READY`。每次 `advance` 只做一次有界远端调用并提交检查点，因此 Executor 崩溃后可由同一任务实例安全重试。连续观察到相同的文件集合签名并超过稳定窗口才允许移动。失败进入 `FAILED`；取消进入 `CANCELLING`，在未开始移动时清理隔离目录，开始移动后采用前向恢复，避免丢失已完成文件。

所有 rclone 调用均通过回环 RC 白名单：`backend/addurl`、`operations/list`、`sync/moveto`、`job/status`、`job/stop`。调用方不能提交 remote key、任意命令或目标路径。

## API

- `POST /api/internal/v1/pikpak/operations`：按幂等键创建操作；只接受账户 UUID、magnet URI 和业务引用。
- `POST /api/internal/v1/pikpak/operations/{id}/advance`：推进一个状态；仅首次提交需再次携带
  magnet URI，服务校验其摘要后使用且不持久化原文。
- `GET /api/internal/v1/pikpak/operations/{id}`：读取脱敏状态及稳定对象清单。
- `POST /api/internal/v1/pikpak/operations/{id}/cancel`：请求可恢复取消。

接口使用独立内部令牌。创建响应和日志不回显 magnet URI；错误只返回统一错误码。

## 迁移与切换

1. 新建服务和 schema，默认 `PIKPAK_CONNECTOR_ENABLED=false`，不改变 DownloadBot 轮询。
2. 显式启用适配器的独立 PikPak 配置导出门禁；导出内容不包含本地 rclone 配置、代理、备份路径或凭据。
3. 创建 `pikpak_migrate_legacy_accounts` 并先以 `dryRun=true` 执行。参数必须完整映射每个旧
   `externalKey` 到 Storage Provider UUID 和 `secret://` 引用，禁止按名称猜测。
4. 正式执行后核对源集合摘要及登记结果。所有迁移账户强制为禁用；旧 `enabled` 只作为审计信息。
5. 无法可靠迁移的运行中任务重新生成，不复制 lease。使用 `download_reconcile_legacy_result`
   比较同一测试 magnet 的文件数、总字节数和内容集合摘要。
6. Provider、Secret、稳定窗口和内容摘要全部验证后，按账户灰度启用 `MAGNET` 新入口；旧 watcher
   保留但不得同时消费同一隔离目录。
7. 稳定后再把 READY 对象交给 Storage Gateway 的托管传输任务，最后停用旧轮询。

## 当前实现

已实现 Java 21 / Spring Boot 服务、V3 schema、内部鉴权、账户脱敏登记、账户级稳定窗口、操作幂等冲突检查、
乐观锁检查点、文件集合摘要、稳定窗口、异步移动和取消前向收敛。rclone RC 被限制为回环 HTTP，
代码只暴露固定的 `backend/command(addurl)`、`operations/list`、`sync/move`、`job/status`、
`job/stop` 和 `operations/purge`，外部请求不能选择动作或 remote key。

READY 响应只暴露 Storage Provider UUID 和逻辑远端路径。`download_pikpak_magnet` 为每个对象
创建 `download_remote_storage_object` 子任务；子任务通过 Storage Gateway 的受控
`operations/cat` 流读取、执行双重大小限制、计算 SHA-256、发布到受管 Root，并复用 Asset
Registry 与 Download Ingestion 回写步骤。父任务等待全部子任务成功，任一失败时取消其他活跃子任务。

服务及 PikPak 父任务仍默认禁用；还需使用真实 PikPak/rclone 环境完成旧新内容集合摘要对账。
账户迁移任务可手工创建，但只会登记禁用账户，不会绕过上述启用门禁。

## 验收

- 相同幂等键不会重复调用 `addurl`。
- Executor 在任意阶段退出后可从数据库检查点恢复。
- 凭据、remote key 和物理路径不进入 Scheduler 参数及结果；原始 magnet 只存在于受控下载请求
  和父任务输入，不写入 Connector 数据库、Outbox 或任务结果。
- 未达到稳定窗口的文件不会移动或发布。
- 取消、超时和失败均可收敛并保留审计事件。
