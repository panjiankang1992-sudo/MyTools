# Task Executor Service

## 技术栈

Java 21

## 服务职责

脚本包执行、节点心跳、日志、超时、取消与受控 DML。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/06-task-executor-service.md)。

当前已实现节点注册与心跳、任务领取、执行租约续期、取消感知、脚本入口安全解析、任务上下文/结果文件、普通及场景步骤顺序执行、步骤重试和结果回传。Python SDK 统一提供任务控制、Storage Gateway 与 Asset Registry 访问、有边界的电子书文本和归档读取能力，以及隔离的 Reader Runtime 客户端，避免各脚本重复实现鉴权和安全校验。脚本包发布目录结构：

```text
${TASK_EXECUTOR_SCRIPT_ROOT}/
└── {scriptPackage}/
    └── {scriptVersion}/
        └── {entrypoint}
```

各领域服务继续拥有自己的 `packages/{name}/{version}` 源目录。发布前运行
`python3 service/scripts/assemble_executor_packages.py` 校验全部包；指定
`--output /path/to/new-release` 时会装配一个全新的扁平发布目录。工具核对 manifest 名称、
版本、入口、重复身份、目录穿越和符号链接，为运行文件生成逐文件 SHA-256 及统一
`package-index.json`，并排除测试与缓存目录。输出目录已存在时直接拒绝，不覆盖正在运行的
Executor；部署层验证新目录后再更新 `TASK_EXECUTOR_SCRIPT_ROOT`。

Executor 只执行 Scheduler 下发的已配置入口，不接受调用方提交任意命令字符串。
每个步骤的结构化输出会作为下一步骤上下文中的 `stepOutputs.{stepName}` 提供，用于对账、汇总和条件处理。
节点 Secret 使用 `executor.script-environments.{scriptPackage}` 按脚本包隔离注入，任务参数和 Scheduler 数据库不保存运行密钥。
Executor 会取任务总截止时间与步骤超时的较小值；总截止时间到达时普通步骤按 `TIMED_OUT` 上报，并继续执行不受原截止时间限制、但仍有自身超时的 `ON_TIMEOUT` 场景步骤。

`python3 service/scripts/executor_environment_contract_gate.py` 会静态扫描全部 Python 任务包对
`os.getenv` 和 `os.environ` 的显式引用，并与上述包级环境映射核对。当前 79 个脚本包已通过
门禁；新增包若遗漏 Storage、领域 API、只读迁移库或并发限制配置，会在提交前直接失败，
避免任务领取后才暴露缺失令牌。

Python 脚本通过 `TASK_EXECUTOR_PYTHON_SDK_ROOT` 自动获得 `mytools_task_sdk`。共享 SDK 已集中提供任务控制和 Storage Gateway 流式读写客户端，领域脚本不再复制上传、下载、授权和 `storage://` URI 解析逻辑。父任务可在有效执行租约内创建直接子任务、等待或取消子任务，并读取当前任务或直接子任务的步骤结果；不能越级读取其他任务。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
