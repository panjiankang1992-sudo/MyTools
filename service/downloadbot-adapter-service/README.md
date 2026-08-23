# DownloadBot Adapter Service

DownloadBot 到 Download Ingestion 的独立旁路适配器，使用 Python 3.12 和独立 `mytools_downloadbot_adapter` schema。该目录不加入根工程，也不修改或替换现有 DownloadBot。

默认 `DOWNLOADBOT_ADAPTER_MODE=DISABLED`：事件只进入新 schema 的幂等收件箱，不调用下载服务。仅在部署方显式设为 `SHADOW` 后，适配器才使用 `downloadbot:{eventId}` 幂等键创建新下载请求；旧 DownloadBot 始终保持权威执行和返回路径。

内部接口为 `POST /internal/v1/downloadbot/events`，要求 `DOWNLOADBOT_ADAPTER_INTERNAL_TOKEN`。数据库账号只需本 schema 的 DML 权限；调用 Download Ingestion 使用单独的 `DOWNLOAD_INGESTION_TOKEN`。

## 历史快照迁移

`V2__create_legacy_snapshot.sql` 增加独立快照、条目和拒绝审计表。执行
`downloadbot_capture_snapshot` 任务时，脚本使用只具备旧 `downloadbot` schema `SELECT`
权限的单独账号开启一致性只读事务，先固定相关表高水位，再将
标准化结果写入适配器 schema 并原子封存。

`1.1.0` 根据 DownloadBot 当前真实表结构新增 `asset_sources` 捕获，覆盖普通
QQ、Telegram 和 OneBot 消息管线的 `ingress_events → asset_sources → assets` 关系。
事件身份由 `platform + bot_account_id + event_id` 计算 SHA-256；快照不保存这些原值，
也不保存 `raw_payload`、`platform_file_id`、发送者、会话或机器人账号。原 `1.0.0`
脚本包保持不可变，Scheduler V65 只让新建任务使用 `1.1.0`。

快照不会导出旧物理路径、原始下载 URL、消息回复路由、Cookie 或 Token。校验失败的
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
