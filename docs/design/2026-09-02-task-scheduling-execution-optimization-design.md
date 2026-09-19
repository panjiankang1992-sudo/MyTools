# 任务调度与执行服务优化设计方案（初稿）

## 1. 文档目的

本文基于当前 `task-scheduler-service`、`task-executor-service`、主应用 Sidecar 调用及已有设计文档，梳理现状、识别缺陷，并给出可渐进实施的优化方案。

本稿重点解决四类问题：

1. 调度状态与业务执行结果不一致。
2. 租约、重试、取消和超时边界不完整。
3. 调度控制面、执行面与业务服务之间缺少统一契约和安全边界。
4. 新调度平台与旧 Sidecar、本地定时任务长期并存，缺少明确收敛路径。

本文不要求一次性引入消息队列、容器沙箱或工作流引擎；第一阶段继续使用 MySQL 作为状态权威，通过协议和状态机补强获得可靠性。

## 2. 范围与非目标

### 2.1 本次范围

- 任务定义、Cron 触发、任务实例和幂等创建。
- 单节点、广播、分片任务的调度与领取。
- 执行租约、心跳、步骤执行、重试、超时、取消和补偿。
- 父子任务、结果上报、事件通知和查询审计。
- Executor 节点注册、容量管理、脚本包和工作目录。
- 主应用及领域服务向统一任务平台迁移。

### 2.2 暂不纳入

- 通用 DAG 可视化编排器。
- 用户在线编辑或上传任意脚本。
- 跨地域强一致调度。
- 第一阶段强制引入 Kubernetes Job、RabbitMQ 或 Kafka。

## 3. 当前实现梳理

### 3.1 服务职责

| 模块 | 当前职责 | 当前实现方式 |
| --- | --- | --- |
| Task Scheduler | 定义、实例、Cron、节点拓扑、领取、租约、截止时间、多节点聚合 | Spring Boot + MySQL + 轮询 |
| Task Executor | 注册、心跳、轮询领取、步骤执行、结果上报 | Spring Boot + 虚拟线程 + 外部进程 |
| Task SDK | 脚本访问任务、Storage、Asset、Reader 等服务 | Python SDK + 租约令牌文件 |
| 业务服务 | 创建任务、保存部分业务任务状态、消费任务结果 | HTTP Sidecar、Spring 事件、本地状态表并存 |
| 旧任务机制 | 本地 `@Scheduled`、内存任务、独立 Sidecar 接口 | 分散在主应用及各领域服务 |

### 3.2 当前主链路

```text
业务服务 / Cron
    │ POST /api/v1/task-instances
    ▼
Task Scheduler ──写入──> task_instance(QUEUED)
    ▲                         │
    │ claim / heartbeat      │ 按定义、集群、节点、标签和容量匹配
    │                         ▼
Task Executor <──────── task_execution(RUNNING + lease)
    │
    ├─ 创建工作目录和上下文
    ├─ 运行 NORMAL 步骤及进程内重试
    ├─ 运行 ON_FAILURE / ON_TIMEOUT / ON_CANCEL
    └─ 上报 step_execution 与 execution 终态
```

### 3.3 已具备的能力

- 任务定义和步骤版本化引用。
- 全局幂等键唯一约束。
- Cron 持久游标、短租约、Misfire 和重叠策略。
- 单节点、广播和分片执行模式。
- 定义、集群和节点三级并发上限。
- 节点标签与存储亲和性匹配。
- 执行租约、租约过期回收、任务总截止时间。
- 普通步骤、失败/超时/取消场景步骤及步骤重试。
- 脚本包发布索引和入口摘要复验。
- 工作目录、租约令牌文件、结构化上下文和结果文件。
- 父子任务的创建、等待、取消和受限查询。

### 3.4 新旧机制并存情况

当前统一平台已承载大量脚本包和领域任务，但根工程仍存在以下旧入口：

