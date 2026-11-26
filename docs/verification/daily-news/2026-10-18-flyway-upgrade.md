# 第十六批：迁移引擎升级与旧历史兼容

持续目标 **ACTIVE**。News、Messaging、Scheduler 已升级到 Spring Boot 3.5.16 / Flyway core、mysql 11.20.3，保留 Java 21；升级后的隔离 MySQL 验证通过，新引擎日志未再出现此前 MySQL 8.4 版本提示。本批没有部署业务服务、访问业务数据库或发送消息。

## 实现与依据

- 三个服务显式锁定相同 Flyway 版本，避免 Boot 默认的旧版本或 core/mysql 混用。原 Boot 3.2 自动配置调用了新版已移除的 API，因此同步更新到 3.5 系列，并验证实际 Spring 上下文和接口测试。
- 测试替身迁移到 MockitoBean / MockitoSpyBean；Surefire 显式加载 Mockito agent，避免 Java 21 动态自挂载警告。禁用测试 JVM 的 CDS，不影响生产 JVM 参数。
- 新增独立旧引擎测试入口，在受控子 JVM 中运行 Flyway 9.22.3。三类旧历史接续升级时保留完整历史行；校验和异常时，新迁移必须停止，不进行自动 repair。
- 发布检查不仅核对业务类，还核对三个 JAR 的四个依赖文件名与内嵌版本元数据；拒绝混装、缺失及改名冒充版本。
- 修复装配命令在发布目录执行帮助时写入 `__pycache__` 的问题，新增真实子进程回归；新的发布目录在五个 CLI 帮助检查前后保持精确清单一致。

调查使用一手来源：[Boot 3.2.5 的 Flyway 自动配置](https://github.com/spring-projects/spring-boot/blob/v3.2.5/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayAutoConfiguration.java)、[Flyway 11.20.3 的 MySQL 版本检查](https://github.com/flyway/flyway/blob/flyway-11.20.3/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLDatabase.java)、[Flyway 官方仓库关于 MySQL 8.4 的版本混用问题](https://github.com/flyway/flyway/issues/4168)。具体组合是否可用以本批实际测试为依据，不把版本告警消失等同于所有业务已验收。

## 测试结果

| 范围 | 结果 | 实际覆盖 |
| --- | --- | --- |
| News | 191 项中 179 通过、12 跳过 | 全套本地回归；8 项 MySQL 用例另在 Ubuntu 执行，4 项可选来源探测本批未运行 |
| Messaging | 127 项通过 | 全套，包括旧邮件、目标、Outbox、保留和渠道行为 |
| Scheduler | 12 项通过 | 新闻/出站迁移和授权契约，Prometheus 实际 Spring 启动与 HTTP 读取 |
| 部署工具 | 16 项通过 | 版本、文件清单、包摘要、不可变装配、帮助命令不改发布目录 |
| 隔离真实 MySQL | 8 项通过，0 跳过/失败 | 完整 7/20/176 迁移、旧数据、repository、新旧引擎及校验异常 |
| 三个服务打包 | 成功 | Java 21；严格编译无新增 Lint 警告；测试入口未进入生产 JAR |

合计 342 项通过，另有 4 项可选来源测试未执行。8 项 MySQL 在本地因条件跳过，但已在 Ubuntu 实际通过；没有把它们重复计为两组不同测试。

Scheduler 的完整 H2 初始化仍在旧 V141 的 MySQL `JSON_SET` 处失败。定向启动测试使用 `-Dspring.flyway.target=140`；新任务迁移测试各自核对目标 SQL，完整历史迁移单独在真实 MySQL 执行。**本批未宣称 Scheduler 全套回归通过**，也没有为让 H2 通过而修改既有生产 SQL。

## 真实 MySQL 的八项用例

1. News、Messaging、Scheduler 从空 schema 分别完成全部历史迁移、validate 和幂等 migrate。
2. News 旧日报原文、owner、幂等键与旧 Outbox 身份升级后保持。
3. Messaging 旧邮件及冻结消息正文升级后保持，正文清理保留身份。
4. Scheduler 旧邮件任务配置保持，新新闻/出站任务默认关闭且包版本固定。
5. News 片段并发领取、租约恢复及旧回执拒绝。
6. News 保留清理保护 UNKNOWN 与活动正文，保留检查点。
7. 真正用 Flyway 9.22.3 建立 News V6、Messaging V19、Scheduler V172 历史，再由 11.20.3 校验和接续升级；旧历史完整行不变。
8. 用旧引擎建立 News V6 后故意破坏旧 checksum，新引擎必须拒绝迁移，历史数量及异常值不被自动修复。

首次新增用例误在存在待执行迁移时直接调用全范围 validate，收到 pending migration 错误。测试顺序已改为先校验旧版本范围，再 migrate 和全范围 validate；没有设置忽略 pending、跳过校验或 repair。最终八项全部通过。

数据库为临时独立 MySQL 8.4.11，JVM 为 Java 21.0.12.1，非 root、回环监听、受限资源和存活时间。509 个测试载荷文件均校验摘要；98 个新旧依赖从 Maven Central 下载到临时测试目录并与本地摘要核对，没有写入业务发布目录。旧引擎子进程无凭据参数、无生产账号，超时会强制终止。

结束后确认三个临时单元停止、数据目录与传输文件删除、本地私有会话及凭据删除。Scheduler、Executor、Messaging、Gateway 四个现有服务仍运行。新引擎测试日志的 `Flyway upgrade recommended` 计数为 0。

## 发布产物与复现

本地最终发布目录为 `/tmp/mytools-news-release-20261018-b16-final`，清单 SHA-256：

`7c043089ab9fc1c08da8b96a619a0dd4d9285f06e07bf5cbd8e9b832c1b96cac`

包含 18 个 Java 服务产物、7 个 Python 服务、153 个任务包、733 个清单文件。本批重新构建上述三个 Java 服务，其余复用既有产物，因此不等同于本批全量重建全部服务。装配后通过发布目录中的五个 CLI 帮助检查，再次验证所有文件未变化。未安装、启用或切换生产 current；第十三批旧产物不再满足当前迁移版本门禁。

主要命令使用 Java 21：

```bash
mvn -B -f service/news-digest-service/pom.xml package
mvn -B -f service/messaging-service/pom.xml test
mvn -B -f service/task-scheduler-service/pom.xml test \
  -Dtest=NewsTaskMigrationTest,NewsExecutionAuthorizationControllerTest,MessageOutboundTaskMigrationTest,MessageOutboundExecutionControllerTest,PrometheusEndpointTest \
  -Dspring.flyway.target=140
python3.12 -B -m unittest discover -s service/deploy -p 'test_verify_news_deployment.py'
python3.12 -B -m unittest discover -s service/deploy -p 'test_assemble_release.py'
```

真实测试的私有配置、旧依赖目录和隔离限制见[发布手册](../../runbooks/daily-news-release.md#真实-mysql-兼容性测试)。计数、JAR/源码摘要、现场清理与发布记录见 [结构化证据](news-flyway-upgrade-test-summary.json)。

持续目标尚未完成：容量控制、真实业务备份副本及跨服务升级/回退、实际节点定位、授权接收目标、渠道送达和七日观测仍待推进。QQ 主动推送限制仍保留。
