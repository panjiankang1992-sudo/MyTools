# Messaging Service 详细设计

## 职责

由 MsgService 演进，统一邮件、QQ、Telegram、OneBot 等渠道的入站与出站消息、附件、模板、投递记录和回执。它不判断消息是否需要下载，不拥有验证码业务状态。

## 数据模型

- `channel_accounts`：渠道配置的密文引用。
- `conversations`、`messages`、`message_parts`、`attachments`。
- `delivery_requests`、`delivery_attempts`、`delivery_receipts`。
- `message_templates`、`inbound_event_inbox`。

## 接口与事件

- `POST /internal/v1/deliveries`：创建投递请求。
- `GET /internal/v1/deliveries/{id}`：查询状态。
- `POST /internal/v1/adapters/onebot/events`：标准化 OneBot 消息正文和附件引用，默认关闭。
- `POST /internal/v1/inbound-messages/{messageId}/parts/{partId}/download`：创建附件下载父任务。
- `POST /internal/v1/attachment-downloads/{jobId}/execute`：由 Executor 幂等创建 Download Ingestion 子任务。
- `POST /internal/v1/migrations/legacy-inbound/batches`：校验、预演或导入旧系统历史入站消息。
- `MessageReceived`、`MessageDeliveryRequested`、`MessageDelivered`、`MessageDeliveryFailed`。

## 任务化操作

- `message_send_email`、`message_send_channel_message`。
- `message_download_attachment`、`message_reconcile_attachment_download`。
- `message_migrate_history`、`message_cleanup_retention`。

步骤脚本调用 Messaging 内部 API 领取经过解析和授权的投递载荷，不能把 SMTP 密码或 Bot Token放进任务参数。

## DML 边界

- 投递状态应走内部 API或存储过程，确保状态机和 Outbox 同步更新。
- 历史归档、清理和修复脚本可使用受限 DML。
- 脚本不得直接修改渠道密钥和投递成功记录。

## 迁移

1. 已建立 provider-neutral 投递、投递尝试、标准入站消息与 Outbox schema，并实现 SMTP 原子 provider。
2. 已建立只携带 `deliveryId` 的 `message_send_email` 任务，并接入默认关闭、旧事务提交后触发的 MyTools 注册邮件旁路。
3. 已迁移 OneBot 消息解析和附件标准模型，开关默认关闭；附件父任务只携带不透明作业标识，先在 Messaging 信任边界内调用独立 OneBot Connector 解析 provider file id，再转入 Download Ingestion。Connector 可返回无签名参数的 `PUBLIC_URL` 或不暴露来源的 `STREAM` 模式；后者由 `download_message_attachment` 通过 Messaging 和 Connector 两级有界内容流执行并复用统一资产登记链路。
   创建请求同时携带权威 owner 字段和兼容嵌套字段，状态对账使用 owner-bound 内部接口，附件作业不能读取其他租户的下载状态。提交脚本 1.1.0 会创建独立终态对账子任务，避免只在用户查询时才发现失败或取消。
4. 已建立历史入站消息批次迁移表、dry-run/幂等导入接口和 `message_migrate_history` 1.1.0 脚本任务；历史记录不产生实时自动化事件。独立快照适配器已提供默认关闭的装载，以及冻结高水位、数量和集合摘要的稳定分页导出；下一步在取得旧 MsgService 真实 schema 后实现只读映射，并执行生产副本摘要对账。
5. 使用 NapCat 生产副本联调 OneBot Connector，并执行流式中断、超限、重试和内容摘要对账；继续扩展 Telegram adapter，渠道凭据只能由隔离 connector 使用。
6. 先双投递到审计通道，再切换真实发送。
7. 删除 MyTools SMTP 和 DownloadBot 渠道发送逻辑。

## 验收

- 同一投递幂等键最多产生一个逻辑消息。
- 渠道故障不会阻塞其他渠道。
- 敏感凭据不进入任务参数、日志或事件。
- SMTP 网络调用不占用数据库事务，状态与 Outbox 更新保持短事务原子性。
- 附件入站事务不下载文件，重复父任务执行最多绑定一个 Download Ingestion 请求。
- Messaging 查询附件任务时应与 Download Ingestion 对账终态，不复制下载产物明细。
- 历史迁移重复执行不得产生重复消息，载荷冲突必须拒绝且不得触发 `MessageReceived`。
