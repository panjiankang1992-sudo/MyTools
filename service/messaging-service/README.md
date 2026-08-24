# Messaging Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

邮件、QQ、Telegram、OneBot 统一消息收发。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。已建立独立 `mytools_messaging` schema、幂等投递请求、投递尝试、标准化入站消息和事务 Outbox。详细设计见 [对应设计文档](../design/03-messaging-service.md)。

邮件发送使用 `message_send_email` 1.0.0 即时任务。Scheduler 参数只保存不透明 `deliveryId`；Executor 调用 `POST /internal/v1/deliveries/{id}/execute`，正文、收件人及 SMTP 凭据不进入任务参数或结果。Messaging Service 在短事务中维护状态机和 Outbox，在事务外调用 SMTP provider，并使用稳定 Message-ID 提供未知结果重试时的关联依据。

当前内部接口包括：

- `POST /internal/v1/deliveries`：幂等创建投递并调度发送任务。
- `GET /internal/v1/deliveries/{id}`：查询不含正文的投递状态。
- `POST /internal/v1/deliveries/{id}/cancel?ownerId=`：在 Provider 调用前取消 owner-bound 投递；已经开始发送时执行尽力取消。
- `POST /internal/v1/deliveries/{id}/execute`：Executor 触发原子 provider 调用。
- `POST /internal/v1/inbound-messages`：provider adapter 幂等写入标准入站消息。
- `GET /internal/v1/inbound-messages/{id}`：Automation 按消息标识读取标准消息。
- `GET /internal/v1/inbound-messages?ownerId=&afterId=&limit=`：按所有者和稳定游标分页读取历史消息；详情接口携带 `ownerId` 时同时执行所有权检查。

V7 将旧 MyTools `t_feedback` 归入消息域的 `support_feedback`，完整保留联系人、类别、标题、正文、处理状态和时间。`feedback_migrate_legacy` 使用旧库只读一致性事务执行 dry-run、正式导入和幂等重放；历史导入不产生实时自动化事件。
- `POST /internal/v1/adapters/onebot/events`：接收 OneBot 11 原始消息事件并标准化正文与附件引用。
- `POST /internal/v1/adapters/email/poll`：由 Executor 轮询服务端配置的 IMAP 账户。
- `POST /internal/v1/attachment-downloads/{jobId}/resolve`：按不透明作业标识解析 provider 文件引用，不返回 URL。
- `POST /internal/v1/attachment-downloads/{jobId}/content`：仅对已解析为 `STREAM` 的作业向 Executor 有界转发内容。
- `POST /internal/v1/migrations/legacy-inbound/batches`：dry-run 或幂等导入旧 MsgService 的脱敏历史入站消息批次。
- `GET /internal/v1/migrations/legacy-inbound/{migrationKey}/reconciliation`：返回目标侧迁移数量和稳定集合摘要，不返回消息正文或分段。
- `POST /internal/v1/migrations/legacy-outbound/batches`：将历史发件导入只读归档，不创建投递任务或 Outbox。
- `GET /internal/v1/migrations/legacy-outbound/{migrationKey}/reconciliation`：返回历史发件目标侧数量和摘要。
- `POST /internal/v1/migrations/msgservice-reference-data/batches`：dry-run 或幂等导入旧模板和已知收件人。
- `GET /internal/v1/migrations/msgservice-reference-data/{migrationKey}/reconciliation`：返回模板和收件人分类数量及集合摘要。

OneBot 入站默认由 `MESSAGING_ONEBOT_INGRESS_ENABLED=false` 关闭。灰度时由独立 adapter/反向代理携带内部令牌调用，不修改 DownloadBot 的现有事件消费链。事件幂等键包含 account、self、message type、conversation 和 message id；消息正文与附件分段写入 `inbound_message_part`，附件只保存 provider file id、远程 URL、文件名、MIME 和声明大小，不在 HTTP 入站事务中下载文件。合并转发内容需要由上游 OneBot adapter 展开后提交；未展开的 provider 文件引用将在后续附件下载任务中解析。

远程 HTTP 附件可通过消息分段接口幂等创建 `message_download_attachment` 任务。父任务的 Scheduler 参数只保存 `attachmentJobId`；第一步由 Messaging 在自身信任边界内把 provider file id 交给独立、凭据隔离的 OneBot Connector，第二步根据解析模式创建下载请求，并由 1.1.0 提交脚本幂等创建独立的 `message_reconcile_attachment_download` 子任务。对账子任务通过 Messaging 接口有界轮询终态，网络失败、超时和最终结果都保留在 Scheduler，不依赖用户主动查询。`PUBLIC_URL` 仅接受无用户信息、query 和 fragment 的公开 HTTPS URL；`STREAM` 使用新的 `MESSAGE_ATTACHMENT` 下载类型，从 Messaging 内容接口经 Connector 有界流式读取。下载完成后由 Download Ingestion 重新校验并发布到 Storage Gateway，资产登记和结果只引用持久化 `storage://` URI。provider account key、provider file id、Connector 令牌和签名 URL 均不会进入 Scheduler 参数或步骤结果。

