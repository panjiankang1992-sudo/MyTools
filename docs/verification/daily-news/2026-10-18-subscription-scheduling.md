# 每日新闻第四批验证：内容订阅与任务编排

日期：2026-10-18。代码工作区验证；没有生产部署、真实节点任务派发或邮件/飞书/QQ 发送。

## 本批结果

| 验证 | 结果 | 范围 |
| --- | --- | --- |
| News 全部默认测试 | 120 个，116 通过、4 个可选实时来源测试跳过 | 包含原内容、趋势、地区和新增订阅链路。 |
| 新增调度集成测试 | 18/18 通过 | 并发扫描、改配置不重复、owner 隔离、停机恢复、12 小时边界、响应丢失、取消、队列租约接管、发布回滚、旧 fence/错误节点、原任务 redrive、回调先于创建响应。 |
| 时间规则与 Scheduler 客户端 | 5/5 通过 | DST 缺口/重叠、日界线、固定请求范围、429/5xx/永久拒绝分类、授权租约传递。 |
| Gateway 新闻及认证回归 | 23/23 通过 | 伪造 owner、额外 JSON 字段、版本冲突、生成运行查询与取消路由。 |
| Scheduler 定向验证 | 10/10 通过 | 新闻域双向守卫、在线租约、取消/过期/新 fence 拒绝、V173 种子、原公开 PDF 守卫回归。 |
| 生成 Python 包 | 7/7 通过 | 请求身份、禁代理/跳转、输入/回执边界、16 KiB 上限、原子结果文件。 |
| 三个 Java 模块打包 | 通过 | Java 21；News 维持 -Xlint:all -Werror。 |
| 包装配 | 150 个版本包通过 | 新增 news_generate_edition/1.0.0；只校验，未发布到实际节点。 |
| 全仓库包环境门禁 | 未通过，新闻包通过 | 既有其他包仍有缺项，见下方。 |

机器可读结果与本次构建、包文件摘要见 [subscription-scheduling-test-summary.json](subscription-scheduling-test-summary.json)。

## 状态恢复与一致性证据

1. 八个并发扫描仅写一条每日运行和一条 Outbox；唯一键不含订阅 revision，修改同日配置不会生成第二条。
2. 网络中断后的创建重试使用同一个 runId 和 news:{runId}；取消未知创建结果时先用原键收敛任务身份，再持续取消直至终态。
3. 出站租约到期后另一持有者接手；旧持有者的整个对账事务回滚，不得覆盖 taskId 或新队列状态。
4. Scheduler 授权 SQL 同时核验执行租约、RUNNING 状态、取消字段和更高 fence；News 匹配冻结任务/节点范围，发布前重复在线校验。授权中途失败无日报写入，但地区绑定保留供重试。
5. 日报写入后模拟异常，日报和 READY 一起回滚；停用与发布通过同一订阅/运行锁顺序串行。
6. 原有文件数据库重启测试扩展为关闭第一应用上下文、启动第二上下文，确认订阅、运行、队列短租约与旧内容/地区仍可恢复；旧租约到期后可接手。
7. 内容测试使用固定材料或空榜，不将测试地区/新闻视作真实证据。READY 与 DEGRADED 可同时成立，不伪造满榜。

## 迁移和环境限制

- News 已在带旧来源、文章、日报数据的数据库中顺序验证 V1→V2→V3→V4，旧文章和日报载荷不被改写。
- 尝试运行 Scheduler TaskInstanceServiceTest 时，在原有 V141 的 MySQL JSON_SET 处失败，尚未到达本批 V173。未修改该历史迁移来伪造全量成功。
- V173 独立验证使用真实迁移至 V140 的旧表结构，再执行新增 SQL，确认集群和任务关闭、包版本固定、没有自动创建任务。该验证不等价于 V141—V173 全量 MySQL 验证。
- 本批未执行真实 MySQL 迁移、跨主机任务/租约联调、实际地区探测或连续七日采样。Scheduler 业务令牌与节点配置均没有填入生产值。
- 全仓库环境门禁仍报告 media_generate_tags、reader_analyze_audiobook_book 的 TASK_ERROR_FILE，pdf_process 的 PDF_ALLOW_UNSANDBOXED_TEST，以及 video_generate 的既有环境缺项；没有改动这些不相关包来消除结果。两个新闻包均无缺项。
- 构建没有新增 Java 编译告警。测试仍出现既有 Flyway/H2 版本兼容提示及 Mockito 动态 agent 提示；四个实时来源测试是显式跳过，不能算通过。

## 复现命令

使用 JDK 21 设置 JAVA_HOME，再运行：

~~~bash
mvn -f service/news-digest-service/pom.xml package
mvn -f service/mytools-gateway/pom.xml test -Dtest=NewsGatewayControllerTest,GatewayRequestFilterTest
mvn -f service/mytools-gateway/pom.xml package -DskipTests
mvn -f service/task-scheduler-service/pom.xml package -Dtest=NewsExecutionAuthorizationControllerTest,NewsTaskMigrationTest,PublicPdfTaskAccessTest
python3.12 -m unittest discover -s service/news-digest-service/packages/news_generate_edition/1.0.0/tests -v
python3 service/scripts/assemble_executor_packages.py --service-root service
python3 service/scripts/executor_environment_contract_gate.py --service-root service
~~~

最后一项当前预期报告上述其他包缺项；不可据此声称全仓库发布门禁通过。

## 本阶段边界与下一步

N14/N15 继续记 DOING：内容订阅与日报任务已可在受控配置下运行，但地区探测报告还没接入同一租约体系，周期采集、实际执行节点和真实 MySQL 尚未验收。生成包是待发布源码，新增 Scheduler 集群/定义及 News 自动编排默认关闭。

后续需补齐周期采集/地区编排，完成订阅和阅读页面，再接入 Messaging 目的地、邮件模板和投递对账；飞书与 QQ 在同一不可变日报上扩展。当前没有收件人绑定或消息投递，不会因本批代码自动发送。
