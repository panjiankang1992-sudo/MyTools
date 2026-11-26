# 第十二批：发布基础配置

日期：2026-10-18。目标 ACTIVE；N24 为 DOING。本批是发布基础接入，不是部署完成。

## 实际变更

- 权威服务清单加入 news-digest-service，23350 / mytools_news / NEWS，默认关闭；复用既有 systemd、独立日志和 Schema 装配。
- Gateway 应用内映射 GATEWAY_NEWS_ROUTE_ENABLED、NEWS_SERVICE_URL、NEWS_INTERNAL_TOKEN，默认关闭，真实 Spring 配置绑定已验证。
- Executor 声明 news 集群和 news.node；Scheduler 默认允许新闻集群。新闻标签只授予配置匹配的注册节点名，其他节点不能通过相同声明得到标签；absent 和空配置不授予标签。普通已有标签策略保持不变。
- 新建私有环境生成独立 News/Messaging v2 凭据与目标加密密钥，所有新闻业务及 timer 默认关闭，地区节点和阅读网址留待真实配置。未创建或改写任何生产环境文件。
- 包含新闻服务的安装器要求地区包 1.1.0 的固定依赖清单，并安装到发布专用 venv。缺少依赖时失败，不切换 current；没有在目标机实际运行安装。
- 完整发布装配包含新闻保留手册与发布准备手册。发布顺序、旧环境配置保留、指定节点、真实验收及空正文兼容回退已记录。

## 验证

| 范围 | 结果 |
| --- | --- |
| Gateway 新闻接口与正式配置绑定 | 13 通过 |
| Scheduler 节点标签、新闻及 Messaging 任务边界/新迁移 | 15 通过 |
| Executor 配置、注册客户端、包摘要、实际子进程日志 | 24 项：22 通过、2 项 Linux 专用测试跳过 |
| 私有环境、systemd、Schema、安装器、完整装配与包装配 Python 单测 | 38 通过 |
| 现有工作区所有 Executor 包的索引检查 | 153 个，全部通过；没有发布输出目录 |
| Gateway、Executor、Scheduler Java 21 打包 | 通过 |

本批共 88 通过、2 跳过。与前批存在定向回归重跑，不将它们累计为新增独立测试。News/Messaging 本批未改业务实现，使用第十一批已验证 JAR；对五个相关 JAR 的配置/迁移存在性做了检查并记录 SHA-256。

Executor 日志分段测试最初因 macOS `/usr/bin/python3` 的 Xcode license 限制返回 69。测试增加可选 `mytools.test.pythonExecutable`，保留默认行为；本机指定已经安装的 Python 3.12 后实际子进程输出及摘要断言通过，没有绕过或跳过该测试。Linux 资源限制两项仍由原有 OS 条件跳过。

Scheduler 的测试只覆盖授权策略、新任务迁移以及相关控制器。旧 V141 的 H2 JSON_SET 兼容限制没有在本批解决，不能声称全部历史迁移或完整 Scheduler 回归已通过。

机器统计、包索引摘要、JAR 和关键源码哈希见 [测试记录](news-release-foundation-test-summary.json)。运行顺序见 [发布准备](../../runbooks/daily-news-release.md)。

## 后续交付

N24 尚需现有环境的增量配置工具、启用前运行门禁和完整发布装配验证。N25/N26 仍需配置 SSH 的实际目标、真实 MySQL、节点地区/来源、明确授权的渠道目标以及七日影子采集。QQ 平台主动推送限制不变。本批没有启动生产采集、改写线上数据库或发送消息。
