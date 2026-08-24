# Download Ingestion Service 详细设计

业务 Gateway 仅返回下载请求标识、业务状态和安全结果摘要，不返回 Scheduler 任务实例标识、源 URL 或内部任务参数。

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
- `contentSetSha256`：忽略执行器条目标识，按文件名、内容 SHA-256 和字节数计算的多重集合摘要。
- `POST /api/v1/download-requests/{id}/cancel`：取消绑定的任务并同步取消状态。
- `GET /health`：进程健康检查。
- `POST /internal/v1/migrations/downloadbot-history/batches`：预检或幂等导入一个标准化历史批次。

首版服务使用 Python 3.12，业务数据写入独立 `mytools_download` schema。Scheduler 短暂不可用时业务请求保留为 `ACCEPTED`，相同幂等键重放会继续创建并绑定任务，不产生第二条下载请求。

下载聚合使用独立 `owner_id` 作为权限边界。旧请求优先从合法的参数 owner 回填，无法映射
的记录归系统所有者 `0` 并等待后续绑定。面向 Gateway 的查询、摘要和取消接口同时匹配
请求 UUID 与 owner；不匹配时在访问 Scheduler 前返回不存在。Scheduler 参数中的 owner
由聚合覆盖，不能被调用方在通用参数对象中替换。

## 任务类型

- `download_http_asset`、`download_x_media`、`download_web_archive`。
- `download_pikpak_asset`、`download_magnet_asset`。
- `download_message_attachment`、`download_local_import`。

下载父任务可创建解析、各文件下载和汇总子任务。HTTP 和消息附件原子任务先把内容写入
有界临时文件并校验摘要，再由独立发布步骤重新校验并写入 Storage Gateway 受管根，最后
通过 Asset Registry API 登记资产并回写下载结果。新成功结果只保存 `storage://` 逻辑 URI，
不能暴露执行节点目录；旧任务实例保留兼容读取，不进行破坏性回填。禁止直接写 Media
Library 表。

X 帖子采用解析父任务加 HTTP 文件子任务。解析器只接受单个 `/status/{id}`，只输出
HTTPS `*.twimg.com` 资源；全部子任务创建后再等待，任一子任务失败时取消仍运行的同批
任务。解析父任务与实际下载任务使用不同集群，防止父任务等待导致下载 worker 饥饿。

WebArchive 同样采用解析父任务。解析器逐跳校验公网地址、限制 HTML 字节数、忽略脚本
和样式，正文交由生成文本原子任务，媒体交由 HTTP 原子任务。父任务不直接发布资产，
并通过 SDK 的统一等待和取消原语管理全部直接子任务。

## DML

下载脚本可用受限 DML 更新下载进度和检查点，但完成状态建议调用 Download 内部 API，以便同时写 Outbox。断点信息必须幂等，重试不得产生第二个资源包。

## 迁移

1. 保留 DownloadBot 表和模型，给现有 worker 增加默认关闭的任务适配层。
2. 将 HTTP/X 下载封装为首批脚本；当前已完成受限 HTTP 下载任务，以及只接受
   Storage Gateway 逻辑 URI 的本地导入任务。
3. 迁移 PikPak、magnet 和消息附件。
4. 通过 `downloadbot_capture_snapshot` 1.1.0 捕获旧库一致性快照；快照同时覆盖链接任务和
   普通消息下载的脱敏资产来源关系，再由 `download_migrate_legacy_history` 经受保护 API
   导入历史；旧库只读账号与两个 API
   令牌相互隔离。实时双跑仍使用新服务按文件项稳定排序的摘要契约。
   导入 API 对四类载荷使用固定字段白名单；`EVENT_ASSET` 的事件摘要、内容摘要、来源类型
   和序号必须全部有效，未知或私有消息字段进入拒绝审计而不是历史表。
   新增的独立只读实时桥接不修改旧 DownloadBot：默认从启用时高水位之后读取 `link_jobs`，
   将 HTTP、X 和具有明确新账户映射的 Magnet 转成 Adapter 事件；失败停留游标重试，无法映射
   的记录保留拒绝证据。普通消息附件仍等待旧服务显式事件或 Messaging 标准附件链路，不从
   历史资产关系反向构造临时下载请求。
5. 使用 `download_reconcile_legacy_result` 比较终态、文件数、总字节数和内容集合摘要；
   不匹配只产出任务证据，不自动切换流量。
6. 关闭旧 worker loop，保留 API、MCP 和业务查询。
7. 渠道接入迁往 Messaging/Automation。

## 验收

- 原有断点续传、SHA-256 去重和原子发布保持不变。
- HTTP 与消息附件任务成功后，下载结果和资产登记均引用同一个持久化 `storage://` URI。
- 一个下载请求可查询全部子任务。
- 父任务取消能停止所有下载并按策略保留或清理 staging。
