# Download Ingestion Service 详细设计

## 职责

由 DownloadBot 核心演进，负责下载请求、来源解析、下载计划、业务状态和资产登记编排。实际下载由 Executor 中的脚本执行。

## 数据模型

- `download_request`：来源、请求参数、业务状态。
- `download_item`：一个请求展开出的文件项。
- `source_reference`：消息、URL、帖子、PikPak 等来源。
- `download_task_binding`：任务实例关联。
- `download_outbox`。
- `legacy_download_history`：从封存快照导入的不可变旧下载记录，不参与新下载生命周期。
- `legacy_download_migration_rejection`：摘要错误和身份冲突审计。

## API

- `POST /api/v1/download-requests`：按全局幂等键接受请求并绑定 Scheduler 任务。
- `GET /api/v1/download-requests/{id}`：查询请求，同时对账 Scheduler 生命周期状态。
- `GET /api/v1/download-requests/{id}/result-summary`：返回不含源参数的稳定内容摘要，用于旧新下载结果对账。
- `POST /api/v1/download-requests/{id}/cancel`：取消绑定的任务并同步取消状态。
- `GET /health`：进程健康检查。
- `POST /internal/v1/migrations/downloadbot-history/batches`：预检或幂等导入一个标准化历史批次。

首版服务使用 Python 3.12，业务数据写入独立 `mytools_download` schema。Scheduler 短暂不可用时业务请求保留为 `ACCEPTED`，相同幂等键重放会继续创建并绑定任务，不产生第二条下载请求。

## 任务类型

- `download_http_asset`、`download_x_media`、`download_web_archive`。
- `download_pikpak_asset`、`download_magnet_asset`。
- `download_message_attachment`、`download_local_import`。

下载父任务可创建解析、各文件下载和汇总子任务。脚本通过 Storage Gateway 写 staging，通过 Asset Registry API 发布资产；禁止直接写 Media Library 表。

## DML

下载脚本可用受限 DML 更新下载进度和检查点，但完成状态建议调用 Download 内部 API，以便同时写 Outbox。断点信息必须幂等，重试不得产生第二个资源包。

## 迁移

1. 保留 DownloadBot 表和模型，给现有 worker 增加默认关闭的任务适配层。
2. 将 HTTP/X 下载封装为首批脚本；当前已完成受限 HTTP 下载任务。
3. 迁移 PikPak、magnet 和消息附件。
4. 通过 `downloadbot_capture_snapshot` 捕获旧库一致性快照，再由
   `download_migrate_legacy_history` 经受保护 API 导入历史；旧库只读账号与两个 API
   令牌相互隔离。实时双跑仍使用新服务按文件项稳定排序的摘要契约。
5. 关闭旧 worker loop，保留 API、MCP 和业务查询。
6. 渠道接入迁往 Messaging/Automation。

## 验收

- 原有断点续传、SHA-256 去重和原子发布保持不变。
- 一个下载请求可查询全部子任务。
- 父任务取消能停止所有下载并按策略保留或清理 staging。
