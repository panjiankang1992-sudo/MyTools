# Message Automation Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

入站消息规则、动作编排与任务触发。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。已建立独立 `mytools_message_automation` schema、授权规则、动作白名单、消息唯一运行和事务 Outbox。详细设计见 [对应设计文档](../design/04-message-automation-service.md)。

Messaging Service 的默认关闭 Outbox relay 只向 `POST /internal/v1/message-events` 发送 `messageId`；Automation 再通过鉴权只读接口获取正文和标准附件部分。规则必须同时满足租户、渠道、可选会话、可选发送者和命令前缀，动作绑定允许 `HTTP_ASSET` 和 `MESSAGE_ATTACHMENT`，每条消息最多创建五个幂等业务请求。未授权消息记录为 `NO_MATCH`，同一消息标识不会再次创建动作。

每个下载动作在外部调用前先写入 `automation_action` 占位记录，保存稳定序号和私有恢复输入；Download Ingestion 返回后绑定业务请求标识。查询运行时逐项对账运行/成功/失败/取消状态，并重新计算 `RUNNING`、`SUCCEEDED`、`FAILED`、`PARTIAL_FAILED` 或 `CANCELLED` 聚合结果。创建结果未知的动作保留 `CREATING`，后续使用相同幂等键恢复；动作 API 响应不暴露保存的源 URL。`action_refs_json` 在迁移期保留为兼容投影，不再作为动作权威记录。

同一标准消息包含多个 URL 时，Automation 不再直接创建多个互不关联的完整下载请求，而是创建一个 `MESSAGE_URL_BATCH`。批次父任务为每个 X URL 创建独立解析子任务，收齐全部媒体清单后按整条消息的真实媒体总数统一决定当天目录或消息专属目录，再为每个媒体创建独立下载、发布、资产登记和标签子任务。单 URL 消息继续走原子请求，既有任务实例不回填也不改变。

附件动作只保存标准消息部分标识，使用消息 owner 创建、查询和取消 Messaging 附件任务；Provider 私有引用不会进入 Automation schema 或接口响应。

终态通知复用 `automation_outbox`。Automation 只向 Messaging 提交“回复原入站消息”，不识别 EMAIL、QQ、Telegram 或 OneBot 的发送协议。Messaging 根据入站消息保存的渠道、账户、会话和外部消息标识选择 Delivery Provider；只有 Messaging 接受请求后才标记事件已发布，失败事件保留并按批次重试。后台协调器会周期性查询运行中的任务并推进自动化状态，因此不依赖用户再次查询任务状态。

规则创建以 owner 和规则名为幂等身份，重复请求必须匹配渠道、会话、发送者、命令前缀、动作类型、动作上限、优先级和启用状态；任一字段变化均拒绝，不能静默复用旧授权规则。

内部接口：

- `POST /internal/v1/automation-rules`：创建规则及固定动作绑定。
- `POST /internal/v1/message-events`：幂等处理仅含消息标识的事件。
- `GET /internal/v1/automation-runs/by-message/{messageId}`：查询规则版本、动作引用和状态。
- `POST /internal/v1/automation-runs/{runId}/cancel`：级联取消仍在运行的下载动作。

## 实施要求

- Telegram 和 OneBot 等其他渠道只需在 Messaging 增加对应 Inbound Reply Provider，不修改规则匹配、任务创建和状态汇总代码。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