- 主应用中的 `@Scheduled` 扫描、维护、分析任务。
- Reader、Media、Registration Mail 等专用 Sidecar Client/Publisher。
- 部分服务中的内存任务状态、轮询与取消标记。
- 调用 Scheduler Gateway 的新链路与直接调用专用 Sidecar 的旧链路并存。

并存本身适合作为迁移手段，但目前缺少统一的任务接入规范、双写对账指标和退出时间表，容易演变为永久双轨。

## 4. 缺陷与风险清单

### 4.1 P0：必须优先解决

| 编号 | 缺陷 | 当前表现 | 风险 |
| --- | --- | --- | --- |
| P0-01 | Executor 终态上报无持久重试 | `complete` 调用失败仅记录日志，随后释放本地运行计数 | 脚本成功但实例最终被租约回收为超时或重新执行 |
| P0-02 | 失租后未停止执行 | 心跳异常只告警，脚本继续运行 | 同一任务可能出现两个执行者并发产生业务副作用 |
| P0-03 | Step 上报不是幂等协议 | 唯一键能阻止重复插入，但重复请求会直接失败 | 网络超时后 Executor 无法安全判断是否已落库 |
| P0-04 | Outbox 仅建表未形成闭环 | 状态变化未统一写事件，也没有投递器 | 业务服务只能轮询，终态通知丢失且难以对账 |
| P0-05 | 内部接口缺少服务身份鉴权 | Scheduler API 主要依赖网络监听地址和执行租约 | 节点注册、建任务、取消、领取等接口存在越权风险 |
| P0-06 | 参数 Schema/结果 Schema 未在运行时强校验 | 定义保存了 Schema，创建与完成链路未统一验证 | 错误参数进入队列后才失败，结果契约漂移无法提前发现 |
| P0-07 | 业务幂等责任未形成强约束 | 调度仅保证领取租约，不可能保证外部副作用 exactly-once | 租约恢复和步骤重试可能重复写业务数据或重复发送消息 |

### 4.2 P1：稳定性和运维能力缺口

| 编号 | 缺陷 | 建议方向 |
| --- | --- | --- |
| P1-01 | 步骤重试无退避、抖动和错误分类 | 增加 retry policy，并区分可重试、不可重试、限流错误 |
| P1-02 | 调度轮询存在大范围候选扫描 | 使用 `FOR UPDATE SKIP LOCKED` 或原子候选领取，补齐组合索引 |
| P1-03 | 节点离线状态依赖领取时的固定 60 秒判断 | 增加节点健康扫描和 OFFLINE 状态转换，统一使用配置阈值 |
| P1-04 | Executor 仅在进程内记录待完成上报 | 增加本地执行日志和待上报队列，重启后可恢复 |
| P1-05 | 工作目录未定义保留和清理策略 | 按终态、审计周期和磁盘水位清理，失败任务保留诊断窗口 |
| P1-06 | stdout/stderr 仅落节点文件 | 增加日志分段索引和对象存储归档，Scheduler 只存索引 |
| P1-07 | 缺少统一指标和告警 | 增加排队时延、运行时长、失租、重试、失败率、节点容量指标 |
| P1-08 | 取消使用递归调用 | 改为有界批处理或递归 SQL，防止深层/宽层任务树拖长事务 |
| P1-09 | 父子约束未完整落到实现 | 落实最大深度、直接子任务数、等待策略和取消传播配置 |
| P1-10 | Cron 扫描逐定义处理 | 随定义量增长改为按到期游标分页抢占，避免全表式扫描 |
| P1-11 | 补偿步骤结果不影响最终判定且缺少告警模型 | 单独记录补偿状态，并允许配置 `compensation_required` |
| P1-12 | 进程资源隔离不完整 | 增加进程组、资源上限、磁盘限额、网络白名单和非 root 验收 |

### 4.3 P2：模型完整性和演进能力

