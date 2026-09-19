# 任务调度与执行发布验收

本手册对应收敛版实施计划，只收集发布所需证据，不扩展调度模型。所有命令从发布候选版本执行；输出中不得记录令牌、任务参数和业务载荷。

## 1. 候选版本门禁

使用 Java 21 依次执行：

```bash
mvn test
mvn package -DskipTests
mvn -f service/task-scheduler-service/pom.xml test
mvn -f service/task-scheduler-service/pom.xml package -DskipTests
mvn -f service/task-executor-service/pom.xml test
mvn -f service/task-executor-service/pom.xml package -DskipTests
python3 -m unittest discover -s service/deploy -p 'test_*.py'
git diff --check
```

记录 Git commit、构建时间、Flyway 最高版本和装配后的 `package-index.json` 摘要。测试数量允许随实现变化，以退出码和测试报告为准。

## 2. Linux Executor 验收

在目标 Linux 主机上以 Executor 的非 root 账号执行：

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_task_runtime_config.py \
  --env-file /opt/yuyutian/mytools/config/services.env \
  --require-runtime-paths

mvn -f service/task-executor-service/pom.xml test
```

验收测试必须显示 0 个 skipped。随后启动 Executor，人工制造一条 Scheduler 暂时不可达的待上报记录并重启服务，确认日志顺序为：Journal 恢复完成、节点注册完成、开始 Claim。恢复后检查：

- `executionJournal` 为 `UP`，pending 与 diagnostic 都为 0。
- 超时和取消任务没有残留子进程或孙进程。
- `TASK_EXECUTOR_REQUIRE_NON_ROOT=true` 和 `TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX=true`。
- 未配置 cgroup v2 delegation 时保持 cgroup 功能关闭并使用 `prlimit`。

## 3. 双 Scheduler 与真实 MySQL 验收

使用同一个测试 schema 启动两个 Scheduler 实例，端口和实例名不同，数据库配置相同。启动两个 Executor，并确认节点查询能看到两个在线节点。

先运行终态链路：

```bash
set -a
. /opt/yuyutian/mytools/config/services.env
set +a
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_task_execution.py \
  --scheduler-url http://127.0.0.1:23410 \
  --service-id mytools-service
```

验收器默认从 `TASK_BUSINESS_MYTOOLS_TOKEN` 读取令牌，不应把令牌放入命令行。保留输出中的 runKey 和四个任务 ID。

再执行以下五个场景并保存数据库只读查询结果：

1. 两个 Scheduler 同时领取同一批任务，单任务只有一个有效 execution。
2. 停止持有节点 Heartbeat，租约到期后由另一节点接管。
3. 重放相同请求 ID 的 Step 与 Complete，返回 replay，数据库各只有一条有效结果。
4. 接管后重放旧 lease token 或 fencing token，接口拒绝且终态不变。
5. 分别在 Claim、Step 上报和 Outbox 投递时重启一个 Scheduler，最终任务和事件状态一致。

测试 schema 必须与生产隔离；禁止直接修改状态来制造通过结果。

## 4. 监控与告警验收

先运行部署健康检查：

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_deployment.py \
  --host 127.0.0.1 \
  --skip-default-disabled
```

按 [任务可靠性告警处置](task-reliability-alerts.md) 依次制造可恢复的 Outbox 积压、Journal 待重放和 Executor 失联。每个告警都需要保存触发时间、恢复时间、指标截图或查询结果以及处置结论；演练完成后 pending、diagnostic 和 dead letter 必须归零。

## 5. 注册邮件灰度

灰度前确认数据库迁移完成、Messaging 邮件健康检查正常，并固定 routing key。按以下顺序逐次发布配置，每一步至少观察一个完整业务周期：

| 阶段 | mode | canary percent | 检查项 |
| --- | --- | --- | --- |
| 基线 | LEGACY | 0 | 旧路径发送正常 |
| 双跑 | SHADOW | 0 | 只创建审计记录，不重复发送 |
| 小流量 | CANARY | 1 至 5 | 创建率、终态、延迟、重复率、Outbox 积压 |
| 扩量 | CANARY | 逐级提升 | 每级完成对账后再提升 |
| 主路径 | PRIMARY | 0 | 新链路稳定，旧路径仍可回切 |

在小流量阶段至少执行一次回滚：把 mode 恢复为 LEGACY、percent 恢复为 0，重启根服务并确认新请求走旧路径。不得删除已经写入的 Delivery Outbox、Shadow Outbox、Scheduler task 或 Executor Journal；已有记录通过查询和对账收敛。

## 6. 验收结论

只有 Linux、真实 MySQL、运行配置、监控告警和注册邮件灰度五类证据全部齐全，才将平台可靠性改造标记完成。其他旧任务继续按业务独立迁移，不阻塞平台首发。
