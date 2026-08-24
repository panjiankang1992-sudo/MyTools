# Download Ingestion Service

## 技术栈

Python 3.12

## 服务职责

下载请求解析、下载计划与下载业务生命周期。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/07-download-ingestion-service.md)。

已建立独立 `mytools_download` schema 的首版迁移、下载请求聚合、任务类型映射、MySQL 仓储、HTTP 接入 API 和幂等父任务编排，并提供受大小限制、校验摘要、临时文件原子落盘的 HTTP 下载任务包。HTTP 任务会拒绝凭据 URL、非公网 DNS 地址，并在每次重定向时重新校验目标，防止消息自动化等不可信入口访问本机或内网服务。`MESSAGE_ATTACHMENT` 类型只携带 Messaging 附件作业 UUID，通过内部内容流取得鉴权 provider 内容，复用相同的大小限制、原子发布、资产登记和结果回写链路。现阶段 DownloadBot 旧 worker 仍是权威执行路径，新任务仅供旁路验证。

HTTP 和消息附件下载成功后先执行 `download_publish_file`，重新校验执行器文件的大小与
SHA-256，并幂等发布到 Storage Gateway 受管根。后续 Asset Registry 登记和结果回写只消费
`storage://` 逻辑 URI，不依赖执行节点本地目录；旧任务实例仍兼容原有步骤输出，但不会被
原地改写。消息自动化创建下载请求时会透传标准消息的 `ownerId`；其他尚未完成身份映射的
旧来源暂以系统所有者 `0` 登记，后续迁移任务再绑定真实租户。

服务默认监听 `127.0.0.1:23220`，通过 `DOWNLOAD_DB_*` 和 `TASK_SCHEDULER_URL` 配置。`POST /api/v1/download-requests` 可供 DownloadBot 后续的默认关闭旁路调用，`GET /api/v1/download-requests/{id}` 查询业务请求及任务绑定。`GET /api/v1/download-requests/{id}/result-summary` 返回按 `itemId` 稳定排序的文件名、SHA-256、字节数、逻辑存储 URI 和资产标识，并汇总文件数、总字节数与确定性集合摘要；响应不包含源 URL 和任务参数。响应还提供忽略执行器 `itemId` 的 `contentSetSha256`，用于证明旧新系统的文件名、内容 SHA-256 和字节数多重集合一致。

V4 为 `download_request` 增加权威 `owner_id`。迁移会从旧 `parameters_json.ownerId`
回填可验证的非负整数，无法可靠识别的旧请求保留为系统所有者 `0`。创建接口优先读取
顶层 `ownerId`，兼容旧调用的嵌套 owner，但两者冲突时拒绝；创建 Scheduler 参数时始终
用聚合 owner 覆盖参数值。`/internal/v1/download-requests/{id}`、`/result-summary` 和
`/cancel` 必须携带 `ownerId`，所有权不匹配统一返回不存在，且不会查询或取消 Scheduler。

## 运行配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DOWNLOAD_HTTP_HOST` | `127.0.0.1` | HTTP 监听地址 |
| `DOWNLOAD_HTTP_PORT` | `23220` | HTTP 监听端口 |
| `DOWNLOAD_DB_HOST` | `127.0.0.1` | MySQL 地址 |
| `DOWNLOAD_DB_PORT` | `3306` | MySQL 端口 |
| `DOWNLOAD_DB_NAME` | `mytools_download` | 独立 schema |
| `DOWNLOAD_DB_USER` | 无 | 最小权限账号 |
| `DOWNLOAD_DB_PASSWORD` | 无 | 数据库密码 |
| `TASK_SCHEDULER_URL` | `http://127.0.0.1:23410` | Scheduler 地址 |
| `DOWNLOAD_INTERNAL_TOKEN` | 无 | 内部 API Bearer Token；为空时除健康检查外拒绝所有请求 |

创建、查询和取消接口均要求内部 Bearer Token。相同幂等键只能重放完全相同的来源、类型和参数，内容变化会被拒绝而不是复用旧请求。