- `TaskStatus` 中规划了 `CREATED`、`DISPATCHING`、`RETRY_WAIT`、`WAITING_CHILDREN` 等状态，但当前核心路径主要使用 `QUEUED/RUNNING/CANCELLING/终态`，文档模型与实现模型不一致。
- 步骤重试全部在同一 `task_execution` 内完成，缺少 `next_retry_at`，重试等待会占用 Executor 槽位。
- `task_definition` 与步骤虽然通过版本关联，但缺少明确的发布态，例如 `DRAFT/PUBLISHED/RETIRED`，也缺少发布时脚本包可用性检查。
- 当前幂等键全局唯一，简单但不利于多租户和长期归档；建议升级为命名空间唯一键。
- 节点注册以名称复用节点标识，但节点实例切换、旧租约隔离和部署批次信息需要更明确的 fencing 语义。
- 缺少任务事件时间线，问题定位需要跨多张表和节点日志拼接。
- 缺少人工干预模型，例如重跑失败步骤、从检查点恢复、强制终止、隔离坏节点和任务死信。

## 5. 目标设计原则

1. **MySQL 是状态权威**：队列、租约、终态和事件均以 Scheduler 数据库为准。
2. **至少一次执行，幂等副作用**：不承诺不可实现的 exactly-once；由 fencing token、业务幂等键和条件写共同防重复。
3. **控制面与执行面解耦**：Scheduler 不运行脚本，Executor 不直接修改调度库。
4. **协议先于实现**：所有创建、领取、续租、步骤上报和终态上报都必须可幂等重试。
5. **失租即失权**：Executor 无法证明持有有效租约时，不得继续产生外部副作用。
6. **定义不可变、实例可追溯**：实例绑定发布版本、脚本摘要和调度约束快照。
7. **事件驱动通知、查询兜底**：终态通过 Outbox 通知，查询接口用于补偿和审计。
8. **渐进迁移**：旧任务按清单逐项切换，未完成对账前保留回退开关。

## 6. 优化后的总体架构

```text
                         ┌──────────────────────────────┐
业务服务 / 管理端 / Cron ─> Task Scheduler Control Plane │
                         │                              │
                         │ Definition / Instance / DAG  │
                         │ Dispatch / Lease / Deadline  │
                         │ Result / Event / Audit        │
                         └──────┬──────────────┬────────┘
                                │              │
                         MySQL 状态权威     Outbox Relay
                                │              │
                                │              └──> 领域服务回调/事件总线
                                │
                     claim / renew / report（幂等）
                                │
                  ┌─────────────▼────────────────┐
                  │ Task Executor Data Plane      │
                  │                               │
                  │ Local WAL / Package Verify    │
                  │ Process Sandbox / Log Upload  │
                  │ Lease Guard / Result Reporter │
                  └─────────────┬────────────────┘
                                │
                     带 fencing token 的调用
                                │
               ┌────────────────▼─────────────────┐
               │ 领域服务 / Storage / Connector   │
               │ 幂等写、条件更新、审计、Outbox   │
               └──────────────────────────────────┘
```

第一阶段 Outbox Relay 可直接使用数据库轮询加 HTTP Webhook，不要求立即引入消息中间件。后续需要提高吞吐或增加订阅者时，再替换为 Kafka/RabbitMQ；任务状态表保持权威不变。

## 7. 核心模型优化

### 7.1 定义与发布

在现有 `task_definition` 基础上增加：

- `lifecycle_status`：`DRAFT/PUBLISHED/RETIRED`。
- `definition_digest`：定义、步骤及脚本引用的稳定摘要。
- `published_at/published_by`。
- `default_retry_policy_json`。
- `cancel_policy` 和 `child_aggregation_policy_json`。

发布动作必须原子完成以下校验：

1. 参数和结果 Schema 合法。
2. 至少存在一个 NORMAL 步骤。
3. 步骤顺序、超时和失败策略合法。
4. 脚本包版本、摘要和所需能力已经登记。
5. 定义版本发布后不可修改，只能创建新版本。

