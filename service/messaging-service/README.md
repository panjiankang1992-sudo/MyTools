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
- `POST /internal/v1/deliveries/{id}/execute`：Executor 触发原子 provider 调用。
- `POST /internal/v1/inbound-messages`：provider adapter 幂等写入标准入站消息。

## 实施要求

- 扩展 QQ、Telegram、OneBot provider adapter 和附件模型。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