当前线上契约仅开放已注册执行包的 `HTTP_ASSET`。其余下载类型将在对应任务定义、执行包和回归测试完成后逐项开放。
当前还开放 `LOCAL_IMPORT`：调用方必须先把来源表示为 `storage://` 逻辑 URI，
`download_storage_object` 任务负责受限读取、摘要校验和向目标托管根的幂等发布；API 和
下载 schema 不接受任意本机物理路径。

`X_POST` 使用 `download_x_post` 父任务：解析脚本通过受限 `gallery-dl --no-download`
获得 `twimg.com` HTTPS 媒体清单，为每个媒体幂等创建 `download_http_asset` 子任务并等待
终态。父任务运行在独立 `download-orchestration` 集群，避免占用实际下载集群造成子任务
饥饿；Cookie 文件和代理只允许由执行节点环境注入，不进入任务参数或下载 schema。

`WEB_ARCHIVE` 使用 `download_web_archive` 父任务逐跳验证公网 HTTP(S) 地址并抓取有界
HTML。正文交给 `download_publish_text` 子任务，嵌入媒体交给 `download_http_asset`
子任务；两类产物复用相同的 Asset Registry 登记与 Download Ingestion 结果回写。
脚本忽略 script/style 内容，限制页面、正文、媒体数量和单媒体字节数，代理仅由节点环境注入。

`MAGNET` 已映射到默认禁用的 `download_pikpak_magnet` 父任务。任务通过专用 PikPak
Connector 创建并推进可恢复操作；账户凭据、rclone remote key 和服务端路径不会进入 Scheduler
参数。云端对象达到 `READY` 后，父任务为每个文件创建 `download_remote_storage_object`
子任务；子任务通过 Storage Gateway 的 Provider UUID 和逻辑路径读取内容，执行大小与 SHA-256
校验，发布至受管 Root，然后复用 Asset Registry 登记和结果回写步骤。父任务仍默认禁用，完成
真实 PikPak/rclone 集成和旧新摘要对账后才允许灰度启用。

历史迁移使用 `V3__create_legacy_history_import.sql` 的独立不可变历史表，不把旧完成记录
伪装成新的 `download_request`，因此不会触发下载。内部接口
`POST /internal/v1/migrations/downloadbot-history/batches` 支持 dry-run、幂等重放和身份冲突
审计。正式迁移由 `download_migrate_legacy_history` 任务分页读取已封存快照，并校验条目数和
集合摘要闭合后调用该接口。

导入端支持 `ASSET`、`LINK_JOB`、`LINK_ASSET` 和 `EVENT_ASSET` 四类固定载荷。
`EVENT_ASSET` 保存普通消息下载与内容资产的脱敏来源关系；服务会校验事件身份摘要、
内容摘要、来源系统和来源序号，并拒绝任何未知顶层字段，因此原始消息、发送者、会话、
平台文件 ID 或机器人账号不能经迁移 API 写入下载 schema。

`download_reconcile_legacy_result` 任务按一个旁路事件读取旧快照证据和新结果摘要，比较
终态、文件数、总字节数和 `contentSetSha256`。适配器对账接口仍默认关闭。

生产演练完成后使用 `service/scripts/download_cutover_gate.py` 离线校验快照、dry-run、
正式导入、同键重放和抽样对账报告。门禁只输出安全计数和稳定错误码，不输出事件身份、
下载请求、摘要或旧任务标识，也不会连接数据库或修改运行开关。

新建 HTTP 和消息附件任务的流水线依次执行 `download_asset`、`publish_asset`、
`register_asset` 和 `record_result`。发布步骤保留执行器原文件，避免影响旧链路；最后一步通过
内部 API 将校验摘要、`storage://` 逻辑 URI 和 Asset Registry 标识原子写入下载 schema，
并生成待发布 outbox 事件。所有步骤均可安全重放，内容冲突会拒绝。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
