# Download Ingestion Service

## 技术栈

Python 3.12

## 服务职责

下载请求解析、下载计划与下载业务生命周期。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/07-download-ingestion-service.md)。

已建立独立 `mytools_download` schema 的首版迁移、下载请求聚合、任务类型映射、MySQL 仓储、HTTP 接入 API 和幂等父任务编排，并提供受大小限制、校验摘要、临时文件原子落盘的 HTTP 下载任务包。HTTP 任务会拒绝凭据 URL、非公网 DNS 地址，并在每次重定向时重新校验目标，防止消息自动化等不可信入口访问本机或内网服务。现阶段 DownloadBot 旧 worker 仍是权威执行路径，新任务仅供旁路验证。

服务默认监听 `127.0.0.1:23220`，通过 `DOWNLOAD_DB_*` 和 `TASK_SCHEDULER_URL` 配置。`POST /api/v1/download-requests` 可供 DownloadBot 后续的默认关闭旁路调用，`GET /api/v1/download-requests/{id}` 查询业务请求及任务绑定。

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

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
