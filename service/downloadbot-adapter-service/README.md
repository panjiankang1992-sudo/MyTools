# DownloadBot Adapter Service

DownloadBot 到 Download Ingestion 的独立旁路适配器，使用 Python 3.12 和独立 `mytools_downloadbot_adapter` schema。该目录不加入根工程，也不修改或替换现有 DownloadBot。

默认 `DOWNLOADBOT_ADAPTER_MODE=DISABLED`：事件只进入新 schema 的幂等收件箱，不调用下载服务。仅在部署方显式设为 `SHADOW` 后，适配器才使用 `downloadbot:{eventId}` 幂等键创建新下载请求；旧 DownloadBot 始终保持权威执行和返回路径。

内部接口为 `POST /internal/v1/downloadbot/events`，要求 `DOWNLOADBOT_ADAPTER_INTERNAL_TOKEN`。数据库账号只需本 schema 的 DML 权限；调用 Download Ingestion 使用单独的 `DOWNLOAD_INGESTION_TOKEN`。

## 历史快照迁移

`V2__create_legacy_snapshot.sql` 增加独立快照、条目和拒绝审计表。执行
`mytools-downloadbot-snapshot` 时，脚本使用只具备旧 `downloadbot` schema `SELECT`
权限的单独账号开启一致性只读事务，先固定 `assets` 和 `link_jobs` 高水位，再将
标准化结果写入适配器 schema 并原子封存。

快照不会导出旧物理路径、原始下载 URL、消息回复路由、Cookie 或 Token。校验失败的
记录进入 `legacy_snapshot_rejection`，不会因为单条脏数据中止整个捕获。只有状态为
`SEALED` 且集合摘要匹配的快照才允许进入后续导入任务。

- `DOWNLOADBOT_LEGACY_DB_*`：旧库，只授予 `SELECT`。
- `DOWNLOADBOT_ADAPTER_DB_*`：新适配器库，只授予本 schema DML。

实时旁路仍默认关闭。历史快照捕获不修改旧任务状态，也不接管旧服务流量。
