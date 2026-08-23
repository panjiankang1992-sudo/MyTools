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
- `MessageReceived`、`MessageDeliveryRequested`、`MessageDelivered`、`MessageDeliveryFailed`。

## 任务化操作

- `message_send_email`、`message_send_channel_message`。
- `message_download_attachment`。
- `message_backfill_history`、`message_cleanup_retention`。

步骤脚本调用 Messaging 内部 API 领取经过解析和授权的投递载荷，不能把 SMTP 密码或 Bot Token放进任务参数。

## DML 边界

- 投递状态应走内部 API或存储过程，确保状态机和 Outbox 同步更新。
- 历史归档、清理和修复脚本可使用受限 DML。
- 脚本不得直接修改渠道密钥和投递成功记录。

## 迁移

1. 已建立 provider-neutral 投递、投递尝试、标准入站消息与 Outbox schema，并实现 SMTP 原子 provider。
2. 已建立只携带 `deliveryId` 的 `message_send_email` 任务，并接入默认关闭、旧事务提交后触发的 MyTools 注册邮件旁路。
3. 迁移 DownloadBot 的 QQ/Telegram/OneBot 适配器。
4. 先双投递到审计通道，再切换真实发送。
5. 删除 MyTools SMTP 和 DownloadBot 渠道发送逻辑。

## 验收

- 同一投递幂等键最多产生一个逻辑消息。
- 渠道故障不会阻塞其他渠道。
- 敏感凭据不进入任务参数、日志或事件。
- SMTP 网络调用不占用数据库事务，状态与 Outbox 更新保持短事务原子性。
