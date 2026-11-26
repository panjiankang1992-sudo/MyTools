# 每日新闻第五批验证：周期采集与地区任务

日期：2026-10-18。工作区代码及离线验证；没有生产部署、实际 Scheduler/Executor 任务派发或真实消息发送。

## 本批结果

| 验证 | 结果 | 范围 |
| --- | --- | --- |
| News 全部默认测试 | 136 个，132 通过、4 个可选实时来源测试跳过 | 包含已有内容、趋势、地区、订阅及新增周期链路。 |
| 新增流水线集成测试 | 15/15 通过，已包含在 News 总数 | 真实采集服务与 H2，模拟外部 FeedTransport 和 Scheduler；覆盖竞争、重试、冷却、事务、取消、凭据、旧入口关闭。 |
| 文件数据库重启与带数据迁移 | 2/2 通过，已包含在 News 总数 | V1→V5 保留旧内容及 EDITION 队列；真正关闭/重新启动应用上下文恢复采样游标与地区作业。 |
| Scheduler 定向验证 | 11/11 通过 | 两类新闻任务身份配对、在线租约、取消/过期/旧 fence、V173/V174 关闭种子，及原 PDF 守卫。 |
| 地区 Python 包 1.1.0 | 默认 13 通过、1 个 MMDB 测试跳过；提供官方测试库后 14/14 通过 | 原探测规则及新任务身份、回执校验、回执丢失重放、新执行读取原回执。 |
| Java 打包 | News、Scheduler 均通过 | Java 21；News 使用 -Xlint:all -Werror。 |
| 包装配 | 151 个版本包通过 | 含新增地区包 1.1.0；只校验，未发布产物。 |
| 全仓库包环境门禁 | 未通过；新闻包无缺项 | 不相关的既有包仍有缺项，见下文及 JSON。 |

本批合计 **157 项通过、4 项跳过**：132 + 11 + 14；Python 两轮只计带官方测试库的结果，不能重复计算。Gateway 本批未修改/未重跑，上一批 23 项通过记录仍独立保留。机器可读结果、构建摘要和包文件摘要见 [periodic-pipeline-test-summary.json](periodic-pipeline-test-summary.json)。

## 可恢复性与身份验证证据

1. RSS 成功后按三十分钟采样，重复材料不刷新首次观测时间；六个并发轮询只完成一次实际采集。一次最多五个来源，停用来源不领取，失败及 429 不阻断其他来源。
2. 发起网络采集前先保留下次时间；旧轮询租约过期后不能提交。文件库重启后仍遵守已保留间隔，不因服务重启立刻重复抓取。原技术来源冷却和平台限流继续生效。
3. 并发地区扫描仅生成一个作业和一条 REGION 队列；EDITION 轮询不会领取地区记录，有未完成作业时不安排重叠探测。过期和取消通过原任务身份继续对账。
4. Scheduler 创建响应丢失后重放同一 news:region:{jobId}；永久拒绝进入 DEAD，修复后恢复原记录；旧出站租约提交使整个对账事务回滚。
5. 地区上报验证作业、任务、指定节点、执行 ID、最新 fence、租约余量和有效期；失效、中途授权撤销、错误类型或节点均拒绝。自动开关开启时静态报告接口不能绕过租约。
6. 报告保存和 STORED 原子提交；模拟中途异常后两者同时回滚。报告已提交但远端创建响应稍后返回时不误记 NEWS_020。日报链路的同类回调竞争也同步修复出站错误码。
7. 同执行的本地报告日志重放不刷新 observedAt；新执行先取得已提交回执，避免重复探测并覆盖原报告。STORED 和 observationStatus 分开，定位失败也能如实保存。
8. 管理查询不返回原始 IP、指纹或租约令牌；工作包只接受固定任务参数与回执字段，拒绝错误服务目标、自动代理和重定向。测试中的节点和地区均为夹具。

## 迁移与实际运行限制

- News V5 在已有内容及旧 V4 出站记录上验证，旧队列自动标为 EDITION。新表为 news_source_poll、news_region_job、news_region_schedule。
- Scheduler 在真实执行至 V140 的 H2 表结构上分别执行新增 V173/V174，验证定义关闭且包版本固定。不是 V141—V174 的全量迁移证明。上一批已发现历史 V141 使用 H2 不支持的 JSON_SET，本批没有改写该迁移或重新宣称全量通过。
- 未执行真实 MySQL 迁移、真实节点定位、代理/NAT 环境联调、首个实际本地来源包验证或真实七日采样。官方 MMDB 测试库用于离线读取兼容性，不能证明执行节点地区；文件摘要在 JSON 中。
- 全仓库环境门禁仍报告 media_generate_tags、reader_analyze_audiobook_book 的 TASK_ERROR_FILE，pdf_process 的 PDF_ALLOW_UNSANDBOXED_TEST，以及 video_generate 的既有缺项。没有修改这些包；新闻生成及地区包无缺项。
- 没有新增 Java 编译告警；既有 Flyway/H2 版本兼容提示、Mockito 动态 agent 提示仍存在。四个实时来源测试显式跳过，不能算通过。
- NEWS_PIPELINE_ENABLED 与 NEWS_SCHEDULING_ENABLED 默认 false；Scheduler 定义/集群默认关闭；包尚未发布。定时器实现不等于已开启后台服务。

## 复现命令

设置 JDK 21 的 JAVA_HOME，Python 使用 3.12：

~~~bash
mvn -f service/news-digest-service/pom.xml package
mvn -f service/task-scheduler-service/pom.xml package -Dtest=NewsExecutionAuthorizationControllerTest,NewsTaskMigrationTest,PublicPdfTaskAccessTest
python3.12 -m unittest discover -s service/news-digest-service/packages/news_resolve_region/1.1.0/tests -v
python3 service/scripts/assemble_executor_packages.py --service-root service
python3 service/scripts/executor_environment_contract_gate.py --service-root service
~~~

带数据库的 Python 验证需在隔离环境安装包 requirements.txt 中的 maxminddb==3.2.0，再通过 NEWS_REGION_TEST_MMDB 指向官方城市测试库，重复同一 unittest 命令。未配置时该一项显式跳过。最后一个全仓库门禁当前预期报告上述既有缺项。

## 后续目标

下一优先级是已有登录前端的订阅、历史与日报阅读页，随后补 Messaging 目的地绑定、邮件模板及投递对账，再扩展飞书与 QQ。真实节点/来源/数据库准备可与页面开发分别推进；长期启用采集前仍需保留清理和监控。详见 [实施计划](../../design/2026-10-18-daily-news-implementation-plan.md) 的“主动推荐的后续目标”。
