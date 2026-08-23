# Download Ingestion Service

## 技术栈

Python 3.12

## 服务职责

下载请求解析、下载计划与下载业务生命周期。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/07-download-ingestion-service.md)。

已建立独立 `mytools_download` schema 的首版迁移、下载请求聚合、任务类型映射、MySQL 仓储、HTTP 接入 API 和幂等父任务编排，并提供受大小限制、校验摘要、临时文件原子落盘的 HTTP 下载任务包。HTTP 任务会拒绝凭据 URL、非公网 DNS 地址，并在每次重定向时重新校验目标，防止消息自动化等不可信入口访问本机或内网服务。现阶段 DownloadBot 旧 worker 仍是权威执行路径，新任务仅供旁路验证。

HTTP 下载成功后追加可忽略的 `asset_register_content` 步骤，将摘要、大小及相对位置转换为不暴露物理根目录的 `download://executor/...` URI，并镜像到 Asset Registry。消息自动化创建下载请求时会透传标准消息的 `ownerId`；其他尚未完成身份映射的旧来源暂以系统所有者 `0` 登记，后续迁移任务再绑定真实租户。

服务默认监听 `127.0.0.1:23220`，通过 `DOWNLOAD_DB_*` 和 `TASK_SCHEDULER_URL` 配置。`POST /api/v1/download-requests` 可供 DownloadBot 后续的默认关闭旁路调用，`GET /api/v1/download-requests/{id}` 查询业务请求及任务绑定。`GET /api/v1/download-requests/{id}/result-summary` 返回按 `itemId` 稳定排序的文件名、SHA-256、字节数、逻辑存储 URI 和资产标识，并汇总文件数、总字节数与确定性集合摘要；响应不包含源 URL 和任务参数。响应还提供忽略执行器 `itemId` 的 `contentSetSha256`，用于证明旧新系统的文件名、内容 SHA-256 和字节数多重集合一致。

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
| `TASK_SCHEDULER_URL` | `http://127.0.0.1:23210` | Scheduler 地址 |
| `DOWNLOAD_INTERNAL_TOKEN` | 无 | 内部 API Bearer Token；为空时除健康检查外拒绝所有请求 |

创建、查询和取消接口均要求内部 Bearer Token。相同幂等键只能重放完全相同的来源、类型和参数，内容变化会被拒绝而不是复用旧请求。

当前线上契约仅开放已注册执行包的 `HTTP_ASSET`。其余下载类型将在对应任务定义、执行包和回归测试完成后逐项开放。

历史迁移使用 `V3__create_legacy_history_import.sql` 的独立不可变历史表，不把旧完成记录
伪装成新的 `download_request`，因此不会触发下载。内部接口
`POST /internal/v1/migrations/downloadbot-history/batches` 支持 dry-run、幂等重放和身份冲突
审计。正式迁移由 `download_migrate_legacy_history` 任务分页读取已封存快照，并校验条目数和
集合摘要闭合后调用该接口。

`download_reconcile_legacy_result` 任务按一个旁路事件读取旧快照证据和新结果摘要，比较
终态、文件数、总字节数和 `contentSetSha256`。适配器对账接口仍默认关闭。

任务流水线依次执行 `download_asset`、`register_asset` 和 `record_result`。最后一步通过内部 API 将校验摘要、逻辑存储 URI 和 Asset Registry 标识原子写入下载 schema，并生成待发布 outbox 事件；回调可安全重放，内容冲突会拒绝。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
