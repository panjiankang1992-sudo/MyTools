# Telegram Bot 无响应修复

日期：2026-10-18，Asia/Shanghai。

## 已确认原因

- 通过当前本机 SSH 配置连接 Ubuntu并验证连接，使用远端现有凭据进行只读诊断，没有输出 Bot token、用户消息正文或环境文件内容。
- Telegram connector、Messaging、代理均 active；Bot API getMe 与 getWebhookInfo 均 HTTP 200，未配置 webhook、pending_update_count=0。最近三条用户消息已经写入 Messaging，说明消息接收成功。
- `mytools-message-automation-service` inactive/dead，未加入 `mytools-services.target` 的 Wants，且 is-enabled 为 disabled。自动化规则中 Telegram 规则仍 enabled。
- Messaging 投递自动化接口遭遇连接失败；11:45 和 11:48 两条 Telegram 消息耗尽重试，Outbox 进入 DEAD。新消息未能触发下载和回复。
- 仓库部署清单对自动化服务采用显式启用策略；10 月 15 日整机重启后没有被 target 拉起，与当前状态相符。未找到明确停用审计，因此不能断言是谁停用了服务，也不把全局默认关闭策略改成默认开启。

## 修复

1. 核验 QQ 配置 pending 标记不存在，保留既有配置门禁。
2. `systemctl enable --now mytools-message-automation-service`，创建 target.wants 链接，恢复服务并持久化自启动选择。
3. 仅选择今日 Telegram 的两条 MessageReceived 死信，通过 Messaging 的精确 redrive API 重投，保留原事件 ID 与幂等语义；未直接改数据库、未重放无关历史通知。

## 真实验证

- 自动化进程启动成功，Flyway 校验通过，无新增迁移；最终 active/enabled。
- 两条重投消息及 11:55 的新入站消息均建立 automation_run，共 11 个动作，检查时均 RUNNING；没有把正在下载误记成完成。
- Telegram connector 在 11:55:43、11:56:27、11:56:27 记录三次 `/internal/v1/messages/text` HTTP 200。该接口等待 Bot API 发送成功后才返回，验证真实开始回执发送链路已恢复。
- 至少四次真实 Telegram 附件内容流返回 HTTP 200，文件下载已启动。
- 最新查询：Messaging 入站死信数为 0。

## 验证边界

- 未重启整机；自启动链接已核验，尚未通过再次开机测试。
- 此次修复恢复 Bot 消息处理与开始回执，11 个下载动作的最终完成及完成回执没有在本次检查内全部验收。
- Automation 健康检查为 DOWN，独立检查发现仍有 8 条历史 `AutomationRunCompleted` 死信，会触发 CompletionOutboxHealthIndicator。保留告警和历史记录，没有伪造健康状态，也未主动重发历史通知。
- 修复仅涉及运行配置与消息重投，没有修改程序代码，因此未运行应用构建或单元测试。

## 回退

如需恢复原停用状态，停止并 disable 自动化服务即可移除本次 target.wants 链接。已按原事件 ID 重投的消息可能已创建幂等业务任务，不应通过数据库回退或删除记录撤销。

## 12:14 完成通知故障修复

用户反馈未收到完成消息后继续关联真实任务，确认初始三条 automation_run 已因状态轮询失败变为 FAILED，对应三条完成通知因 ResourceAccessException 重试耗尽。Messaging 同期出现 HikariPool 获取连接 30 秒超时。

根因代码：MessagingRepository.mapInbound 在 JdbcTemplate 主查询的 RowMapper 中调用 findInboundParts 与 isPreAcknowledged，主查询仍持有连接，嵌套查询需借第二条连接。并发读取占满连接池后形成连接饥饿，与日志超时及自动化有限重试失败相符。

修复：RowMapper 仅映射主记录；按 ID、外部 ID 和分页读取均在主查询返回后加载附件分段与受理回执。保留原数据和分页协议，不以扩容连接池代替修复。

验证：

- 新增 InboundConnectionPoolTest，最大池容量为 1，验证按 ID、分页、分页游标查询及附件和 preAcknowledged 字段；通过。
- MessagingOutboxRetryRepositoryTest、EmailPollCheckpointRepositoryTest 与新增测试均通过。
- 生产仅替换 MessagingRepository.class，其余 JAR 条目和现有配置保留。发布到 `/opt/yuyutian/mytools/releases/messaging-pool-fix-20261018-v1`，重启 Messaging，健康 UP；有回退 drop-in。
- 对真实消息执行 12 并发、24 次内部 GET，全部成功，最长 0.013 秒。
- 仅通过 Automation 精确 redrive API 重投今日三条完成死信，未重投八条旧通知。
- 三条完成事件全部 published，dead=false；Telegram `/internal/v1/messages/text` 在 12:14:26 三次返回 HTTP 200。接口等待 Bot API 返回成功，确认完成通知已发出。
- 12:14 附件作业真实状态：13 SUCCEEDED、6 RUNNING；不把自动化运行失败等同于文件全部失败，不把尚未终态的附件写为成功。最近一批仍处理中的动作继续由既有对账器跟踪。
- Messaging 重启期间正在执行的调用可能重试；没有直接改 Scheduler 或业务任务状态。
