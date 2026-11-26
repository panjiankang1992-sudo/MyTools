# 每日新闻第十四批：真实 MySQL 兼容性

日期：2026-10-18。持续目标 **ACTIVE**。已在 Ubuntu 上使用隔离的真实 MySQL 验证，未部署新闻应用或发送消息。

## 实际结果

| 范围 | 结果 |
| --- | --- |
| MySQL / JVM | MySQL 8.4.11-0ubuntu0.26.04.1，Java 21.0.12.1 |
| 真实数据库 JUnit | 6 项通过，0 失败、0 跳过、0 容器初始化失败 |
| News 完整迁移 | V1—V7，7 个文件；验证 checksum 后再次 migrate 执行 0 项 |
| Messaging 完整迁移 | V1—V20，20 个文件；验证及重复执行通过 |
| Scheduler 完整迁移 | V1—V176，176 个文件；包含此前 H2 无法完整覆盖的 JSON 迁移 |
| News 本地回归 | 189 项中 179 通过，0 失败；4 项可选联网、6 项条件 MySQL 测试在本地跳过 |
| 临时资源 | 数据库已停止、数据目录已删除、本地私有会话和凭据已删除 |

本地跳过的 6 项 MySQL 用例已在 Ubuntu 独立实际执行，不作为第二组不同测试重复计数。源码、迁移文件和运行结果摘要见[结构化证据](news-mysql-compatibility-test-summary.json)。

## 六项覆盖

1. 三个服务分别从空 schema 执行全部历史迁移，校验文件数量、Flyway 历史和重复 migrate，不跳过旧 SQL。
2. News 从 V1 保留已有日报原文、owner 和幂等键；V4 旧 Outbox 升级后保持身份并填充 EDITION 类型；升级至 V7 后仍可原键读取并拒绝外部 owner。
3. Messaging 从 V17 保留旧邮件正文与 SENT 状态；V19 冻结出站正文升级至 V20 保持不变，随后允许释放正文而保留 owner、键和摘要。Unicode 内容往返一致。
4. Scheduler 从 V172 升级，保留原邮件任务的自定义开关和超时；两个新闻任务和新版出站任务默认关闭，包版本固定，没有生成任务实例。
5. 直接使用 News `DispatchRepository`，八个线程竞争同一片段只获得一个租约；到期后可恢复，旧租约回执被拒绝，当前租约成功后不再领取。
6. 直接使用 News `RetentionRepository`，清理满足条件的日报与投递正文；UNKNOWN 相关正文、原摘要和 owner 隔离保持，检查点写为 SUCCEEDED。

这没有启动三个应用的完整 HTTP/调度链路，也没有验证真实邮件、飞书或 QQ 提供方。

## 隔离与实施证据

按当前本地 SSH 配置连接 Ubuntu。最初的 Docker 镜像拉取因网络超时失败，未创建容器；随后复用现场已安装的 MySQL 程序，使用 `--no-defaults` 和全新的临时数据、临时文件、socket、pid 路径。没有读取现有 MySQL 配置或业务数据库。

测试数据库与测试 JVM 使用独立临时 systemd 单元、非 root 身份，每个单元限制为 1 CPU / 768 MiB；文件系统仅允许写临时测试目录，网络限制为回环，数据库有 30 分钟存活上限。测试账号和实例标记为本次随机生成，凭据只写入 0600 私有文件，没有出现在命令参数或记录中。

跨网 JDBC 试运行因明显延迟主动停止，随后将相同用例移至 Ubuntu。传输材料按 SHA-256 校验，复用了 94 个内容完全相同的现有依赖，468 个测试包文件逐项核对。第一轮发现测试包中三个旧迁移文件原有 0600 权限使运行用户不可读；数量断言阻止了不完整验证。只修正临时测试包的属组和读取权限，没有改变仓库原文件权限或降低断言。最终在 Java 21 下完整执行 176 个 Scheduler 迁移。

清理前确认 Scheduler、Executor、Messaging、Gateway 四个现有服务均在运行。本次没有重启或修改这些服务，测试数据库停止后 MainPID=0、状态 inactive，临时数据和凭据已删除。

## 新增代码与复现

- `MySqlCompatibilityTest`：私有配置、回环地址、随机实例身份保护及六项真实数据库用例。
- `MySqlCompatibilityLauncher`：在无 Maven 的测试主机运行同一 JUnit 套件，严格处理跳过和初始化失败。
- POM 仅增加 test scope 的 JUnit Launcher；它不进入生产运行依赖。

本地构建和回归命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -f service/news-digest-service/pom.xml test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -f service/news-digest-service/pom.xml package -DskipTests
```

真实测试所需配置和执行方式见[发布手册](../../runbooks/daily-news-release.md#真实-mysql-兼容性测试)。

## 保留项

Flyway 9.22.3 对 MySQL 8.4 给出超出已测试 8.0 范围的运行提示；编译没有新增警告，但生产版本支持矩阵仍需处理。此次只有空库和固定旧数据，不能替代真实业务数据库备份副本、线上迁移与回退演练。实际节点配置、出口地区、来源质量、七日采样和授权渠道送达继续待验收，目标没有因此标记完成。
