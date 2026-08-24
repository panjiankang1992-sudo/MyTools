# Message Automation Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

入站消息规则、动作编排与任务触发。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。已建立独立 `mytools_message_automation` schema、授权规则、动作白名单、消息唯一运行和事务 Outbox。详细设计见 [对应设计文档](../design/04-message-automation-service.md)。

Messaging Service 的默认关闭 Outbox relay 只向 `POST /internal/v1/message-events` 发送 `messageId`；Automation 再通过鉴权只读接口获取正文。规则必须同时满足租户、渠道、可选会话、可选发送者和命令前缀，当前动作绑定仅允许 `HTTP_ASSET`，每条消息最多创建五个幂等 Download Ingestion 业务请求。未授权消息记录为 `NO_MATCH`，同一消息标识不会再次创建动作。

每个下载动作在外部调用前先写入 `automation_action` 占位记录，保存稳定序号和私有恢复输入；Download Ingestion 返回后绑定业务请求标识。查询运行时逐项对账运行/成功/失败/取消状态，并重新计算 `RUNNING`、`SUCCEEDED`、`FAILED`、`PARTIAL_FAILED` 或 `CANCELLED` 聚合结果。创建结果未知的动作保留 `CREATING`，后续使用相同幂等键恢复；动作 API 响应不暴露保存的源 URL。`action_refs_json` 在迁移期保留为兼容投影，不再作为动作权威记录。

规则创建以 owner 和规则名为幂等身份，重复请求必须匹配渠道、会话、发送者、命令前缀、动作类型、动作上限、优先级和启用状态；任一字段变化均拒绝，不能静默复用旧授权规则。

内部接口：

- `POST /internal/v1/automation-rules`：创建规则及固定动作绑定。
- `POST /internal/v1/message-events`：幂等处理仅含消息标识的事件。
- `GET /internal/v1/automation-runs/by-message/{messageId}`：查询规则版本、动作引用和状态。
- `POST /internal/v1/automation-runs/{runId}/cancel`：级联取消仍在运行的下载动作。

## 实施要求

- 继续实现 Messaging 标准附件动作和完成通知。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
