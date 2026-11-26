# 取消任务排队时限

日期：2026-10-21（Asia/Shanghai）。用户明确要求排队不应有时间限制。本次实现为全局语义调整，覆盖 X 链接、附件、普通任务及单节点、多节点派发。

## 行为

- 新任务创建时 `dispatch_deadline_at=NULL`。
- 首次领取不再根据 `dispatch_deadline_at` 过滤或拒绝。旧排队记录即使保留已过期的字段，也可以正常被领取，无需批量改库。
- 不再回收尚未启动任务的排队期限，也不再为旧空字段回填五分钟期限。
- 已启动执行仍有执行总超时、步骤超时、租约及重试限制。已经启动后重新排队的重试仍使用原执行预算。
- 节点匹配、并发容量、GPU 视频互斥、领取幂等、任务取消均保留。
- 旧 `TASK_QUEUE_DISPATCH_TIMEOUT_SECONDS` 配置只作兼容绑定，不再参与期限计算；源码默认保留值为 0。删除旧 GPU/附件排队时限辅助逻辑，避免与新全局语义混淆。
- 历史已终态超时的 13 个 X 任务不自动改回队列或重跑。

## 验证

13 项测试通过：TaskDispatchDeadlineServiceTest 9 项，GpuDispatchAdmissionTest 4 项。覆盖：

- 新任务没有排队截止时间，模拟三十天后仍 QUEUED；无排队超时事件。
- 旧任务创建三十天且截止时间已过期，仍能领取。
- 节点标签不匹配时保留排队；老排队任务可以取消。
- 单节点、多节点分页扫描和旧截止字段均不阻断正常领取。
- 并发领取与期限扫描不会因排队年龄竞争生成超时。
- 领取后执行期限从启动计算；已启动重试仍可执行超时。
- GPU 视频与标签、图片互斥继续有效；普通任务与 GPU 任务均无排队期限。

定向 H2 夹具加载真实基础迁移到 V140，以及调度守卫 V145 和执行授权 V146。它跳过无关的 MySQL 专有 JSON 数据迁移，不宣称验证完整生产迁移链。本次不新增数据库迁移。

`mvn -q -f service/task-scheduler-service/pom.xml compile`、部署脚本 Python 语法检查、git diff --check 均通过。

## 生产发布

使用当前本机 SSH 配置验证连接后，读取现行 Scheduler 启动命令。仅替换当前生产 JAR 内五个相关类，保留其余代码、既有迁移和所有启动配置。未重启 Executor，也未直接修改数据库。

发布：`/opt/yuyutian/mytools/releases/unlimited-task-queue-20261021-v1/scheduler.jar`。

首次预检发现八个真实下载执行正在运行，因此未直接中止它们。最终仅短暂重启 Scheduler，Executor 及其现有任务继续执行，通过既有租约与回报日志恢复调度通信。发布后健康 UP，Scheduler 与 Executor 均 active。

通过 Scheduler API 创建无业务副作用的 `system_executor_acceptance` 核验任务，使用随机且不匹配的节点标签保证不会执行：

- 任务标识：`ee26dff7-7a77-4696-b623-fb5bb2729d50`。
- 两秒后真实记录为 QUEUED、started_at=NULL、dispatch_deadline_at=NULL。
- 核验后通过正常 cancel API 收敛为 CANCELLED，保留审计记录。
- 生产不等待三十天；长期与旧截止字段场景由自动化测试模拟验证。

## 回退

原 JAR 保留，新 systemd drop-in 为 48 个 z 前缀的 `-unlimited-queue.conf`。移除该覆盖并 daemon-reload、restart Scheduler 可恢复旧程序；旧程序会再次按旧规则处理排队，可能使长时间排队任务超时，因此回退前应核对业务影响。新策略不删除旧任务、期限字段或事件记录。