### 7.2 实例、执行与尝试

建议明确四层对象：

```text
task_instance
  └─ task_execution             一次节点领取，带 fencing token
       └─ step_execution        一个逻辑步骤
            └─ step_attempt     一次实际进程运行
```

如果短期不拆 `step_attempt` 表，可继续复用当前 `step_execution`，但字段和 API 语义必须明确为“步骤尝试”。

`task_instance` 建议增加：

- `tenant_id` 或 `owner_scope`。
- `idempotency_namespace`、`idempotency_key`，组合唯一。
- `definition_digest` 和 `script_release_digest` 快照。
- `queued_at/started_at/finished_at/deadline_at`。
- `terminal_reason_code`、`terminal_message`。
- `revision`，用于乐观并发控制。
- `result_json`，保存通过 Schema 校验后的聚合结果。

`task_execution` 建议增加：

- 单调递增 `fencing_token`，每次重新领取必须增大。
- `claim_request_id`，保证领取接口重试安全。
- `last_heartbeat_at`、`lease_lost_at`。
- `executor_instance_id`、`executor_release`。
- `completion_request_id`，保证终态上报幂等。

### 7.3 推荐状态机

任务实例只保留对外有价值的状态：

```text
QUEUED -> RUNNING -> SUCCEEDED
   │         ├────> FAILED
   │         ├────> TIMED_OUT
   │         └────> CANCELLING -> CANCELLED
   └───────────────────────────> CANCELLED / TIMED_OUT
```

重试等待属于执行尝试，不应让实例退回模糊的 `QUEUED`：

- 实例保持 `RUNNING`。
- 当前执行变为 `LOST/FAILED/TIMED_OUT`。
- 创建新的 `task_execution` 或把目标置为 `RETRY_WAIT`，记录 `next_retry_at`。
- 到期后再进入可领取状态。

终态必须单向不可逆。所有转换使用 `revision` 或明确的来源状态条件更新。

## 8. 调度与领取优化

### 8.1 原子领取

将“查询候选列表 + 逐条条件更新”收敛为短事务：

1. 按集群、标签、优先级、可运行时间筛选少量候选。
2. 使用 `FOR UPDATE SKIP LOCKED` 锁定候选。
3. 在同一事务内复核定义、集群和节点容量。
4. 创建 execution、生成 fencing token 并更新实例/目标状态。
5. 提交后返回完整领取快照。

领取请求携带 `claimRequestId`。如果响应丢失，Executor 使用同一请求重试，Scheduler 返回同一 execution，不能再领取第二个任务。

### 8.2 公平性与背压

- 排序维度：`priority DESC, available_at ASC, created_at ASC`。
- 增加按租户/任务定义的队列上限。
- 为连续空轮询增加 100ms 到数秒的指数退避和随机抖动。
- Executor 一次可按剩余槽位批量领取，但 Scheduler 必须以数据库容量为准。
- 对长期无法匹配节点的任务生成 `NO_ELIGIBLE_NODE` 告警，而不是无限静默排队。

### 8.3 Cron

- 继续保留持久化游标和定义级租约。
- 改为直接扫描 `next_fire_at <= now` 的游标并分页抢占，避免每轮遍历全部定义。
- `REPLACE` 应等待旧任务进入不可产生副作用的取消状态，或显式允许新旧短暂重叠。
- 所有 Cron 实例幂等键继续包含定义版本和计划触发时间。

## 9. 租约、失租与可靠上报

### 9.1 租约安全规则

每个执行拥有 `leaseToken + fencingToken`：

