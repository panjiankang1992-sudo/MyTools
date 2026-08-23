# Download Ingestion Service 详细设计

## 职责

由 DownloadBot 核心演进，负责下载请求、来源解析、下载计划、业务状态和资产登记编排。实际下载由 Executor 中的脚本执行。

## 数据模型

- `download_requests`：来源、请求参数、业务状态。
- `download_items`：一个请求展开出的文件项。
- `source_references`：消息、URL、帖子、PikPak 等来源。
- `download_task_bindings`：任务实例关联。
- `download_outbox`。

## 任务类型

- `download_http_asset`、`download_x_media`、`download_web_archive`。
- `download_pikpak_asset`、`download_magnet_asset`。
- `download_message_attachment`、`download_local_import`。

下载父任务可创建解析、各文件下载和汇总子任务。脚本通过 Storage Gateway 写 staging，通过 Asset Registry API 发布资产；禁止直接写 Media Library 表。

## DML

下载脚本可用受限 DML 更新下载进度和检查点，但完成状态建议调用 Download 内部 API，以便同时写 Outbox。断点信息必须幂等，重试不得产生第二个资源包。

## 迁移

1. 保留 DownloadBot 表和模型，给现有 worker 增加任务适配层。
2. 将 HTTP/X 下载封装为首批脚本。
3. 迁移 PikPak、magnet 和消息附件。
4. 双写旧任务状态与新任务绑定并对账。
5. 关闭旧 worker loop，保留 API、MCP 和业务查询。
6. 渠道接入迁往 Messaging/Automation。

## 验收

- 原有断点续传、SHA-256 去重和原子发布保持不变。
- 一个下载请求可查询全部子任务。
- 父任务取消能停止所有下载并按策略保留或清理 staging。
