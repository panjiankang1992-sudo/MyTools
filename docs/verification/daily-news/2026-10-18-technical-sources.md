# 每日新闻速报：第二批技术来源验证

日期：2026-10-18。范围：N06/N07，以及采样覆盖、持久化冷却、已观测撤稿和旧数据兼容。实施说明见 [服务 README](../../../service/news-digest-service/README.md)，接口见 [内容 API](../../../service/contracts/news-digest-v1.md)，任务状态见 [实施计划](../../design/2026-10-18-daily-news-implementation-plan.md)。

## 1. 结果

| 项目 | 结果 | 边界 |
| --- | --- | --- |
| Java 21 全量离线测试 | 84 项，80 通过，4 默认跳过，0 失败/错误 | 跳过项为 RSS 实网 1 项及技术实网 3 项 |
| 三类技术 API 实网 | GitHub/HN/DEV 各访问 3 条、接受 3 条、失败 0 条 | 本机匿名、元数据只读；未验证执行节点、详情授权或长期稳定性 |
| compile / package | 通过 | 独立 Maven 模块，未加入根构建 |
| H2 V1→V2 | 带既有文章与日报升级通过 | 验证排名时间回填，旧 JSON 不改写，重复迁移不重复执行 |
| 文件库重启 | 通过 | 新 Spring 上下文恢复文章、日报、GitHub 快照、来源/平台冷却 |
| 真实 MySQL | 未验证 | H2 不能代替 MySQL 约束、并发、DDL 失败恢复验证 |
| 连续七日采样 | 未执行 | 29 个固定时钟样本只证明算法，不证明运营覆盖 |
| 生产部署/定时/消息发送 | 未执行 | 本批没有新增真实投递入口 |

机器可读汇总见 [technical-sources-test-summary.json](technical-sources-test-summary.json)。第一批记录保持不变，本批没有修改 Gateway；其 JsonNode 转发可保留新增字段。

## 2. 自动化覆盖

| 测试 | 重点 |
| --- | --- |
| TechnicalCollectorsTest | HN 类型/撤稿/日期过滤；不跟随外链；DEV 原始发布时间与详情身份匹配；元数据模式不请求详情；错误 JSON、限额、固定官方入口、令牌配置脱敏、Retry-After |
| GithubTrendCalculatorTest | 冷启动 null 增量、负增长、29 次连续样本、真实 168—174 小时端点、缺基线、缺测、过期、排除未来观测 |
| TechnicalPersistenceTest | 正常冷却、429 HTTP 头、平台共享限流、启停不清空冷却、桶首次写入、旧租约拒绝、快照与文章事务回滚、改名/离榜跟踪、撤稿与恢复、旧日报不变、禁止手工伪造技术指标 |
| TechnicalMigrationTest | 先建立 V1 并写入旧格式来源/文章/日报，再迁移 V2；回填排名时间、旧数据可读且原 JSON 不变 |
| RestartPersistenceTest | 文件库跨应用上下文重启后，日报、证据、快照及限流冷却保持不变 |
| DigestEngineTest | 保持四类 20/10 与时间窗回归；HN 讨论不作为额外独立出版者，正文引用指向 HN 实际讨论页 |
| 原有测试组 | 鉴权、owner 隔离、RSS/Atom、SSRF、XML 实体、幂等与并发回归 |

测试编译使用 `-Xlint:all -Werror`，没有 Java 编译告警。运行时仍有既有 Spring 测试栈的动态 Java agent 提示及 Flyway 对 H2 2.2.224 的兼容提示；这不等于真实 MySQL 已验证，也没有通过降级数据库版本隐藏这些提示。Surefire 会将主动跳过的实网测试打印为 WARNING。

## 3. 复现命令

本机默认 Java 版本不用于此模块，命令显式指定 Java 21：

~~~bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/news-digest-service/pom.xml test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/news-digest-service/pom.xml test -Dtest=LiveTechnicalSourcesTest -Dnews.liveTechProbe=true
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/news-digest-service/pom.xml compile package -DskipTests
~~~

实网测试在 2026-10-18 12:53（Asia/Shanghai）完成；全量离线回归在 12:58 完成。两者分别记录，不能把默认跳过当成真实通过。

~~~text
TECH_PROBE provider=DEV visited=3 accepted=3 skipped=0 failed=0
TECH_PROBE provider=GITHUB visited=3 accepted=3 skipped=0 failed=0
TECH_PROBE provider=HACKER_NEWS visited=3 accepted=3 skipped=0 failed=0
~~~

实网使用生产 `HttpFeedTransport`、JSON 解析器和三种适配器；itemLimit=3、detailLimit=0、trackedLimit=0、无凭据。没有建立数据库连接、没有写快照、没有抓正文或发送消息。完整正文的可用性与默认预算下的吞吐并未由该测试覆盖。

## 4. 实施判断与尚存限制

- GitHub 搜索按累计星数发现候选，不把搜索结果称作官方周榜；排名只使用质量合格的净星数变化。冷启动可显示项目，但不给累计星数热度分，也不把观测时间展示为项目发布时间。
- 六小时桶保留首次值；允许的基线窗口意味着实际净变化跨度为 168—174 小时。缺测、过期、负增长均显式表达。跟踪最多 30 个旧仓库，有意保留较早进入的观察队列；广度和动态轮换仍可后续优化。
- HN 当前榜单与 DEV 第一页都是抽样。HN 外链正文未抓取；DEV 详情失败回退到已取得的简介，不补造文字。
- 已观测到的 HN 删除/死亡、GitHub 归档/私有等排除状态只影响后续候选。HN 脱离当前抽样列表、GitHub 404、DEV 删除等仍未全面复核。
- 上游限流整轮失败，不提交半轮快照；按 Retry-After/reset 计算有界冷却，至少一分钟、最多一天。没有自动重试风暴；长于一天的上游指令需要后续扩展人工停用或更长等待策略。
- 单轮循环预算 75 秒，加最后一个已开始请求的传输器预算；操作系统 DNS 解析不受总读取预算保证。租约到期会拒绝旧采集提交，生产仍需网络出口限制。
- N08 地区、N09/N10 完整聚类与多样性、N14/N15 调度订阅及消息渠道仍待实施。当前真实预览继续标记地区缺口，不能宣传四类完整日报已上线。

## 5. 上游口径复核

适配器字段依据官方 [HN API](https://github.com/HackerNews/API)、[Forem API v1](https://developers.forem.com/api/v1)、[GitHub Search](https://docs.github.com/en/rest/search/search)、[仓库 API](https://docs.github.com/en/rest/repos/repos) 和 [GitHub 限流说明](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)。这些文档说明接口与字段，不能代替各来源正文使用范围的确认；本批示例默认停用且只开放元数据。