- `leaseToken` 用于 Scheduler API 鉴权。
- `fencingToken` 用于领域服务拒绝旧执行的迟到写入。
- 心跳连续失败达到 `leaseSafetyWindow`，Executor 必须先停止当前进程组，再尝试恢复通信。
- 任何步骤启动前、外部写入前均检查本地租约守卫。
- Scheduler 回收租约后，旧 token 的步骤和终态上报只能返回“已失租”，不能覆盖新执行状态。

建议默认值：租约 60 秒、心跳 10 秒、安全窗口 30 秒。具体值必须校验 `heartbeat < safetyWindow < lease`。

### 9.2 Executor 本地 WAL

Executor 在工作目录之外维护轻量本地 WAL，至少记录：

- 已领取但未完成的 execution。
- 每次步骤终态上报请求及确认结果。
- 最终完成上报请求及确认结果。
- 日志归档状态和工作目录清理状态。

流程调整为：

1. 先把待上报事件原子写入本地 WAL。
2. 调用 Scheduler 幂等上报接口。
3. 收到已确认或已存在响应后标记 ACK。
4. Executor 重启后优先恢复上报，再领取新任务。

### 9.3 幂等上报协议

步骤上报请求增加 `reportRequestId`，唯一键建议为：

```text
(execution_id, step_definition_id, attempt)
```

重复请求规则：

- 内容完全一致：返回原结果和 `replayed=true`。
- 同一键但内容不同：返回 `409 REPORT_CONFLICT`，并触发告警。

执行完成请求同样携带稳定 `completionRequestId`。重复完成必须返回当前终态，不能报普通服务异常。

## 10. 重试、超时、取消与补偿

### 10.1 重试策略

任务定义和步骤支持：

```json
{
  "maxAttempts": 3,
  "initialDelaySeconds": 5,
  "maxDelaySeconds": 300,
  "multiplier": 2.0,
  "jitter": 0.2,
  "retryableErrorCodes": ["NETWORK_ERROR", "RATE_LIMITED"],
  "nonRetryableErrorCodes": ["INVALID_PARAMETER", "PERMISSION_DENIED"]
}
```

等待重试时释放 Executor 槽位。Scheduler 通过 `available_at` 再次暴露任务，避免虚拟线程和外部进程长期占用。

### 10.2 超时

区分三类时间：

- 排队最长时间 `queueTimeout`。
- 任务总运行时间 `executionTimeout`。
- 单步骤/单尝试时间 `stepTimeout`。

实例在首次执行时固化 `deadline_at`。普通步骤取任务剩余时间和步骤超时的较小值；补偿步骤使用独立 `compensationTimeout`，但必须有全局硬上限。

### 10.3 取消

- 取消请求本身幂等并记录请求人、原因和时间。
- Scheduler 把实例置为 `CANCELLING` 后，拒绝创建新的普通执行和子任务。
- Executor 收到取消后终止整个进程组，宽限期后强杀。
- 父子取消按定义配置批量传播，不在单个长事务中递归处理。
- 无活跃执行的排队任务可以直接进入 `CANCELLED`。

### 10.4 补偿

- 补偿步骤拥有单独状态，不覆盖原始失败原因。
- 最终结果同时返回 `primaryStatus` 和 `compensationStatus`。
- 对关键任务可配置补偿失败转入人工处理队列。
- 补偿写操作也必须携带业务幂等键和 fencing token。

## 11. 业务副作用与幂等规范

调度平台提供的是至少一次执行。每个会写外部系统的脚本必须选择以下一种方式：

1. 调用领域服务幂等 API，携带 `taskInstanceId + stepName + businessKey + fencingToken`。
2. 使用带唯一键的数据库写入，并通过条件更新验证 fencing token。
3. 使用 Outbox 先落本地事务，再异步产生外部副作用。

禁止依赖“脚本只会运行一次”。消息发送、文件移动、资产发布等不可逆操作必须在领域服务侧保存幂等记录。

## 12. 安全设计

### 12.1 接口分区

