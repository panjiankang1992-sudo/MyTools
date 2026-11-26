# 每日新闻速报：首批实施验证

日期：2026-10-18。范围为[实施计划](../../design/2026-10-18-daily-news-implementation-plan.md)中的首批内容链路和提前接入的 Gateway 内容路由，不代表全部里程碑完成。

## 交付结果

新增独立 `service/news-digest-service`，没有加入根或服务聚合 Maven 构建。已有工作区的其他改动保持原样；共享文件仅在 Gateway ErrorCode 增加新闻错误码、GatewayRequestFilter 增加新闻前缀认证分支。Messaging、QQ、Scheduler、Executor 和生产部署配置未修改。

内容链路为：受控来源登记 → RSS/Atom 或受控证据导入 → 不可变文章版本 → 时间窗/去重/基线排名 → 前十原文节选 → 不可变日报 → 本人历史/预览读取。用户接口经过 Gateway 身份认证与 owner 注入，管理接口使用不同令牌。

## 当前验证证据

| 验证 | 结果 | 边界 |
| --- | --- | --- |
| Java 21 compile | 通过 | 新服务启用 `-Xlint:all -Werror`；无编译警告。 |
| 新服务默认 test | 55 个执行通过，0 失败/错误；1 个真实网络测试按设计跳过 | 固定 Clock、RSS/Atom、四类数量与时间窗、详情不足、来源权限、SSRF 地址策略、XXE、owner、并发幂等、迁移/事务、租约、重启恢复。 |
| 真实 RSS smoke | 1 个执行通过 | 使用 HttpFeedTransport 实际访问中国新闻网 World RSS，HTTP 200，FeedParser 解析到非空条目；不输出正文，不入数据库。 |
| Gateway 定向回归 | 20 个执行通过，0 失败/错误 | 4 个新闻路由测试与 16 个既有认证过滤器测试；伪造 owner 被忽略或拒绝，409 保留，默认关闭，两前缀认证。 |
| Java 21 package -DskipTests | 通过 | 生成 news-digest-service-0.1.0-SNAPSHOT.jar。 |
| 修改文件 diff --check | 通过 | 已有共享文件无新增空白错误。 |
| Docker/MySQL 条件 | 未具备 | 本机 Docker daemon socket 不存在；没有运行真实 MySQL 验证。 |

最终验证使用 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，JDK 21.0.11。早期发现默认 Maven 使用 JDK 26 后，已经在 Java 21 重新执行相关验证。

命令（先确保 Maven 使用 Java 21）：

~~~bash
mvn -f service/news-digest-service/pom.xml compile
mvn -f service/news-digest-service/pom.xml test
mvn -f service/news-digest-service/pom.xml test -Dnews.liveProbe=true
mvn -f service/news-digest-service/pom.xml package -DskipTests
mvn -f service/mytools-gateway/pom.xml test -Dtest=NewsGatewayControllerTest,GatewayRequestFilterTest
~~~

开启 liveProbe 的运行曾执行当时全部 55 个测试且均通过，其中含 1 个公开网络验证；之后新增路径歧义鉴权用例，并重跑最终默认测试，最终为 55 个离线执行通过、1 个网络测试跳过。统计不把跳过项当作失败或已执行通过。

运行时兼容提示仍需单独说明：当前仓库沿用的 Flyway 9.22.3 对 H2 2.2.224 提示其高于已验证的 2.2.220；Spring 测试基础设施/Mockito 在 Java 21 发出动态 agent 提示。它们不是编译或 Lint 警告，也不等同于 MySQL 验证通过；本批未变更全仓库依赖或通过隐藏日志抹去提示。默认跳过真实网络测试亦由 Surefire 输出 skipped 提示。

## 覆盖的关键失败场景

- 技术七日、新闻二十四小时，窗口左闭右开；缺失日期、截止后观测、未来稿件不会进入当前窗口。
- 最新文章版本将发布时间更正到窗口外时，不回退使用旧版本冒充新稿。
- 单一来源转载不被算成多个独立出版者；同网址跨分类只占一个名次。
- 第一名缺少证据时，保持第一名和缺失状态，不拿第十一名补成前十详情。
- METADATA_ONLY 不存储详情正文，不生成详情。
- 原文更新后，相同预览幂等键仍返回已保存快照；改 cutoff 返回 409。
- 八个同时提交的相同请求最终只留下一个逻辑日报。
- 新应用上下文重新连接文件数据库后，可读到相同日报和文章证据。
- 过期采集租约不能提交；坏订阅解析失败释放本轮租约；304 不让长期停更来源恢复 FRESH。
- 内部空令牌拒绝调用；代理令牌不能管理来源；矩阵参数、编码/非规范路径不能降级管理员鉴权。
- 来源解析拒绝 DTD/外部实体、超大文档、过多条目、脚本链接；公网策略覆盖 IPv4/IPv6 私网、共享、链路本地和转换地址。

## 尚未验证或实现

1. 实际执行节点出口 IP 定位、地区来源包、生产网络出口和跨节点重试。当前真实接口 LOCAL 明确 UNRESOLVED。
2. GitHub/HN/DEV 专用采集、七日星数增长、语义聚类、多样性配额及模型翻译。
3. 真实 MySQL 的 Flyway、隔离级别、并发/恢复验证；当前 H2 结果不可替代。
4. Scheduler、订阅、任务/投递 Outbox、个人阅读页面、目的地绑定。
5. 邮件、飞书、QQ 的真实发送与对账、退订、分片和未知投递结果处理。
6. 部署清单接入、长期保留清理、运行指标和七日影子观测。

本机公开 RSS 成功只证明此实现能够在本机抓取一个公开来源，不能推断执行节点城市、全部来源长期可用或许可范围。所有缺口已保留在实施计划中，本批没有生产部署、模型费用或真实消息发送。
