# 任务调度与执行可靠性告警处置

## 通用检查

1. 确认告警标签中的 `application`、实例和首次触发时间，不在告警或工单中复制令牌、任务参数、业务结果及 Journal 载荷。
2. 检查对应服务的 `/actuator/health` 与 `/actuator/prometheus`；生产服务默认绑定回环地址，应由同机采集器抓取，不对外网开放。
3. 在原因未确认前不要删除 Executor Journal、工作目录或 Scheduler Outbox 记录。

## TaskOutboxDeliveryStalled

- 核对 `task_outbox_oldest_age_seconds`、`task_outbox_backlog` 和 Relay 日志，确认 Webhook 是否持续返回网络错误、429 或 5xx。
- 修复接收端或网络后等待 Relay 自动退避重试；不得直接把未投递记录改成成功。
- 若事件已经进入死信，按事件标识使用受鉴权的 Outbox 人工重投接口，并保留重投审计。

## TaskOutboxDeadLettersPresent

- 查询死信的稳定错误摘要和事件类型，不导出完整业务载荷。
- 先修复永久配置错误或接收端契约，再执行有界人工重投；确认事件消费方按事件标识幂等。

## TaskExecutionClusterUnavailable

- 查看 Scheduler 健康详情中的不可用集群和受阻任务年龄。
- 检查目标集群节点是否 OFFLINE、DRAINING、心跳过期或注册能力不匹配。
- 不要通过伪造标签恢复容量；修复节点后由正常注册/心跳恢复，人工 DRAINING 节点需运维明确切回 ONLINE。

## TaskQueueWaitSustained

- 同时查看 `task_queue_depth`、`task_queue_wait_seconds`、不可用集群和运行执行数，区分容量不足、目标标签不匹配与任务退避。
- 检查最早排队任务的定义、集群、优先级和 `available_at`，不得通过直接修改任务状态跳过领取协议。
- 容量恢复后确认最早等待时间持续下降；仅增加 Executor 前先排除 Scheduler 数据库或领取循环故障。

## TaskLeaseLossesDetected

- 结合 Executor 心跳、Scheduler 恢复日志和 `task_execution` 的 `lease_lost_at` 判断是节点失联、长时间停顿还是租约参数错误。
- 确认旧执行进程树已经停止，并验证领域服务拒绝旧 fencing token；不得仅延长租约掩盖持续网络故障。
- 若同一节点持续失租，先排空节点并检查时钟、网络和负载，再由运维明确恢复 ONLINE。

## TaskExecutorJournalUnreadable

- 立即停止该节点领取；节点恢复门禁应使其保持未注册。
- 检查 Journal 目录所有权、`0700/0600` 权限、磁盘空间和 SQLite 完整性，不移动或重建原数据库。
- 保存只包含文件元数据和稳定错误码的诊断证据；需要恢复时先复制原 Journal 到受限位置，再离线处理。

## TaskExecutorJournalReplayStalled

- 查看 `executionJournal` 健康详情中的待上报数量和最早重试时间，确认 Scheduler 可用性与 429/5xx 状态。
- 等待持久化退避到期；不要通过反复重启 Executor 绕过退避。
- 若记录转为 DIAGNOSTIC，转入人工诊断流程，不删除或忽略记录。

## TaskExecutorJournalNeedsDiagnosis

- 使用默认不对 Web 暴露的 `executionjournal` 运维端点列出稳定报告标识、类型和错误码。
- 核对 Scheduler 当前执行租约与终态；只有确认错误原因已经消除后，携带报告标识和预期错误码执行原子重投。
- `REPORT_CONFLICT` 或 `EXECUTION_LEASE_LOST` 不得盲目重试，应先对账 Scheduler 中的 Step/Complete 内容。

## TaskReportRetriesSustained

- 按低基数 `type=STEP|COMPLETE` 区分重试来源，并与 Journal pending、Scheduler HTTP 状态关联检查。
- 短暂重试由 WAL 自动恢复；持续十分钟表示 Scheduler、网络或限流容量需要处理。

## 恢复确认

- 告警指标持续恢复到正常值，且 Scheduler/Executor health 均为 UP。
- Executor 必须先完成 WAL 回放再注册；禁止为了消警手工清空 Journal 或 Outbox。
- 对 Step/Complete 响应丢失场景，确认 Scheduler 返回 `replayed=true`，数据库没有重复记录或冲突终态。