- `/api/v1/**`：业务和管理 API，通过 Gateway 或服务身份调用。
- `/internal/v1/executors/**`：节点注册、领取和心跳，只允许 Executor 身份。
- `/internal/v1/executions/**`：租约、步骤和终态上报，要求节点身份与执行租约双重校验。
- `/internal/v1/task-sdk/**`：脚本 SDK，只允许当前执行作用域。

### 12.2 身份与授权

- 服务间使用 mTLS 或短期服务 JWT；迁移期可先使用轮换的内部 token。
- 节点注册不能自行声明任意高权限标签，能力和集群成员资格由服务端策略确认。
- 租约 token 只写权限为 `0600` 的文件，并缩小到单个 execution。
- 日志、结果和事件发布前进行 Secret 脱敏。
- 参数 Schema 增加敏感字段标记，敏感参数不进入普通查询响应和审计正文。

### 12.3 执行隔离

- Executor 使用非 root 账号运行。
- 每个任务使用独立进程组；支持时优先使用 systemd scope、cgroup v2 或容器。
- 限制 CPU、内存、进程数、文件大小、工作目录总量和运行时间。
- 默认禁止任意网络和绝对路径访问，按脚本清单开放。
- 脚本发布必须签名；当前 SHA-256 索引作为完整性基础保留。

## 13. Outbox 与结果通知

### 13.1 事件模型

所有关键状态转换与业务状态在同一事务写入 `task_outbox`：

- `TaskQueued`
- `TaskStarted`
- `TaskProgressChanged`
- `TaskSucceeded`
- `TaskFailed`
- `TaskTimedOut`
- `TaskCancelled`
- `TaskCompensationFailed`

事件包含：事件 ID、任务 ID、定义名和版本、业务关联、状态、结果摘要、错误码、发生时间和 trace ID。敏感参数不得进入事件。

### 13.2 投递策略

- Relay 使用 `SKIP LOCKED` 批量领取 Outbox。
- HTTP 投递必须携带事件 ID，订阅方按事件 ID 幂等消费。
- 指数退避重试，达到阈值进入 `DEAD` 状态并告警。
- 提供按任务或事件 ID 的人工重投接口。
- 业务服务仍可通过查询接口定期对账，修复通知永久失败或消费遗漏。

## 14. 可观测性与运维

### 14.1 指标

至少增加以下 Micrometer 指标：

- `task_queue_depth{definition,cluster,priority}`
- `task_queue_wait_seconds`
- `task_execution_seconds{definition,status}`
- `task_execution_retry_total{error_code}`
- `task_lease_lost_total{node}`
- `task_report_retry_total{type}`
- `task_cron_misfire_total{policy}`
- `executor_slots{node,state}`
- `executor_heartbeat_age_seconds{node}`
- `task_outbox_backlog` 和 `task_outbox_oldest_age_seconds`

### 14.2 日志与追踪

- 全链路统一 `traceId/taskInstanceId/executionId/stepName/attempt/nodeId`。
- stdout/stderr 分段上传对象存储，数据库只保留 URI、摘要、大小和保留期。
- 状态转换写 `task_event` 时间线，包含来源、旧状态、新状态、原因和操作者。
- 默认不输出完整任务参数、token、Secret 或大结果正文。

### 14.3 管理能力

管理端至少支持：

- 按状态、定义、业务 ID、节点和时间查询任务。
- 查看状态时间线、执行尝试、步骤结果和日志索引。
- 取消任务、重放 Outbox、隔离节点、DRAIN 节点。
- 从头重跑任务；后续再支持从检查点或指定步骤恢复。

## 15. 数据库与索引建议

建议新增或调整：

