# 每日新闻第八批：固定渲染、投递队列和 Gateway

日期：2026-10-18。持续目标状态：ACTIVE。

## 本批交付

- News V6：接收设置版本、owner 隔离的目标选择、每日运行创建时的目标快照、固定投递批次和分片 Outbox。
- 内容日报、READY 状态和分片在一个发布事务提交。变更目标不增加旧运行的收件人，预览不发送。停用目标、取消批次及关闭内容订阅会请求取消相关投递。
- `news-delivery-v1` 渲染支持 FULL/BRIEF、HTML/纯文本、引用、覆盖不足和认证阅读地址。完整模式保留四类最多二十标题与前十可用详情；精简模式明确省略排名四至十的详情。
- 飞书分片按实际 UTF-8 JSON 字节及提及标签转义计算，保持 14 KiB 内部预算；不会截断 Unicode 或 URL。邮件正文也有有界分片。
- 丢失响应后的原身份对账、部分失败隔离、先前片段未接受时的后续等待、片段单独重试、REJECTED 原键恢复均已实现。
- Messaging 补充原键取消记录和 expectedGeneration 重试协议，防止为了取消而补发、或因重试响应丢失再次推进发送代次。
- Messaging V19 增加跨实例共享的飞书 Webhook 摘要桶，预留至少 1.1 秒启动间隔。超过十秒积压在调用提供方之前拒绝，不在数据库事务中等待。
- Gateway 增加目标、能力、投递历史、取消、重试和原键恢复入口；owner 始终来自认证主体，不能提交地址、Webhook 或 owner 覆盖字段。

## 验证证据

| 范围 | 结果 | 实际覆盖 |
| --- | --- | --- |
| News 完整 package | 159 项，155 通过、4 跳过 | 原内容/地区/编排回归，新渲染 5、投递集成 13、协议 4、磁盘重开 1 |
| Messaging 完整 package | 117 项全部通过 | 既有业务回归、原键取消、重试响应重放、配额并发与拒绝 |
| Gateway 新闻定向回归 | 10 项全部通过 | 认证 owner、额外字段拒绝、目标版本冲突、重试原代次 |
| 编译与打包 | News / Messaging 通过 | 新代码无 javac 编译告警；News 使用 -Xlint:all/-Werror，Messaging 使用 -Xlint:all/failOnWarning |
| Git 空白检查 | 通过 | 本批改动的跟踪文件 |

本轮合计 **282 通过，4 跳过，0 失败/错误**。没有重复累加中间测试。Gateway 只统计本轮实际执行的 NewsGatewayControllerTest，未沿用旧报告的其他回归数量。

News 的带数据迁移测试明确先升级至 V5，再应用 V6，并检查旧日报、旧出站状态未变。没有用调整断言掩盖旧数据变化。

新增磁盘测试真实关闭 H2 文件库并重建仓储：原分片标识、内容、哈希和未完成状态不变；旧租约有效期内不能领取，过期后恢复原片段且产生新租约；外部 owner 无法列出记录。这仍不等于真实 MySQL 故障恢复验收。

结构化计数及源文件摘要：[news-dispatch-test-summary.json](news-dispatch-test-summary.json)。

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/news-digest-service/pom.xml package
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/messaging-service/pom.xml -Dmaven.compiler.showWarnings=true -Dmaven.compiler.compilerArgument=-Xlint:all -Dmaven.compiler.failOnWarning=true package
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -f service/mytools-gateway/pom.xml -Dtest=NewsGatewayControllerTest test
```

## 局限和下一步

- 测试使用模拟提供方、MockRestServiceServer、内存和文件 H2；未发送真实消息、未部署、未验收真实 MySQL。
- 四项真实来源网络测试仍按默认配置跳过。已有 Flyway/H2 兼容版本、Mockito/JVM agent 的运行时提示仍存在；测试跳过也产生 Maven 提示，不是编译 warning。
- 网页投递目标选择、状态、确认重试和浏览器验证是下一执行项。当前页面仍为上一批阅读 UI，不把 Gateway 接通说成页面已经交付。
- 本批配额是内部启动预算，不承诺外部平台永不限流或最终已读。平台业务拒绝和 UNKNOWN 均有独立状态。
- QQ 主动推送仍受官方平台限制。真实接收目标、节点、来源准入、七日观察、保留清理和部署运维仍未完成。

协议：[News API](../../../service/contracts/news-digest-v1.md)、[Messaging v2](../../../service/contracts/messaging-outbound-v2.md)。持续目标：[执行记录](../../design/2026-10-18-daily-news-goal-progress.md)。