`attachment_download_job` 保存解析检查点、父任务与下载请求的关联，查询时使用消息 owner 调用 Download Ingestion 的 owner-bound 接口，对账运行、成功、失败或取消状态；重复解析、创建或执行不会产生第二个逻辑下载，也不能跨租户回查。通过 `MESSAGE_PROVIDER_RESOLVER_URL` 和独立 `MESSAGE_PROVIDER_RESOLVER_TOKEN` 配置解析边界，不配置令牌不会影响入站消息接收，只有创建 provider-only 附件任务后才会失败。

面向 Gateway 的附件创建、查询和取消都携带可信 owner。所有权不匹配统一按附件任务不存在处理；取消父 Scheduler 任务会利用既有父子取消传播停止尚未完成的下载链路。

邮件投递的幂等重放会校验收件人、主题和正文完全一致，相同幂等键不能复用不同邮件。Gateway 创建、查询和取消均绑定 owner，响应不暴露任务 ID和 Provider 消息 ID。

MyTools 注册验证码已增加默认关闭的 `MESSAGING_REGISTRATION_MAIL_SIDECAR_ENABLED` 旁路。只有旧 SMTP 调用成功且验证码事务提交后才异步创建新投递；旁路异常不回滚旧链路，开发环境仅打印验证码时不会触发真实旁路邮件。旁路幂等键取验证码记录标识，便于双投递审计和后续切换。

`MESSAGE_AUTOMATION_RELAY_ENABLED` 默认关闭。启用后，Messaging 分批转发未发布的 `MessageReceived` Outbox 事件，Automation 返回成功后才标记 `published_at`；中继失败不丢弃事件，重复发送由下游消息唯一键去重。

IMAP 入站使用 `message_poll_email` 1.0.0 任务，参数只包含 `accountKey`。账户、邮箱和凭据由 `MESSAGING_IMAP_*`、`MESSAGING_EMAIL_OWNER_ID` 与 `MESSAGING_EMAIL_ACCOUNT_KEY` 配置，`MESSAGING_EMAIL_INGRESS_ENABLED=false` 默认拒绝轮询。轮询始终以只读方式打开邮箱，不设置已读标志；`email_poll_checkpoint` 记录账户、邮箱、UIDVALIDITY 和最后成功 UID。只有整批消息以及其中所有附件的统一下载任务都创建成功后才推进检查点，失败重放依赖标准入站消息与附件任务幂等键去重。

邮件附件只在消息表保存服务端生成的 IMAP UID 引用和安全元数据，不保存原始字节。附件创建后立即进入既有 `message_download_attachment` 链路：Messaging 将 EMAIL 引用解析为 `STREAM`，按 UIDVALIDITY、UID 和附件序号重新打开只读 IMAP 流，并经 Download Ingestion 下载及资产登记。凭据、Message-ID 和邮件内容均不会进入 Scheduler 参数或任务结果。启用这一旁路不会停止或修改远程 `/opt/code/MsgService` 的监听器。

历史消息使用 `message_migrate_history` 1.1.0 即时任务迁移。脚本从独立旧服务适配器首屏冻结高水位，随后分页读取同一批脱敏记录，并在每页校验来源条目数和集合摘要不变；通过内部批次接口写入 `inbound_message` 和 `inbound_history_migration` 审计表。dry-run 不写库，正式导入按来源系统与旧消息标识幂等，并校验载荷摘要。历史导入不会生成 `MessageReceived` Outbox，避免重放实时自动化规则。独立 MsgService 快照适配器已实现且装载、导出默认关闭；实际 SQLite 只读映射和生产一致备份演练已经完成。发件归档支持 `ARCHIVED` 与 `MISSING` 附件证据：前者必须验证大小和 SHA-256，后者只用于明确记录源端文件已经不存在，不能作为可下载附件使用。

正式演练后使用 `service/scripts/messaging_cutover_gate.py` 校验 dry-run、正式导入、同键重放和目标侧 reconciliation 报告。来源与目标使用相同的长度前缀集合摘要协议；门禁只输出迁移键、数量和错误码，不输出高水位、消息身份、正文或摘要，也不会连接数据库或修改开关。

## 实施要求

- 使用 NapCat 生产副本联调 `onebot-connector-service`；继续扩展 Telegram provider adapter。所有 Connector 必须实现 `PUBLIC_URL/STREAM` 契约及相同的字节上限。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
