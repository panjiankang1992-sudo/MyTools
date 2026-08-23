# Task Executor Service

## 技术栈

Java 21

## 服务职责

脚本包执行、节点心跳、日志、超时、取消与受控 DML。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/06-task-executor-service.md)。

当前已实现节点注册与心跳、任务领取、执行租约续期、取消感知、脚本入口安全解析、任务上下文/结果文件、普通及场景步骤顺序执行、步骤重试和结果回传。脚本包发布目录结构：

```text
${TASK_EXECUTOR_SCRIPT_ROOT}/
└── {scriptPackage}/
    └── {scriptVersion}/
        └── {entrypoint}
```

Executor 只执行 Scheduler 下发的已配置入口，不接受调用方提交任意命令字符串。
每个步骤的结构化输出会作为下一步骤上下文中的 `stepOutputs.{stepName}` 提供，用于对账、汇总和条件处理。
节点 Secret 使用 `executor.script-environments.{scriptPackage}` 按脚本包隔离注入，任务参数和 Scheduler 数据库不保存运行密钥。
Executor 会取任务总截止时间与步骤超时的较小值；总截止时间到达时普通步骤按 `TIMED_OUT` 上报，并继续执行不受原截止时间限制、但仍有自身超时的 `ON_TIMEOUT` 场景步骤。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
