# Task Executor Service 详细设计

## 1. 职责

Executor 是部署在执行节点上的执行面，只运行 Scheduler 下发的已发布脚本包，负责下载与校验脚本、准备工作目录、注入短期令牌、运行进程、采集日志、心跳续租、超时、取消和清理。

## 2. 脚本包规范

```text
script-package/
├── manifest.yaml
├── scripts/
│   ├── run.sh
│   └── main.py
├── requirements.lock
└── checksums.sha256
```

`manifest.yaml` 声明名称、版本、入口、运行时、参数 Schema、需要的能力、允许的网络目标、数据库权限配置名、资源限制和输出 Schema。脚本包必须签名并按内容哈希缓存，任务定义只引用不可变版本。

## 3. 脚本运行协议

Executor 向每次步骤创建独立工作目录，并注入：

- `TASK_CONTEXT_FILE`：任务、步骤、业务关联和参数 JSON 文件路径。
- `TASK_RESULT_FILE`：脚本原子写入结构化结果的位置。
- `TASK_API_URL`：Scheduler 内部 API。
- `TASK_LEASE_TOKEN_FILE`：执行租约令牌文件，不直接放环境变量。
- `TASK_WORK_DIR`、`TASK_EXECUTION_ID`、`TASK_EXECUTOR_NODE_AFFINITY`。

脚本退出码 `0` 表示成功，非零表示失败；取消建议返回约定退出码，但最终状态以 Executor 收到的取消状态为准。stdout/stderr 仅用于日志，业务结果必须写结果文件或调用 SDK。

## 4. Task SDK

提供 Shell CLI 和 Python SDK：

```text
taskctl progress --percent 40 --message downloading
taskctl child create --task download_http_asset --params params.json --idempotency-key key
taskctl child wait --id child-id --timeout 600
taskctl status --id child-id
taskctl cancel --id child-id
taskctl checkpoint put --file checkpoint.json
taskctl heartbeat
```

Python SDK 提供等价方法，并自动携带令牌、重试安全的 GET 和幂等创建请求。脚本不得直接修改 Scheduler 数据库。

## 5. 数据库 DML

支持两种模式。

### 模式 A：调用领域服务 API，默认模式

适用于用户、权限、订单式状态、资产发布、媒体状态等包含领域不变量的写操作。API 负责鉴权、校验、事务、Outbox 和审计。

### 模式 B：脚本直接 DML

适用于同服务批处理、索引、扫描结果批量写入、迁移和数据修复。约束：

- 数据库凭据通过短期凭据代理或只读 Secret 文件注入。
- 每个脚本只获得声明的 schema、表和 `SELECT/INSERT/UPDATE/DELETE` 权限。
- 禁止 DDL、授权管理、跨 schema 写和本地文件导入。
- 参数化 SQL，禁止把任务参数拼入 SQL 字符串。
- 每批次短事务，禁止长事务覆盖网络调用。
- 写入 `task_instance_id`、`step_execution_id` 和幂等键。
- 任务取消时回滚当前事务；已提交批次由补偿步骤处理。
- SQL 摘要、影响行数和事务耗时进入审计，敏感值不记录。

推荐提供 `dbexec` 包装器，根据脚本清单签发权限并执行参数化 SQL；生产环境不向脚本暴露管理员账号。

## 6. 子进程、超时与取消

- Executor 创建独立进程组或容器。
- 取消先发送 `SIGTERM`，宽限期后发送 `SIGKILL`。
- 递归清理所有子进程。
- 超时后执行 `ON_TIMEOUT`，取消后执行 `ON_CANCEL`，普通失败执行 `ON_FAILURE`。
- 补偿步骤使用新的隔离目录但可读取只读的前序步骤产物。

## 7. 隔离与安全

- Executor 使用非 root 账号，按任务配置 CPU、内存、进程、磁盘和文件大小限制。
- 工作目录和允许挂载使用白名单；禁止任意绝对路径。
- 网络默认拒绝，按脚本包允许的服务域名或内部服务身份开放。
- Secret 不写入日志、结果或子任务参数。
- 节点配置按脚本包注入环境变量，不向所有脚本共享同一组领域令牌或数据库账号。
- `executor_environment_contract_gate.py` 静态核对 Python 脚本显式读取的节点变量与包级映射；
  动态前缀数据库变量由对应迁移包完整列入配置，门禁与 Executor 测试共同作为发布检查。
- 普通业务调用方不能上传脚本，只能触发已发布任务。

## 8. 节点生命周期

节点启动时注册能力和脚本运行时，持续心跳。状态支持 `ONLINE`、`BUSY`、`DRAINING`、`UNHEALTHY`、`OFFLINE`、`DISABLED`。发布时先进入 DRAINING，完成现有任务后升级。

## 9. 迁移

1. 将现有 Python/Shell 命令封装为脚本包，不先改业务逻辑。
2. 引入 Task SDK 替换各自的状态表轮询。
3. 为脚本配置最小数据库账号或内部 API。
4. 增加取消信号、检查点和幂等写入。
5. 删除旧服务中常驻执行循环，只保留业务编排。

## 10. 验收

- 节点崩溃后任务可按租约重试且不重复提交业务结果。
- 取消能够终止完整进程树。
- 未授权脚本不能访问其他 schema、任务或节点文件。
- DML 审计可定位到任务、步骤和脚本版本。
- 所有脚本显式依赖的外部环境变量均存在对应的包级注入映射。
