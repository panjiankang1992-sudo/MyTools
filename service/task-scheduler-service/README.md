# Task Scheduler Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

任务定义、实例、步骤、父子任务、集群节点与调度控制面。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/05-task-scheduler-service.md)。

当前已经实现独立 `mytools_task` schema 的 Flyway 基础结构，以及任务定义、脚本步骤、任务实例、集群、节点和多对多节点分配接口。创建 schema：

任务执行内部协议同时支持按节点集群领取、租约续期、取消状态返回、步骤结果上报和执行完成。领取通过任务状态条件更新保证同一任务实例只产生一个有效领取者。

定时定义由持久化 `task_schedule_cursor` 驱动；Scheduler 多副本通过数据库租约抢占到期游标。支持 `IGNORE`、`RUN_ONCE`、受单轮上限保护的 `CATCH_UP` misfire 策略，以及 `ALLOW`、`SKIP`、`QUEUE`、`REPLACE` 重叠策略。任务领取会在事务锁内同时检查定义、集群和节点并发上限。

`MULTI_NODE_BROADCAST` 和 `MULTI_NODE_SHARD` 会在首次领取时把在线集群成员固化为执行目标。每个节点只领取自己的目标；分片信息位于 `parameters.taskExecutionTarget`，包含 `mode`、`index`、`count` 和 `nodeId`。目标独立租约、重试和取消，全部结束后按失败、超时、取消、成功的优先级聚合实例终态。结果查询同时返回节点和目标序号。

任务第一次进入执行态后固化 `started_at`，Scheduler 下发总截止时间。Executor 以任务剩余时间限制普通步骤，超时后仍执行独立受限的 `ON_TIMEOUT` 步骤；已开始后重新排队但长期无人领取的任务，以及多节点任务未领取的目标，由持久化截止时间扫描回收。

任务实例可以通过可选 `requiredNodeLabels` 声明最多 16 个不可变的节点标签约束。单节点领取和多节点目标展开均执行精确包含匹配；节点标签缺失或在目标固化后发生漂移时拒绝领取。相同幂等键不能改变标签约束。未设置该字段的现有调用保持原调度行为。运行脚本可使用租约作用域 API 查询自身或直接子任务的状态及步骤结果，权限检查在 Scheduler 内完成，不能通过脚本参数指定任意任务读取。

执行节点注册时可以声明 `clusterNames` 自动加入多个已存在集群。新 schema 会创建 `media` 集群以及版本化的 `media_generate_tags` 双步骤任务定义。任务完成后可通过 `GET /api/v1/task-instances/{id}/results` 查询生成结果和对账结果。

```bash
mysql -u root -p < deploy/create-schema.sql
```

运行服务前使用环境变量配置专用账号：

```text
TASK_DB_URL=jdbc:mysql://127.0.0.1:3306/mytools_task?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
TASK_DB_USERNAME=mytools_task
TASK_DB_PASSWORD=通过部署 Secret 注入
TASK_CRON_SCAN_DELAY_MS=1000
TASK_CRON_LEASE_SECONDS=30
TASK_CRON_MAX_CATCH_UP=100
TASK_DEADLINE_SCAN_DELAY_MS=1000
```

数据库账号的创建和授权由部署系统完成，不在仓库中保存生产用户名之外的凭据。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
