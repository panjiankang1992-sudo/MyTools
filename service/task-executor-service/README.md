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

Executor 在发布目录包含索引时会在启动阶段复验全部索引文件，并在每个步骤启动前再次核对
入口大小与 SHA-256，拒绝索引外入口、内容篡改和经父目录符号链接逃逸的文件。现有节点默认
使用 `TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX=false` 保持兼容；切换到装配发布目录后应设为
`true`，索引缺失或校验失败时节点不会启动领取任务。

Executor 只执行 Scheduler 下发的已配置入口，不接受调用方提交任意命令字符串。

## 执行日志归档

Executor 将每个步骤尝试的 stdout/stderr 切分为 8 MiB 分段，并生成
`process-log-index.json`。启用 `TASK_EXECUTOR_LOG_ARCHIVE_ENABLED` 后，成功执行目录达到保留期时，
Executor 会先使用 execution、相对日志路径及 SHA-256 派生的稳定幂等键上传到 Storage Gateway，
远端确认全部分段后才删除本地目录。上传失败只保留目录并在后续维护周期或进程重启后重试，
`executionLogArchiver` 健康项降级但不会单独阻止节点注册；磁盘水位保护仍会在积压造成空间不足时停止领取。

归档配置包括 `STORAGE_GATEWAY_URL`、`STORAGE_INTERNAL_TOKEN`、
`TASK_EXECUTOR_LOG_ARCHIVE_ROOT` 和 `TASK_EXECUTOR_LOG_ARCHIVE_PREFIX`。功能默认关闭，部署启用前必须确认
Storage Gateway 受管根容量、保留策略和内部令牌均已配置。

## 业务错误分类

脚本非零退出时可以通过 `TASK_ERROR_FILE` 写入 `task-error.json`，字段为稳定 `code`、`category`、
与类别一致的 `retryable` 及不超过 2048 字符的 `message`。Python SDK 提供
`write_task_error()` 和 `taskctl fail`；统一 Python 包装器还会自动分类 HTTP 4xx、429/5xx、网络异常、
参数错误和本地资源错误。`TRANSIENT`、`RATE_LIMITED`、`RESOURCE_EXHAUSTED`、`TIMEOUT` 可重试，
其余标准类别均为永久错误。类别与 retryable 不一致或文档非法时按
`TASK_ERROR_DOCUMENT_INVALID` 永久失败处理，脚本不能自行伪造重试策略。未生成错误文档的旧脚本继续按
`SCRIPT_EXIT_NON_ZERO` 兼容重试。
生产默认通过 `TASK_EXECUTOR_REQUIRE_NON_ROOT=true` 在 Spring 上下文刷新前校验 UID 和 JVM 用户名，
以 root 身份运行会直接拒绝启动。仅本地诊断可显式关闭强制项；此时 `executorIdentity` 健康指标
仍保持 `DOWN`，不得用于生产流量。远端 systemd 单元同时固定使用 `User=mytools` 和
`NoNewPrivileges=true`，形成部署层与应用层双重约束。
工作目录所在文件系统同时受 `TASK_EXECUTOR_DISK_MINIMUM_USABLE_BYTES` 和
`TASK_EXECUTOR_DISK_MINIMUM_USABLE_PERCENT` 双阈值保护。轮询会先重放 WAL、清理已确认的成功目录，
空间仍不足或容量不可读取时才停止领取并将节点置为 `DRAINING`；`executorDiskSpace` 健康指标
同步降级。空间恢复后需由运维显式切回 `ONLINE`，避免覆盖人工维护产生的排空状态。
Linux 节点使用 `prlimit` 为每个任务进程设置 CPU 时间、虚拟内存和单文件大小硬限制，配置分别为
`TASK_EXECUTOR_MAXIMUM_CPU_SECONDS`、`TASK_EXECUTOR_MAXIMUM_VIRTUAL_MEMORY_BYTES` 和
`TASK_EXECUTOR_MAXIMUM_FILE_BYTES`。限制参数与原始命令均通过参数数组传递，不经过 shell 展开；
启用限制但系统缺少 `prlimit` 时任务拒绝启动。该迁移期能力按进程继承，不替代 cgroup 的任务级
CPU、内存和进程数聚合配额。
Linux cgroup v2 聚合限制可通过 `TASK_EXECUTOR_CGROUP_V2_ENABLED=true` 启用，根目录由
`TASK_EXECUTOR_CGROUP_V2_ROOT` 指向预先委派给 Executor 非 root 用户的空 cgroup；内存、进程数和
CPU 配额分别由 `TASK_EXECUTOR_CGROUP_MAXIMUM_MEMORY_BYTES`、`TASK_EXECUTOR_CGROUP_MAXIMUM_PROCESSES`
和 `TASK_EXECUTOR_CGROUP_MAXIMUM_CPU_PERCENT` 控制，其中 100% 等于一个逻辑 CPU。任务命令通过固定
位置参数启动器先加入独立 cgroup 再执行，不拼接用户参数；任务结束时使用 `cgroup.kill` 或 PID
兜底清除残留进程。控制器、委派目录或限制文件不可用时 `executorCgroupV2` 健康检查降级，节点
保持未注册。该能力默认关闭，启用前必须在目标 Linux 主机完成委派和真实限额验收。
Linux 默认拒绝网络隔离可通过 `TASK_EXECUTOR_NETWORK_ISOLATION_ENABLED=true` 启用；Executor 使用
`bubblewrap --unshare-net` 为每个脚本创建独立 IP 网络命名空间，并在注册节点前实际执行探测，
工具缺失或内核禁止 user namespace 时 `executorNetworkIsolation` 健康检查降级且节点不注册。
原始命令参数保持数组传递，文件访问仍沿用非 root 用户和现有工作目录权限模型。配置中的
`allowed-domains` 目前只作为待接入策略声明：只要任一脚本包声明了非空域名清单，而透明域名网关
尚未落地，Executor 就 fail-closed，绝不退化为该脚本包完全联网。该能力默认关闭，当前阶段仅可
安全启用“所有脚本均离线”的模式。
每个步骤的结构化输出会作为下一步骤上下文中的 `stepOutputs.{stepName}` 提供，用于对账、汇总和条件处理。
节点 Secret 使用 `executor.script-environments.{scriptPackage}` 按脚本包隔离注入，任务参数和 Scheduler 数据库不保存运行密钥。
Executor 会取任务总截止时间与步骤超时的较小值；总截止时间到达时普通步骤按 `TIMED_OUT` 上报，并继续执行不受原截止时间限制、但仍有自身超时的 `ON_TIMEOUT` 场景步骤。

