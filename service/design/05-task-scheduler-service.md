# Task Scheduler Service 详细设计

## 1. 职责

Scheduler 是任务控制面，负责任务定义、脚本步骤、Cron、实例、父子任务、集群与节点、多节点展开、租约、超时、取消、状态聚合和审计；不执行脚本和业务 DML。

## 2. 定义模型

### task_definition

包含 `name`、`description`、`task_type`、`timeout_seconds`、`cluster_id`、`cron_expression`、`cron_timezone`、`execution_mode`、`enabled`、`max_concurrency`、`overlap_policy`、`misfire_policy`、`parameter_schema`、`result_schema`、`version`。

名称正则：`^[a-z][a-z0-9_]{0,127}$`。

### task_step_definition

包含 `name`、`description`、`step_kind`、`script_package`、`script_version`、`entrypoint`、`arguments_template`、`enabled`、`timeout_seconds`、`failure_policy`、`sequence_number`。

所有步骤均为脚本，`step_kind`：

- `NORMAL`：普通顺序步骤。
- `ON_TIMEOUT`：任务或步骤超时时运行。
- `ON_FAILURE`：任务失败时运行。
- `ON_CANCEL`：取消时运行。

场景步骤允许多个并按顺序执行。`failure_policy` 支持 `IGNORE`、`FAIL_TASK`；步骤重试通过 `max_attempts` 和退避参数配置。

定义发布后生成不可变版本，实例始终引用创建时版本。

## 3. 运行模型

- `task_instance`：一次触发，保存业务关联、参数、幂等键、父实例、状态和聚合策略。
- `task_execution`：实例在某节点上的一次执行。
- `step_execution`：具体脚本步骤的一次尝试。
- `task_dependency`：父子和依赖关系。
- `task_checkpoint`：可恢复检查点。

状态：`CREATED`、`QUEUED`、`DISPATCHING`、`RUNNING`、`WAITING_CHILDREN`、`CANCELLING`、`CANCELLED`、`SUCCEEDED`、`FAILED`、`TIMED_OUT`、`RETRY_WAIT`。

## 4. 父子任务

脚本通过任务令牌调用：

```http
POST /internal/v1/task-instances/{parentId}/children
GET /internal/v1/task-instances/{id}
POST /internal/v1/task-instances/{id}/cancel
```

创建子任务必须提供 `Idempotency-Key`。默认约束：

- 最大嵌套深度 8。
- 单实例最多直接子任务 1000。
- 只有运行中的父任务可以创建子任务。
- 脚本只能查询和取消当前任务授权范围内的后代任务。
- 防止环依赖。

父任务等待策略：`ALL_SUCCESS`、`ANY_SUCCESS`、`MIN_SUCCESS_COUNT`、`MIN_SUCCESS_PERCENT`、`DO_NOT_WAIT`。父任务取消默认向未完成子任务传播，可由定义关闭。

## 5. 集群与节点

- `execution_cluster`：名称、标签、分配策略、并发上限。
- `executor_node`：实例、状态、能力、资源、心跳和并发。
- `cluster_node`：多对多关联、权重、优先级和节点级并发。

执行模式：`SINGLE_NODE`、`MULTI_NODE_BROADCAST`、`MULTI_NODE_SHARD`。调度必须匹配集群成员、在线状态、脚本运行时、能力标签、存储亲和性和剩余容量。

广播与分片实例在首次领取时生成不可变 `task_execution_target` 快照。广播目标表示每个节点执行完整参数；分片目标在任务参数中增加 `taskExecutionTarget.index/count/nodeId`，脚本据此处理互斥数据区间。每个目标拥有独立状态和重试次数，实例在全部目标结束后聚合终态。

## 6. API

- 任务定义、步骤、版本、启停和手动触发 CRUD。
- `POST /api/v1/task-instances` 创建实例。
- `GET /api/v1/task-instances/{id}` 查询。
- `POST /api/v1/task-instances/{id}/cancel` 取消。
- Executor 的领取、续租、心跳、进度、日志索引和完成接口。

## 7. Cron 与并发

- 重叠策略：`ALLOW`、`SKIP`、`QUEUE`、`REPLACE`。
- Misfire：`IGNORE`、`RUN_ONCE`、`CATCH_UP`。
- Cron 创建实例与 API 创建实例走完全相同的路径。
- 按任务、租户、集群和节点配置并发与队列上限。
- 每个定时定义持久化 `next_fire_at` 游标，Scheduler 多副本通过短租约抢占；实例幂等键包含定义版本触发时间，崩溃重放不会产生第二个实例。
- `CATCH_UP` 每轮有硬上限，超出部分保留到后续扫描，避免长时间停机后一次性压垮执行集群。
- 总超时从实例首次进入执行态开始，普通步骤取步骤超时和任务剩余时间的较小值；场景超时步骤使用自身超时，保证补偿仍有执行窗口。

## 8. 实现

- 数据库采用 MySQL，调度抢占使用短事务和版本号/跳过锁定。
- 分发可先采用数据库队列，规模增长后切换 RabbitMQ/Kafka；数据库仍是状态权威。
- Scheduler 多副本无主运行，通过数据库租约处理 Cron 分片和超时扫描。
- 所有外部状态变更写 Outbox。

## 9. 迁移

1. 建表并实现定义、实例和单节点状态机。
2. 实现 Executor 心跳、租约和脚本步骤。
3. 实现超时、取消和场景步骤。
4. 实现脚本 SDK 和父子任务。
5. 实现 Cron、多节点分片和管理后台。
6. 逐项替换 MyTools `@Scheduled` 和 DownloadBot 内部 worker 队列。

## 10. 验收

- Scheduler 多实例切换不重复分配有效租约。
- 子任务重复创建返回同一实例。
- 父子取消传播和状态聚合符合策略。
- 脚本不可越权查询无关任务。
