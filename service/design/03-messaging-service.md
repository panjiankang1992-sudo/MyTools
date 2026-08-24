# Messaging Service 详细设计

> MsgService 实际代码位于远程 `/opt/code/MsgService`。其存量发件历史必须导入独立的
> `outbound_message_history` 归档，不得写入实时投递队列；详细审计和迁移顺序见
> [24-msgservice-current-state-and-migration.md](24-msgservice-current-state-and-migration.md)。

MsgService 模板和已知收件人使用 Messaging 自有 `message_template`、`known_recipient`
表保存，通过 `/internal/v1/migrations/msgservice-reference-data` 执行受保护的 dry-run、
幂等导入和集合对账，不进入实时投递或任务调度链路。

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
- `POST /internal/v1/adapters/email/poll`：由 Executor 轮询服务端配置的 IMAP 账户，默认关闭。
- `POST /internal/v1/inbound-messages/{messageId}/parts/{partId}/download`：创建附件下载父任务。
- `POST /internal/v1/attachment-downloads/{jobId}/execute`：由 Executor 幂等创建 Download Ingestion 子任务。
- `POST /internal/v1/migrations/legacy-inbound/batches`：校验、预演或导入旧系统历史入站消息。
- `GET /internal/v1/inbound-messages`：按 owner 分页查询标准化入站消息，供 Gateway 展示历史记录。
- `GET /internal/v1/migrations/legacy-inbound/{migrationKey}/reconciliation`：计算目标侧历史映射数量与集合摘要。
- `MessageReceived`、`MessageDeliveryRequested`、`MessageDelivered`、`MessageDeliveryFailed`。

## 任务化操作

- `message_send_email`、`message_poll_email`、`message_send_channel_message`。
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
3. 已迁移 OneBot 消息解析和附件标准模型，开关默认关闭；附件父任务只携带不透明作业标识，先在 Messaging 信任边界内调用独立 OneBot Connector 解析 provider file id，再转入 Download Ingestion。Connector 可返回无签名参数的 `PUBLIC_URL` 或不暴露来源的 `STREAM` 模式；后者由 `download_message_attachment` 通过 Messaging 和 Connector 两级有界内容流执行，下载后重新校验并发布到 Storage Gateway，再复用只引用 `storage://` URI 的统一资产登记链路。
   创建请求同时携带权威 owner 字段和兼容嵌套字段，状态对账使用 owner-bound 内部接口，附件作业不能读取其他租户的下载状态。提交脚本 1.1.0 会创建独立终态对账子任务，避免只在用户查询时才发现失败或取消。
4. 已按远程 `/opt/code/MsgService` 的真实 TypeScript 和 SQLite schema 实现入站、发件、模板与已知收件人迁移。发件历史进入独立归档，不触发投递任务或实时事件；适配器通过 SQLite online backup 捕获 WAL，生成冻结发件批次、附件内容寻址归档和参考数据批次。生产只读演练已完成 11 条发件、7 个模板、5 个已知收件人的数量与摘要核对，2 个源端已丢失的附件以 `MISSING` 证据保留。剩余步骤是在新 Messaging schema 上执行正式 dry-run、导入、重放和目标对账。
5. MyTools 问题反馈作为用户发给系统的支持消息归入 Messaging，但使用独立 `support_feedback` 表保留其处理状态和联系人字段。V7、`feedback_migrate_legacy` 和离线门禁已支持只读快照式迁移、幂等重放及数量对账。
5. 已增加 owner-bound 消息分页与详情查询；Gateway 仅返回正文和安全附件元数据，不暴露 provider file id、账户键、来源 URL、外部消息 ID或会话键。
6. 已将邮件投递创建、状态查询和尽力取消接入 Gateway；相同 owner 与幂等键必须匹配相同邮件内容。
7. 使用实际 NapCat 环境联调 OneBot Connector；继续扩展 Telegram adapter，渠道凭据只能由隔离 connector 使用。
8. 数据验收后删除 MyTools SMTP 和 DownloadBot 渠道发送逻辑。

MsgService 的 IMAP 能力已按远程 `/opt/code/MsgService` 的实际实现迁入 Messaging，但不替换旧监听器。`message_poll_email` 任务只携带英文、数字或下划线组成的账户逻辑键，IMAP 主机和凭据只存在 Messaging 配置。服务以只读模式按 UID 增量读取，不修改 `Seen` 标志；V10 保存 `UIDVALIDITY + last_uid` 检查点，检查点只在整批消息和附件任务创建成功后推进，进程失败会安全重放。Message-ID 经过 SHA-256 后参与幂等键，缺失 Message-ID 时使用账户、UIDVALIDITY 和 UID。

MIME 正文和附件元数据在任务内解析，附件字节不进入轮询事务或消息表。每个附件保存服务端生成的 IMAP 引用并立即创建统一附件下载任务；解析步骤将邮件附件绑定为 `STREAM`，Download Ingestion 再通过 Messaging 只读重开邮箱并有界转发指定附件。这样 UID 检查点不会越过尚未成功进入下载编排的附件。`MESSAGING_EMAIL_INGRESS_ENABLED` 默认关闭，旧 MsgService 继续权威收件，启用新轮询不会标记已读或修改旧 SQLite。

## 验收

- 同一投递幂等键最多产生一个逻辑消息。
- 渠道故障不会阻塞其他渠道。
- 敏感凭据不进入任务参数、日志或事件。
- SMTP 网络调用不占用数据库事务，状态与 Outbox 更新保持短事务原子性。
- 附件入站事务不下载文件，重复父任务执行最多绑定一个 Download Ingestion 请求。
- Messaging 查询附件任务时应与 Download Ingestion 对账终态，不复制下载产物明细。
- 历史迁移重复执行不得产生重复消息，载荷冲突必须拒绝且不得触发 `MessageReceived`。
