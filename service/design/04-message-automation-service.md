# Message Automation Service 详细设计

规则名在 owner 内唯一，同时也是创建幂等身份。幂等重放必须匹配完整授权范围和动作配置，防止同名规则参数漂移后扩大消息执行权限。

## 职责

消费已经由渠道 Connector 回执并写入 Messaging 的标准化入站消息，匹配规则，提取命令、链接和附件，创建下载或其他业务任务，并请求 Messaging Service 按原渠道回复结果。本服务不得依赖 QQ、Telegram 等输入协议。

## 数据模型

- `automation_rules`：渠道、会话、发送者、匹配条件、动作。
- `automation_runs`：输入消息、规则版本、业务结果。
- `automation_actions`：稳定动作序号、私有恢复输入、外部请求标识和独立状态。
- `action_bindings`：动作到任务定义的映射。
- `automation_outbox`。

## 执行流程

1. 消费 `MessageReceived` 并通过消息 ID 去重。
2. 匹配启用规则和授权范围。
3. 为 HTTP 链接创建 `DownloadRequest`，或按标准消息部分标识创建附件下载任务。
4. 在外部调用前保存动作占位记录，再绑定任务实例关联。
5. 查询或事件触发时对账动作状态，聚合全部成功、部分失败、失败或取消。
6. 级联取消仍在运行的子动作并生成完成通知。

完成通知不增加独立通知中心。终态事件由轻量中继读取并交给 Messaging 的统一渠道路由，Messaging 再按入站消息保存的 `channelType + accountKey + conversationKey + externalMessageId` 调用 EMAIL、QQ、Telegram 或 OneBot Delivery Provider。只有对应 Provider 接受请求后才确认 Outbox，失败保留重试。后台协调器周期性查询运行中的任务并推进终态，不依赖外部查询触发。当前 QQ Connector 直连发送仅作为统一 Messaging 出站 Provider 完成前的兼容实现，不得把 QQ 分支扩散到分析、分类和任务编排代码。

## 脚本与子任务

复杂自动化可由入口脚本创建多个子任务，例如一个邮件包含多个链接时，父任务创建多个下载子任务，再创建汇总子任务。父任务应使用 `ALL_SUCCESS`、`ANY_SUCCESS` 或允许部分成功的聚合策略。

脚本只能创建 `action_bindings` 白名单内的任务，不能指定任意脚本或执行节点。

## 迁移

1. 已建立独立 schema、租户/渠道/会话/发送者授权规则、动作白名单、消息去重运行和 Outbox，并实现有界 HTTP URL 到 Download Ingestion 请求的编排。
2. 已建立默认关闭、只转发消息标识的 Messaging Outbox relay；继续让原 Bot 入口镜像产生标准消息事件。
3. 已将动作引用升级为规范化子动作记录，支持创建结果未知恢复、下载状态聚合和级联取消；兼容 JSON 引用仅作为投影保留。下载创建使用消息租户作为权威 owner，状态查询和取消均走 owner-bound 内部接口。
4. 已实现 `MESSAGE_ATTACHMENT` 白名单动作，仅持久化标准消息部分标识，并通过 owner-bound Messaging 接口创建、查询和取消附件任务。
5. 已将终态 Outbox 收口为 Messaging 的统一原消息回复接口；EMAIL 和 QQ Provider 已接入，Telegram 和 OneBot 只需补充同契约 Provider，投递失败时事件保留重试。
6. 对比新旧规则执行结果。
7. 切换创建任务入口，最后移除 DownloadBot 渠道耦合。

## 验收

- 重复消息不会重复创建下载任务。
- 未授权会话或发送者无法触发自动化。
- 多附件任务可以独立取消和汇总状态。
- 消息正文不进入跨服务事件，Automation 仅按标识通过鉴权接口读取。
