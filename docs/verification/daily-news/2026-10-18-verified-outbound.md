# 每日新闻第七批：已验证目标与新版投递基础

日期：2026-10-18。持续目标保持 ACTIVE。本批是投递基础交付，未将整个每日新闻能力标为完成。

## 已实现

- Messaging V18 增加独立目的地、冻结投递和尝试审计表；目的地以 AES-256-GCM 加密，并绑定所有者和渠道。
- 管理员可信验证后绑定，用户侧只看到不透明标识；客户端、执行器、管理端使用三个不同令牌。输入校验错误不会返回或默认记录原始地址和凭据。
- 请求先落库，再由短租约 relay 创建固定身份的 Scheduler 任务；丢失响应后复用同一键，调度失败不会改换身份。
- 提供方调用前由 Scheduler 在线确认执行租约、当前 fence、业务身份与取消状态。数据库独占尝试，网络调用在事务之外。统一目的地优先的锁序。
- 邮件 HTML/纯文本替代正文和每代稳定 Message-ID；飞书签名、提及标签处理、16 KiB 请求预算和 8 KiB 响应预算。
- `PROVIDER_ACCEPTED`、`FAILED`、`UNKNOWN` 分开；UNKNOWN 不自动重发；过期停止发送；已启动调用无法虚报取消成功；人工核对/风险确认重试保留审计。
- 新 `message_send_channel_message@1.0.0`、Scheduler V176 和 Executor 环境映射默认关闭，不修改旧邮件任务版本。

## 实际验证

| 验证项 | 结果 | 口径 |
| --- | --- | --- |
| Messaging 完整 Maven package | 113 通过，0 失败/错误/跳过 | 包含新投递与既有邮件、入站、附件、历史迁移回归 |
| Scheduler 定向回归 | 24 通过 | 新投递授权/迁移 5，原新闻授权/迁移 6，内部令牌过滤 13 |
| Python 新执行包 | 6 通过 | 本地 HTTP，验证上下文、租约、禁止跳转、最小结果、未知终态 |
| 编译 | 通过，无编译 warning | Java 21，`-Xlint:all`、failOnWarning；未声称测试运行时无告警 |
| 包装配 | 153 个版本，ready=true | 验证时工作区的实际包集合；不代表全部包运行环境可用 |
| 环境契约门禁 | 全局 ready=false，新包无缺项 | 既有 media_generate_tags、reader_analyze_audiobook_book、pdf_process、video_generate 缺项继续保留 |
| Git 空白检查 | 通过 | 本批跟踪文件的 diff --check |

共 143 项测试通过。没有把不同命令的重复测试累加。结构化数量和源文件摘要见 [verified-outbound-test-summary.json](verified-outbound-test-summary.json)。

运行命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/messaging-service/pom.xml -Dmaven.compiler.showWarnings=true -Dmaven.compiler.compilerArgument=-Xlint:all -Dmaven.compiler.failOnWarning=true package
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -f service/task-scheduler-service/pom.xml -Dtest=MessageOutboundExecutionControllerTest,MessageOutboundTaskMigrationTest,NewsExecutionAuthorizationControllerTest,NewsTaskMigrationTest,InternalTokenFilterTest test
/Users/pankang/.local/bin/python3.12 -m unittest discover -s service/messaging-service/tests -p 'test_channel_worker.py' -v
python3 service/scripts/assemble_executor_packages.py --output /tmp/mytools-news-outbound-packages.json
python3 service/scripts/executor_environment_contract_gate.py
```

## 重要局限与下一步

- 无真实邮件、飞书或 QQ 消息发送；SMTP 使用模拟发送器，HTTP 使用 loopback 夹具。
- Messaging V1→V18 在 H2 上通过；Scheduler 用 V140 基础加 V176 验证新增迁移，未把已知 V141 H2 JSON_SET 问题掩盖为全量通过。真实 MySQL 仍待验收。
- 测试运行时仍有既有 Flyway/H2 支持版本和 Mockito/JVM agent 提示；编译阶段未产生 warning。
- QQ 官方文档注明主动推送已在 2025-04-21 停止，本批明确拒绝 QQ 定时绑定，没有把旧会话回复当主动推送。依据：[腾讯官方文档](https://github.com/tencent-connect/bot-docs/blob/main/docs/develop/api-v2/server-inter/message/send-receive/send.md)。
- News 接收目标配置、固定渲染和分片、News 投递 Outbox、目的地节流、Gateway/页面接入、存储保留和真实联调尚未完成。目标继续执行这些事项。
- 首期绑定依赖管理员已完成的所有权核对；没有上线自助验证码绑定。验证引用应为不含敏感内容的审计编号。

实现协议：[Messaging outbound v2](../../../service/contracts/messaging-outbound-v2.md)。持续执行顺序：[目标记录](../../design/2026-10-18-daily-news-goal-progress.md)。
