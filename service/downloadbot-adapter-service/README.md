# DownloadBot Adapter Service

DownloadBot 到 Download Ingestion 的独立旁路适配器，使用 Python 3.12 和独立 `mytools_downloadbot_adapter` schema。该目录不加入根工程，也不修改或替换现有 DownloadBot。

默认 `DOWNLOADBOT_ADAPTER_MODE=DISABLED`：事件只进入新 schema 的幂等收件箱，不调用下载服务。仅在部署方显式设为 `SHADOW` 后，适配器才使用 `downloadbot:{eventId}` 幂等键创建新下载请求；旧 DownloadBot 始终保持权威执行和返回路径。

内部接口为 `POST /internal/v1/downloadbot/events`，要求 `DOWNLOADBOT_ADAPTER_INTERNAL_TOKEN`。数据库账号只需本 schema 的 DML 权限；调用 Download Ingestion 使用单独的 `DOWNLOAD_INGESTION_TOKEN`。

## 旧库只读实时桥接

不修改旧 DownloadBot 的部署方式是单独运行 `mytools-downloadbot-live-bridge`。该进程只读取旧库 `link_jobs` 的身份、原始链接、类型、策略和来源键，在适配器 schema 中保存独立游标，然后复用同一个幂等收件箱。它不读取消息正文、反馈路由、结果 JSON、Cookie 或旧配置文件，也不更新旧任务状态。

桥接具有两层关闭门禁：必须显式设置 `DOWNLOADBOT_LIVE_BRIDGE_ENABLED=true` 才能启动；只有 `DOWNLOADBOT_ADAPTER_MODE=SHADOW` 才会创建新下载任务，`DISABLED` 仅保存收件箱事件。默认 `DOWNLOADBOT_LIVE_BRIDGE_START_MODE=LATEST` 会在首次启动冻结旧 `link_jobs` 当前高水位，防止历史任务被重新下载。`BEGINNING` 只用于隔离测试或明确批准的全量重放，日常迁移不得使用。

关键配置：

- `DOWNLOADBOT_LEGACY_DB_*`：旧库连接，只授予 `link_jobs` 的 `SELECT`。
- `DOWNLOADBOT_ADAPTER_DB_*`：适配器 schema DML，与旧库账号隔离。
- `DOWNLOADBOT_PIKPAK_ACCOUNT_MAPPING`：旧账户键到新 PikPak UUID 的 JSON 对象；缺少映射的 Magnet 写入拒绝证据，不猜测账户。
- `DOWNLOADBOT_LIVE_BRIDGE_POLL_SECONDS`：轮询间隔，默认 5 秒，范围 1 至 300 秒。
- `DOWNLOADBOT_LIVE_BRIDGE_PAGE_SIZE`：单页上限，默认 100，最大 500。

HTTP 链接映射为 `WEB_ARCHIVE`，由新父任务在执行时区分网页和直接资源；X 帖子映射为 `X_POST`；Magnet 映射为 `MAGNET`。影子转发失败时游标不会越过当前记录，恢复后会以相同 `downloadbot-link:{legacyId}` 事件标识重试。由 `DISABLED` 切换到 `SHADOW` 时，桥接进程先重放收件箱中既有的 `RECEIVED`、`FAILED` 事件，再消费新旧库行，因此审计阶段捕获的事件不会静默滞留。

## 历史快照迁移

`V2__create_legacy_snapshot.sql` 增加独立快照、条目和拒绝审计表。执行
`downloadbot_capture_snapshot` 任务时，脚本使用只具备旧 `downloadbot` schema `SELECT`
权限的单独账号开启一致性只读事务，先固定相关表高水位，再将
标准化结果写入适配器 schema 并原子封存。

`1.1.0` 根据 DownloadBot 当前真实表结构覆盖 `ingress_events`、`messages`、`assets`、
`asset_sources`、`link_jobs` 和 `link_asset_sources`。受限快照完整保留不可再生的事件原始
载荷、事件身份、标准消息身份和消息关系；资产物理路径、原始下载 URL、回复路由及凭据仍不进入
快照。Scheduler V73 更新任务定义说明，捕获仍使用旧库只读事务。

快照不会导出旧物理路径、原始下载 URL、消息回复路由、Cookie 或 Token。`raw_payload`
作为旧消息本体被原样封存，因此适配器 schema 和导出令牌必须按敏感数据管理。校验失败的
记录进入 `legacy_snapshot_rejection`，不会因为单条脏数据中止整个捕获。只有状态为
`SEALED` 且集合摘要匹配的快照才允许进入后续导入任务。

- `DOWNLOADBOT_LEGACY_DB_*`：旧库，只授予 `SELECT`。
- `DOWNLOADBOT_ADAPTER_DB_*`：新适配器库，只授予本 schema DML。

实时旁路仍默认关闭。历史快照捕获不修改旧任务状态，也不接管旧服务流量。

生产迁移通过任务调度服务创建 `downloadbot_capture_snapshot`，由迁移执行集群运行
`downloadbot_capture_snapshot/1.1.0` 脚本包。快照导出还需显式设置
`DOWNLOADBOT_SNAPSHOT_EXPORT_ENABLED=true`；缺省状态下只允许捕获，不允许读取快照内容。
导出接口使用独立的 `DOWNLOADBOT_SNAPSHOT_EXPORT_TOKEN`，不得复用实时事件接入令牌。
单事件结果证据接口还需显式设置 `DOWNLOADBOT_RECONCILIATION_ENABLED=true`；它只读取
已封存快照和 `FORWARDED` 事件映射，不会修改旧任务或新下载请求。

## PikPak 账户元数据导出

旧 YAML 配置可通过默认关闭的
`GET /internal/v1/migration/downloadbot/pikpak-accounts` 分页导出。启用时必须同时设置：

- `DOWNLOADBOT_LEGACY_CONFIG_PATH`：旧 DownloadBot YAML 配置的绝对路径。
- `DOWNLOADBOT_PIKPAK_EXPORT_ENABLED=true`：显式打开只读导出门禁。
- `DOWNLOADBOT_PIKPAK_EXPORT_TOKEN`：仅供本导出接口使用的独立令牌。

接口只返回账户外部键、rclone remote key、离线/就绪逻辑根、旧启用状态和稳定窗口。
它不会导出 rclone 配置文件路径、代理、备份目录、暂存目录或任何凭据。导出结果带集合摘要，供
`pikpak_migrate_legacy_accounts` 任务在分页结束后校验配置未发生变化。