```text
task_instance:
  UNIQUE(idempotency_namespace, idempotency_key)
  INDEX(status, available_at, priority, created_at)
  INDEX(task_definition_id, status, created_at)
  INDEX(business_type, business_id, created_at)

task_execution:
  UNIQUE(claim_request_id)
  UNIQUE(task_instance_id, fencing_token)
  INDEX(status, lease_until)
  INDEX(node_id, status, started_at)

step_execution:
  UNIQUE(task_execution_id, step_definition_id, attempt)

task_schedule_cursor:
  INDEX(next_fire_at, lease_until)

task_outbox:
  INDEX(status, next_attempt_at, created_at)
```

JSON 大字段继续存储时要设置大小上限；日志和大结果不应进入主表。若 MySQL 版本支持，可使用原生 JSON 类型并为常用字段建立生成列索引。

## 16. API 契约调整建议

### 16.1 创建任务

```http
POST /api/v1/task-instances
Idempotency-Key: media:analysis:asset-123:v2
Authorization: Bearer <service-token>
```

返回 `201 Created`；幂等重放返回同一实例并附带 `replayed=true`。同一幂等键内容冲突返回 `409 IDEMPOTENCY_CONFLICT`。

### 16.2 领取任务

```http
POST /internal/v1/executors/{nodeId}/claims
X-Executor-Instance-Id: ...
X-Claim-Request-Id: ...
```

响应中必须包含 execution ID、lease token、fencing token、绝对 deadline、定义摘要、脚本发布摘要和步骤快照。

### 16.3 心跳

心跳请求携带当前进度、运行步骤和本地时间；响应返回：

- 新的 `leaseUntil`。
- `cancelRequested`。
- `leaseState=ACTIVE/REVOKED`。
- 可选的节点控制指令，如 `DRAIN`。

### 16.4 上报

步骤和完成上报都使用稳定 request ID，返回 `accepted/replayed/conflict/lease_lost` 四类明确结果。Executor 只对网络错误和 5xx 重试，不对契约冲突无限重试。

## 17. 分阶段实施方案

### 阶段 0：基线与冻结（1 周）

- 建立现有任务定义、脚本包、Sidecar 和 `@Scheduled` 清单。
- 统计近 7 天任务量、失败率、平均时长、最长时长和重复副作用案例。
- 冻结新增专用 Sidecar 任务入口，新增任务原则上接统一平台。
- 为当前状态机补充集成测试和 MySQL 并发测试基线。

### 阶段 1：可靠性闭环（优先，1～2 周）

- 实现步骤/终态幂等上报。
- Executor 增加本地 WAL 和重启恢复上报。
- 心跳连续失败触发租约守卫和进程终止。
- 增加 fencing token，并先在高风险领域写 API 中验证。
- 创建任务和完成任务时校验参数/结果 Schema。
- 为 Scheduler 与 Executor API 增加服务身份鉴权。

验收标准：通过“完成响应丢失、Scheduler 重启、Executor 重启、网络分区、租约过期后迟到写”故障注入。

### 阶段 2：状态机与重试治理（1～2 周）

- 引入 `available_at`、退避和错误分类。
- 重试等待释放 Executor 槽位。
- 原子领取改为 `SKIP LOCKED` 短事务。
- 完善节点 OFFLINE/DRAINING、队列超时和无可用节点告警。
- 统一父子任务深度、数量、聚合与取消传播约束。

### 阶段 3：事件与可观测性（1～2 周）

- 在状态事务中写 Outbox。
- 实现 Relay、订阅方幂等、死信和重投。
- 增加任务事件时间线、核心指标、仪表盘和告警。
- 日志分段归档并建立查询索引。

### 阶段 4：安全与执行隔离（并行推进）

- 非 root、进程组、资源限制和磁盘水位保护。
- 节点标签服务端授权、网络白名单和脚本签名。
- Secret 分包注入和日志/结果脱敏验收。

### 阶段 5：旧机制收敛（按业务逐项迁移）

每个旧任务按以下步骤迁移：