`python3 service/scripts/executor_environment_contract_gate.py` 会静态扫描全部 Python 任务包对
`os.getenv` 和 `os.environ` 的显式引用，并与上述包级环境映射核对。当前 79 个脚本包已通过
门禁；新增包若遗漏 Storage、领域 API、只读迁移库或并发限制配置，会在提交前直接失败，
避免任务领取后才暴露缺失令牌。

Python 脚本通过 `TASK_EXECUTOR_PYTHON_SDK_ROOT` 自动获得 `mytools_task_sdk`。共享 SDK 已集中提供任务控制和 Storage Gateway 流式读写客户端，领域脚本不再复制上传、下载、授权和 `storage://` URI 解析逻辑。父任务可在有效执行租约内创建直接子任务、等待或取消子任务，并读取当前任务或直接子任务的步骤结果；不能越级读取其他任务。

长任务可通过 `TaskContext.put_checkpoint/get_checkpoint/list_checkpoints` 保存可恢复游标。写入必须提供预期版本，SDK 会按任务、键、版本和规范化内容生成稳定请求 ID；响应丢失后重复调用会返回 `replayed=true`。命令行等价入口为：

```text
taskctl checkpoint put --key scan.cursor --value checkpoint.json --expected-version 0
taskctl checkpoint get --key scan.cursor
taskctl checkpoint list
```

检查点只允许在当前有效执行租约内访问；任务失租重领后，新执行可以读取旧值并用更高 fencing token 推进版本，旧执行不能继续读写。

本地 SQLite 上报 Journal 通过 `executor-owner.lock` 持有生命周期级操作系统文件锁，禁止两个
Executor 进程同时操作同一工作根目录。竞争失败的实例保持健康降级且不会领取任务；进程被强制
终止时锁由操作系统释放，新实例接管后优先回放未 ACK 的 Step/Complete，再开放新任务领取。
若旧进程在脚本运行期间崩溃，仅留下 `CLAIMED` 且没有任何 Complete 记录，新实例会在首次注册前
以稳定请求 ID 合成 `FAILED` Complete；已有待上报 Step 始终先于该终态回放，重复恢复不会生成新记录。
节点首次注册同样受该恢复门禁约束：Journal 校验、回放或成功目录清理任一步失败时，Executor
保持未注册并按心跳周期重试，Scheduler 不会看到一个尚未具备可靠执行能力的 ONLINE 节点。
网络错误与可重试服务端错误会把 `retry_count` 和带抖动的绝对 `next_attempt_at` 写入 SQLite；
重启后的实例继续遵守该时间，退避到期前既不重复投递也不注册节点，避免故障期间形成重启风暴。
每次 WAL 安排新的投递退避都会增加 `task.report.retry` Counter，并以 `type=STEP|COMPLETE`
区分上报类型；Actuator `/actuator/metrics/task.report.retry` 可用于告警采集。
`executionJournal` 健康详情只暴露待确认、已确认、人工诊断数量、最早重试时间和稳定错误码计数，
不返回任务载荷或租约令牌。无待办时为 `UP`，存在待回放记录时为 `OUT_OF_SERVICE`，存在协议冲突等
人工诊断记录或 SQLite 不可读时为 `DOWN`，便于区分临时恢复阻塞和本地数据故障。
人工诊断端点 ID 为 `executionjournal`，默认不在 Web exposure 列表中。仅允许在 HTTP 仍绑定
`127.0.0.1` 时由运维临时加入 exposure：读取操作返回最多 100 条报告 ID、类型、HTTP 状态、稳定
错误码和更新时间；重投操作必须同时提交报告 ID 与当前预期错误码，条件不匹配即拒绝。端点只把
记录恢复为 `PENDING`，后续仍走原有幂等回放；不提供删除或忽略操作。操作完成后应立即移除 exposure。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
