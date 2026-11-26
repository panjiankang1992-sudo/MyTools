# 第十一批：Messaging 副本保留与历史引用状态

日期：2026-10-18。持续目标：ACTIVE。本批完成工程与本地验证，不声明生产已发布或消息真实送达。

## 已完成

- Messaging V20 默认不清理既有内容。启用后，以持久租约和有界短事务释放超过保留期、已过发送有效期、无活动 relay 租约的已知终态正文。默认正文及尝试保留 90 天，每轮最多各 250 行，定时器每分钟一次。UNKNOWN/SENDING 保留以供核对。
- 清理保留 owner、请求键、目标、载荷哈希、状态和独立严格内容身份摘要。缺省 HTML 和空 HTML 仍可区分；已清理记录不能再次发送，同键重放仍返回原记录或冲突。损坏内容导致整批回滚并记录脱敏 FAILED。
- 新管理接口只接受独立 admin 凭据，GET 只读状态/候选/检查点，POST 显式执行一批；客户端和 worker 不得访问。旧入站与邮件表不受此功能影响。
- News 新增本人日报的实时引用状态查询。范围取自已授权日报，客户端无法指定任意文章；仅返回 articleId、状态、期限及核对时刻。版本不符或元数据已删除为 UNAVAILABLE，到期或已清理为 RETENTION_EXPIRED，其余保留正文为 AVAILABLE。
- Gateway 两个路由前缀均从可信登录主体传 owner；原文、地址及内部凭据不返回。清理开关关闭或定时器尚未运行时，到期状态仍按当前策略计算。
- 网页引用区展示保留状态、截止时间和核对时刻，说明这是本地保留状态。刷新失败清除旧可用标签并提示失败，保留原日报；快速切换、旧请求失败与卸载响应不会覆盖当前结果。

## 本次实际验证

| 范围 | 结果 |
| --- | --- |
| News Java 21 严格编译及完整打包测试 | 183 项，179 通过、4 项显式真实来源测试跳过 |
| Messaging Java 21 严格编译及完整打包测试 | 127 项通过 |
| Gateway 新闻接口定向回归 | 12 项通过 |
| 网页状态测试 | 33 项通过 |
| 浏览器原阅读流程及新证据状态 | 19 + 10 项断言通过 |
| 全项目类型检查、生产构建、相关 Oxlint、阅读组件 ESLint | 通过，无代码 Lint 告警 |

合计自动测试 351 项通过、4 项跳过；另有 29 项真实浏览器断言。浏览器数据是离线夹具，包含预期的 409/503 和模拟连接中断，不代表来源或提供方真实验收。H2/Flyway 的兼容版本提示是已有测试运行库提示，不能据此声称 MySQL 已验证。

新增测试覆盖：V19→V20 保持旧正文、文件数据库关闭重开、严格内容重放、跨 owner、清理批次上限/租约、未知结果人工核对、失败回滚、权限边界、来源短保留期、到期边界、缺失/错版引用及客户端状态竞态。浏览器确认旧节选不被刷新覆盖、失败后不误标可用，以及 390/360px 无横向溢出。

## 重跑与证据

使用 Java 21，分别运行 News `mvn package`（POM 已配置 -Xlint:all/-Werror）、Messaging `mvn -Dmaven.compiler.showWarnings=true -Dmaven.compiler.compilerArgument=-Xlint:all -Dmaven.compiler.failOnWarning=true package`，以及 Gateway `mvn -Dtest=NewsGatewayControllerTest test`。各命令在对应独立服务目录执行。

网页命令和浏览器夹具说明见 `webapp/tests/news/README.md`。统计与关键源文件哈希见 [机器记录](news-evidence-retention-test-summary.json)，浏览器断言见 [结果](news-evidence-browser-results.json)。截图位于 `output/playwright/daily-news/evidence-desktop.png`、`evidence-mobile.png`。

## 保留限制

- 仍无真实 MySQL、生产迁移、接收目标、SMTP/飞书送达、节点定位或七日连续采集验收。
- QQ 官方主动推送限制未改变，本批未新增替代 QQ 通道。
- 精简身份行、未解决 UNKNOWN、地区和控制记录仍可能增长；没有声称整体存储具备容量硬上限。
- 当前证据查询只说明本地保留状态，不访问原站，不验证报道真假，不改变已保存节选；派生内容的授权期限仍应由来源准入治理。
- 清理释放内容后不能靠关闭开关恢复。Messaging 应用回退必须保留 V20 空正文兼容及严格身份摘要逻辑；不可回退到旧版读取/重放代码。

下一批推进发布清单、环境变量装配、执行包门禁和默认关闭的部署配置。目标继续 ACTIVE。