1. 定义任务契约和业务幂等键。
2. 将逻辑封装为已发布脚本包或领域 API。
3. 影子触发但禁止真实副作用，对比参数和目标集合。
4. 小流量启用统一平台，保留旧链路回退开关。
5. 对账任务量、终态、结果和副作用。
6. 观察一个完整业务周期后删除旧定时器、状态表轮询和专用 Sidecar。

优先迁移顺序建议：邮件和消息发送等高重复风险任务、文件移动/发布任务、长耗时媒体分析、Reader 搜索与维护、本地低风险扫描任务。

## 18. 测试与验收矩阵

| 场景 | 预期结果 |
| --- | --- |
| 两个 Scheduler 同时领取同一候选 | 只产生一个有效 execution |
| Claim 响应丢失后重试 | 返回同一个 execution |
| Step 上报响应丢失后重试 | 返回已存在结果，不重复插入 |
| Complete 上报响应丢失后 Executor 重启 | WAL 恢复并确认同一终态 |
| Executor 与 Scheduler 网络中断超过安全窗口 | 进程树被终止，旧执行不能再写业务状态 |
| 租约过期后任务被新节点领取 | 旧 fencing token 被领域服务拒绝 |
| Scheduler 在 Cron 创建前后崩溃 | 同一计划时间最多一个实例 |
| 取消带 1000 个子任务的父任务 | 有界处理，最终全部收敛，无长事务阻塞 |
| 补偿脚本失败 | 保留原失败原因并产生人工告警 |
| 节点磁盘接近上限 | 停止领取新任务并进入 DRAINING/UNHEALTHY |
| 错误参数或结果不符合 Schema | 在执行前或完成时明确拒绝并记录契约错误 |
| Outbox 投递重复 | 消费方只应用一次 |

## 19. 发布、回滚与兼容

- 数据库变更全部采用向前兼容的扩展式迁移，先加字段和索引，再切换代码，最后清理旧字段。
- 新旧 Executor 协议并存一个发布周期，Scheduler 根据节点协议版本返回兼容响应。
- fencing token 未覆盖全部领域服务前，不删除旧业务幂等保护。
- Outbox 启用初期只做旁路通知，不改变业务权威状态。
- 每个旧任务迁移保持独立功能开关和回退路径，禁止一次性全量切换。

## 20. 关键决策与待确认项

### 20.1 本稿建议直接确认的决策

1. 调度语义采用“至少一次执行 + 业务幂等”，不宣称 exactly-once。
2. 第一阶段继续使用 MySQL 队列，不把消息队列作为可靠性改造前置条件。
3. P0 先修可靠上报、失租停止、鉴权、Schema 校验和 Outbox 闭环。
4. Scheduler 保持控制面，领域不变量继续由领域服务负责。
5. 新任务不得再增加专用 Sidecar 协议，统一接入 Task API。

### 20.2 需要评审确认

- 服务身份方案选择 mTLS、短期 JWT，还是迁移期内部 token。
- fencing token 首批接入的领域服务范围。
- Executor 本地 WAL 使用 SQLite 还是追加日志文件；建议首选 SQLite，便于原子状态和恢复查询。
- 日志归档目标使用现有 Storage Gateway 还是独立对象存储桶。
- 任务结果和事件的默认保留周期。
- 是否需要租户级公平调度；如果当前仅单租户，可先保留字段和指标，不立即实现配额。

## 21. 最小可交付版本

若本轮只投入一个迭代，建议交付以下闭环：

1. Step/Complete 幂等上报 API。
2. Executor SQLite WAL 与重启恢复。
3. 租约安全窗口和失租终止进程树。
4. execution fencing token 及至少一个高风险业务 API 的条件写验证。
5. Task 参数和结果 Schema 校验。
6. Scheduler/Executor 内部 token 鉴权。
7. 任务终态 Outbox 写入、HTTP Relay 和失败告警。
8. 对上述能力的网络分区与进程崩溃集成测试。

完成这八项后，当前平台才具备承接更多高风险任务并逐步下线旧 Sidecar 的基础。
