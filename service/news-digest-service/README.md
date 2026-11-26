# News Digest Service

个人使用请先看[自用版实施范围](../../docs/design/2026-10-19-daily-news-personal-mvp.md)：配置少量来源、看一份日报、验证自己的邮件接收，再启用每日定时。下文包含既有技术细节，容量和审计扩展不再是个人版的新增开发主线。

2026-10-19 个人 DSH 图文链路已安装并启用每日 07:30 任务，运行包为 `news-personal-1.0.5`。最新真实预览四类各有 20 条标题，来源不足和短讯均明确说明；见[本地来源与短讯验证](../../docs/verification/daily-news/2026-10-19-local-source-expansion.md)。下文的 Spring 服务、数据库和原消息 Outbox 部署状态与这条个人运行链路分开记录。

2026-10-31 个人运行包已更新为 **1.2.0**，在 DSH 前接入 Trafilatura 和可追溯的正文补全，定时任务保持每天 07:30。见[正文补全设计](../../docs/design/2026-10-31-daily-news-content-acquisition.md)与[实测记录](../../docs/verification/daily-news/2026-10-31-content-acquisition.md)。

2026-11-01 个人运行包已更新为 **1.4.1**，新闻 RSS 已上线：[订阅入口](https://toolbox.yuyutian.top/news/subscribe/)、[全部新闻](https://toolbox.yuyutian.top/news/rss/daily.xml)。技术、国际、国内、江苏本地各有独立新闻源，每条新闻一个条目并附详情，配图优先采用新闻原图，支持 OPML 导入。最近一周文章保留在 Feed，分类日报另作网页归档。见[方案](../../docs/design/2026-11-01-news-rss-publishing.md)与[运行说明](../../docs/operations/news-rss.md)。

2026-11-02 扩展包 **1.5.1** 支持十分类，新增流行、军事、财经、社会、体育、娱乐。RSS 标题统一为 `【分类】【YYYY/MM/DD】标题`，沿用原订阅地址和文章身份。个人生产配置保持邮件关闭。

2026-11-04 个人运行包 **1.5.2** 将每日每类输出调整为 **Top10**，每条保留图文详情；候选池仍为约三十条，历史文章地址保留。见[验证记录](../../docs/verification/daily-news/2026-11-04-news-top10.md)。

## 一条命令看真实日报

2026-11-02 运行包更新至 **1.4.2**，新增 [Outlook 兼容订阅](https://toolbox.yuyutian.top/news/rss/outlook.xml)：最新最多 100 条、每条完整图文，原一周完整源继续保留。Nginx 显式启用 RSS/OPML 压缩；客户端实际添加订阅的结果需单独确认。

在 Java 21 和 Maven 环境运行，输出目录须不存在：

```bash
bash service/news-digest-service/scripts/preview-personal.sh /tmp/my-news-preview
```

默认使用 [personal-sources.json](examples/personal-sources.json)：GitHub、Hacker News、DEV、中新网国际/国内、BBC 国际，以及中新网江苏、中国江苏网江苏要闻两个本地来源。江苏来源只会进入实际地区匹配的本地栏目。该配置用于个人订阅的短摘要与原文链接，不包含收件地址。原来的技术来源示例仍保留仅标题配置；已登记来源不能原地改变内容政策，因此个人示例使用独立来源 ID。

工具复用实际采集器和日报渲染器，输出 HTML 和采集数量汇总。来源摘要足够时直接采用；RSS 和 HN 在前二十候选中最多补读十篇缺少摘要的原文，避免已有摘要占用补读额度。每条展示最多 300 个码点，保留实际证据链接；不可访问、正文不足时保留标题并提示。不会把页面导航、评论区或验证码页面当作详情。GitHub 页面使用 README/Wiki 段落，排除注册宣传简介。

该命令使用一次性本地 H2，不读取生产数据库配置，也不发送邮件。本地栏目在未取得执行服务的 IP 地区时保持为空；它是一份内容预览，不代表每日任务或生产投递已经启用。

若已有执行主机刚产生的地区报告，可将来源 JSON 和报告路径分别作为第二、第三参数传入。报告须在五分钟内，不能改写旧报告的观测时间。中新网江苏频道按 GBK/GB2312 解码；中国江苏网江苏要闻按其静态列表与独立正文容器解析。两者都采用页面明确发布时间，近 24 小时不足 20 条时显示实际数量；本地范围按报告的可用精度选择。


每日新闻速报的内容服务，已完成基础内容链路、技术来源、节点地区、订阅生成编排、周期采集/地区任务及第六批网页订阅阅读。Java 21、Spring Boot、MySQL/Flyway，独立构建；不加入根 Maven reactor。

设计与计划：[设计文档](../../docs/design/2026-10-18-daily-news-digest-design.md)、[实施计划](../../docs/design/2026-10-18-daily-news-implementation-plan.md)。

## 已实现

- 独立管理令牌与用户代理令牌；只有健康检查匿名可读。管理接口不经 Gateway 暴露。
- 注册来源、启停、加工权限声明、抓取健康状态、条件请求缓存和有期限的采集租约。
- 受控 HTTPS RSS/Atom 采集：实际拨号 DNS 校验、公网地址限制、同主机重定向、2 MiB 响应上限、禁用 DTD/外部实体。
- GitHub、Hacker News、DEV 官方 JSON 适配；原始日期、原生指标、讨论地址与采样覆盖审计。
- GitHub 六小时聚合快照、近七日净星数、冷启动/缺测/过期状态；仓库改名按数字 ID 去重。
- 持久化来源冷却与平台共享限流；已观测撤稿退出后续榜单，历史日报保持原样。
- 不可变文章版本：规范 URL、内容哈希、原始发布时间、服务端观测时间。重复采集不刷新第一次观测时间。
- 技术七日、其他二十四小时的 `[start, cutoff)` 窗口；只使用截止时刻前已经观测的证据。
- 网址和同日长标题去重、单事件单分类、来源内热度归一化、可解释的稳定排序。
- 每类最多二十条，前十条附 80—300 码点的来源摘要或原文节选与证据引用。不足时降级，不更换名次凑详情。
- 按 owner 隔离的预览、历史和日报读取；幂等冲突、并发去重、数据库重启恢复。
- Gateway `/api/app/v1/news` 和 `/api/v1/news` 两个前缀；所有者来自认证主体。
- 节点探测包、在线执行租约鉴权、地区来源包、同出口缓存与省级降级；每个预览请求在生成前持久化地区快照。
- 持久化来源轮询、失败退避、地区六小时任务与独立队列；多实例互斥，重启不重置采样间隔。

当前**尚非完整的每日推送服务**。地区代码与离线链路已实现，实际执行节点定位和首个真实本地来源包未验收；内容订阅和 Scheduler 生成编排已有代码与离线验证；实际任务联调、消息 Outbox 和邮件/飞书/QQ 投递仍待接入。服务没有发送接口，不会因预览发送消息。

## 当前明确的实现边界

| 项目 | 当前行为 |
| --- | --- |
| 地区 | 使用指定执行节点的独立报告；未配置时为 `UNRESOLVED`。来源包缺失、仅国家精度、出口未知或观测过期时本地保持空榜；允许的省级/缓存结果继续明确降级。测试地区不是实际节点证明。 |
| 分类 | 由受控来源登记指定，不是文章语义分类。转载归属依赖登记的 `publisherKey`。 |
| 聚类 | 规范 URL，或 UTC 同日、归一化后至少 12 码点的完全相同标题；不宣称已做语义事件聚类。 |
| 排名 | `technical-evidence-v2`：0.35 新鲜度 + 0.25 独立出版者佐证 + 0.20 同源热度 + 0.20 来源权重。GitHub 只将覆盖合格的正净增量用于热度；HN 讨论不增加独立出版者佐证。来源配额、多样性和重复惩罚仍待接入。 |
| GitHub 时间 | 保留仓库原始创建时间；使用聚合快照观测时间进入技术窗口。仅有 GitHub 证据且无合格正增长时，新鲜度与热度均为零，避免将旧项目累计星数当作本周热点。 |
| 技术覆盖 | GitHub 搜索发现与有界跟踪队列、HN 当前热榜抽样、DEV 七日热门第一页。不是全站或全网 Top20；覆盖限制随来源状态返回。 |
| 详情 | `SOURCE_EXCERPT_V1`，保留来源语言，最多 300 码点；不是模型摘要或翻译。只有标题或少于 80 码点证据时不给详情。 |
| 完整度 | `COMPLETE` 要求数量达标，且没有已实现的地区/趋势缺口；不代表事实核验、全网覆盖或上线验收。默认配置下地区尚未解析，整体为 `DEGRADED`。 |
| 预览 | 有界同步生成，返回 200；最多 10,000 个候选，超限明确报错。预览保持同步；订阅通过独立 /runs 查询 Scheduler 异步生成状态。 |
| 时间 | 默认 Asia/Shanghai、07:30；能力接口提供最近已到达的 cutoff。内容订阅可单独配置时区/截止时刻；默认发送时刻 08:05 仅在设计中。订阅扫描和任务对账默认关闭。 |
| 来源变更 | 同一来源 ID 仅允许启停；网址、分类、权重及加工政策改变需新 ID，以保持历史证据身份不变。 |
| 网络 | HTTPS/443、域名地址、无系统代理、同主机最多三次重定向。连接/读超时 5 秒，总读取预算 20 秒；操作系统 DNS 解析耗时不受该读取预算保证。生产还需网络出口层限制。 |
| 数据库 | H2 回归及隔离 MySQL 8.4 全量迁移、新旧引擎接续已验证；生产备份副本和跨服务链路仍待验收。 |

## 执行节点地区与本地来源

探测包位于 [news_resolve_region/1.2.0](packages/news_resolve_region/1.2.0/README.md)。它在 Executor 的实际网络环境探测固定 IPv4 或 IPv6 出口。自用可选择免密钥 `IPWHOIS`，不需城市库；MaxMind 本地库和 Web 仍可选。NAT 自动体现在出口结果中；代理需显式配置，不能从用户请求头或新闻服务自身 IP 推测地区。IPWHOIS 没有城市精度证据时采用已登记的省级范围，并明确显示降级。

News 只接收已配置节点、过去五分钟内且最多领先服务时钟三十秒的毫秒精度报告。首次接收后报告不可变，同 ID 重放原报告不受后续时间推移影响。同出口成功观测可用 24 小时；只有新的探测确认仍是同一出口且定位失败时，才允许最多七日的成功地区缓存。中间出现不同或未知出口会切断缓存连续性。

城市要求供应商提供 GeoNames ID 和精度半径，默认半径不超过 50 km。这个阈值是新闻范围策略，不承诺城市定位绝对准确。缺城市、精度不足或城市来源包缺失时，可以按配置退到省/州级；仅国家证据不填充本地栏目。

管理员先登记 LOCAL 来源，再登记不可变 `region-source-pack` 版本。城市代码使用 `GEONAME-{id}`，省/州使用 `{country}-{subdivision}`，例如 `CN-SC`。每个来源必须与所属包的 regionCode 完全一致；仅同地区但未列入包的来源也不参加本地榜单。地区与来源包版本在预览开始时冻结，生成失败重试仍保留原地区，新一期/新幂等键才采用新观测。

`GET /local-region` 展示当前观测；capabilities.regionStatus 对应 latestCutoff；历史日报展示它自身冻结的地区。三者可能不同，客户端不能用“当前地区”覆盖历史日报。

地区任务 1.1.0 使用 NEWS_JOB_TOKEN 和 Scheduler 在线执行租约；提交前再次校验任务、指定节点及 fence。旧 1.0.0 静态令牌报告入口仅在 NEWS_PIPELINE_ENABLED=false 时可用于独立诊断；开启流水线后该入口返回 NEWS_016，不能绕过工作租约。还需配置真实主节点、城市数据库或服务账户、首个来源包，并完成实际节点验证后才能启用本地日报。见 [第五批验证记录](../../docs/verification/daily-news/2026-10-18-periodic-pipeline.md)。

## 内容订阅与每日任务

首期每个 owner 一份固定四分类的内容订阅；独立 subscription ID 和逐条 owner 隔离为多用户扩展保留边界。当前订阅只生成可阅读日报，`deliveryEnabled=false`，没有接收人字段。

- `GET/PUT /subscription`：IANA 时区、分钟级 cutoffTime、enabled、expectedRevision。首次创建 revision=0；编辑携带当前版本，冲突为 NEWS_014/409。
- 新启用或修改只安排之后的截止；修改或停用取消所有未完成生成。数据库唯一键 `(subscription_id, business_date)` 防止改时刻、停用再启用或多个扫描实例造成当日重复。
- 每分钟扫描最多 100 份到期订阅。恢复时只考虑最近已到截止的一期；更早漏掉的日期计入 skippedDays。距 cutoff 达到 12 小时则 EXPIRED，不派发；正好截止时可生成。
- 夏令时缺失时刻向后移动，重复时刻取较早偏移；同一天不生成两次。源候选仍严格使用截止前观测，恢复后新抓取不能回填旧窗口。
- run 冻结 owner、订阅版本、业务日期、时区、cutoff、过期时刻和节点；使用 `news:{runId}` 向 Scheduler 创建唯一任务。生成幂等空间 `news-run:` 为服务保留，用户预览不能使用。
- 每十秒处理最多 20 个队列记录；单记录租约 120 秒。网络/429/5xx 保留原键，5 秒起退避、上限 15 分钟。请求结果未知时不能换键重建；取消或过期仍继续对账，必要时重放原创建请求查明任务，再取消。
- 永久拒绝进入 DEAD，需要管理员修复配置后调用 redrive 恢复原记录。远端任务成功但没有已发布日报记 FAILED/NEWS_019。
- 任务通过 `news_generate_edition/1.0.0` 调用生成接口。News 使用独立业务身份在线验证执行租约和 fence，确认任务属于该运行和指定节点；构建后在发布事务内再次验证。日报与 READY 状态原子保存，取消/停用和发布串行。地区绑定先单独持久化，失败重试不漂移。

生成状态为 QUEUED、DISPATCHED、READY、FAILED、CANCELLED、EXPIRED；taskStatus 独立保留 Scheduler 状态。READY 代表内容已经可读，内容仍可能 DEGRADED，也不代表发送。已生成内容即使随后 Scheduler 回报失败仍可阅读，需查看两种状态分别判断。

| 配置 | 默认值/作用 |
| --- | --- |
| NEWS_SCHEDULING_ENABLED | false；关闭时不扫描、不派发、不对账，也拒绝工作回调。 |
| NEWS_SCHEDULING_TIMER_ENABLED | true；只有前一个开关也开启才安装定时器，测试/运维可单独关闭触发器。 |
| NEWS_SCHEDULER_URL | http://127.0.0.1:23410；跨主机必须 HTTPS，不跟随重定向。 |
| TASK_BUSINESS_NEWS_TOKEN | 无默认；至少 32 字符，News 与 Scheduler 独立 `news-digest-service` 身份对应。 |
| NEWS_JOB_TOKEN | 无默认；至少 32 字符，不能复用代理、管理、地区或 Scheduler 凭据，注入新闻生成包及地区包 1.1.0。 |
| NEWS_REGION_NODE_ID | 与实际执行节点名一致，同时冻结为任务 `news.node` 标签约束。 |
| TASK_NEWS_NODE_VALUE | Scheduler 的受信任注册标签，默认 absent；上线时与指定节点名匹配。 |

Scheduler V173 预置 `news` 集群和 `news_generate_edition` 定义，均为关闭；步骤固定引用包 1.0.0。上线前需发布不可变包、把指定节点纳入集群、配置可信标签及允许的集群，再启用任务。不能为了连接 News 而全局关闭 Executor 网络隔离；现有 allowed-domains 不是网络代理，隔离节点的连接能力必须单独验收。

第五批增加独立周期采集及地区任务开关，详见下节。切换 `NEWS_REGION_NODE_ID` 后旧节点的待生成运行被拒绝，需取消旧运行并安排之后期次，不能将另一节点地区套入旧运行。

管理入口 `/internal/admin/news/scheduling` 提供 `POST /scan`、`POST /relay`、`GET /runs/{id}/outbox`、`POST /runs/{id}/redrive`；使用管理令牌，不通过 Gateway 暴露。详情见 [第四批验证记录](../../docs/verification/daily-news/2026-10-18-subscription-scheduling.md)。

## 周期采集与地区任务

自动更新由 NEWS_PIPELINE_ENABLED 控制，默认 false。启用时必须同时开启 NEWS_COLLECTION_ENABLED、NEWS_SCHEDULING_ENABLED，并配置指定节点及调度身份，否则启动失败。NEWS_PIPELINE_TIMER_ENABLED 默认 true；关闭它可保留管理接口手动推进。不会自动登记、启用来源或配置接收人。

| 环节 | 当前规则 |
| --- | --- |
| 来源扫描 | 每次结束后间隔一分钟；只轮询已启用来源。每轮最多五个来源，经过六十秒后不再开始下一个来源；已开始的采集仍受原采集器预算约束，因此不是整轮六十秒硬超时。 |
| 成功间隔 | RSS/Atom 默认三十分钟，可用 NEWS_RSS_INTERVAL_MINUTES 配置为 5—1440；HN 十五分钟、DEV 一小时、GitHub 六小时。 |
| 多实例与恢复 | 轮询租约三分钟，调用网络前先持久化下一时刻；继续复用底层两分钟采集租约。进程退出不会重置游标或快速重复采样，旧租约不能覆盖新状态。 |
| 失败 | 单源失败不阻断其他来源；通常从六十秒指数退避，上限一小时。429 与已有平台冷却按其允许的重试时刻执行；状态不伪装为成功。 |
| 地区扫描 | 每分钟检查当前 UTC 六小时桶，每节点每桶最多一个逻辑任务；有未完成任务时不重叠，不补建停机期间的历史桶。每任务自创建起十五分钟有效。 |
| 地区队列 | 与日报队列按 REGION/EDITION 分离；每十秒最多推进五个地区任务，租约一百二十秒。未知创建结果保留原幂等键，取消后仍收敛远端状态；DEAD 可恢复原记录。 |
| 地区提交 | 探测前读取原回执；若已提交，后续执行直接返回同一回执。否则执行节点探测后通过活动租约提交，报告和 STORED 原子保存。 |

Scheduler V174 增加默认关闭的 news_resolve_region 定义，步骤 resolve 固定使用包 1.1.0；参数仅 jobId。任务使用 NEWS_REGION、news:region:{jobId} 和指定 news.node，不与日报任务身份混用。包和任务均尚未发布/启用。

地区作业状态为 QUEUED/DISPATCHED/STORED/FAILED/CANCELLED/EXPIRED；STORED 只代表报告已保存，observationStatus 仍可能为 LOOKUP_FAILED 或 EGRESS_UNAVAILABLE。没有报告的远端终态记 NEWS_020。切换指定节点后，旧地区作业会被取消，旧节点不能再提交。当前报告不会改写已经冻结的历史日报地区。

管理前缀 `/internal/admin/news/pipeline` 提供采样进度、一次轮询、地区扫描/对账/取消/死信恢复；仅管理令牌可用，不经 Gateway 暴露。脱敏查询不返回原始 IP、指纹或租约令牌。接口字段见 [协议](../contracts/news-digest-v1.md)，离线证据与上线缺口见 [第五批记录](../../docs/verification/daily-news/2026-10-18-periodic-pipeline.md)。

## 技术来源与趋势口径

来源 `kind` 省略时按 `RSS_ATOM` 处理，兼容旧数据。三种技术协议只能登记到 TECH，固定入口及初始停用配置见 [技术来源示例](examples/technical-sources.json)。示例仅开放元数据，文档链接本身不代表获得正文加工许可。另有 `CHINANEWS_JIANGSU` 仅接受 LOCAL、CN-JS 和 `https://www.js.chinanews.com.cn/`，默认成功冷却 30 分钟。

| 协议 | 采样方式 | 默认成功后冷却 | 正文范围 |
| --- | --- | --- | --- |
| HACKER_NEWS | topstories 前 30 个 ID，读取官方 item，排除 job/comment/dead/deleted，按原始 `time` 过滤七日 | 15 分钟 | 优先 item 自带文字，引用讨论页；允许摘要的来源在前二十候选中最多补读十篇原文，成功后引用实际原文页 |
| DEV | `top=7` 第一页 30 条，按 `published_at` 过滤，编辑时间不刷新新闻日期 | 1 小时 | EXCERPT_ALLOWED 才补取最多 10 篇官方详情；否则不请求详情 |
| GITHUB | 近七日 pushed 的公开非归档非 fork 仓库，按累计星数搜索第一页 30 条用于发现；额外跟踪最多 30 个已有仓库 | 6 小时 | 保存汇总指标和允许范围内的描述；不下载源码、README 或点赞用户列表 |

GitHub 搜索只是候选入口，累计星数不直接进入周热度评分。快照按 UTC 六小时桶保留首次值；以最近一次观测减去七日为基准，选择不晚于该基准且最多早六小时的真实快照。`coverageSeconds` 显示实际跨度，通常为 168—174 小时，因此名称是“近七日净变化”，不是精确七日新增点赞人数。

`personal-source-mix-v4` 为时间窗内的 GitHub 候选保留最多五个名额，剩余名额按现有得分填充；GitHub 不足五条时其他来源补位，最终仍按原得分排序。这样首次运行也能看到仓库，不需要等待七天。累计星数不被改成周增量，缺失的增量仍为 null；邮件明确说明是综合选报，而非全网热度榜。

- `WARMING_UP`：积累不足；`netStars=null`，不能显示为零增长。
- `MISSING_BASELINE`：已有较老数据，但缺少允许范围内的对照快照。
- `GAPPED_HISTORY`：中间采样间隔超过 12 小时；保留端点差值，退出热度评分。
- `STALE`：距最新观测超过 12 小时；退出热度评分。
- `READY`：端点与连续性合格；负净变化保留展示，热度仅使用非负部分。

READY 已用固定时钟的 29 次采样验证；真实连续七日运行尚未进行。第五批内置来源轮询遵守采样间隔并持久化下一次采样时刻；首次启用不会回补七日前的快照，仍需真实运行积累。

上游返回 429 或可识别的 GitHub 配额耗尽时，返回 `NEWS_012` 和 `Retry-After`，将冷却写入数据库；同一平台的其他来源 ID 也遵守该冷却。正常成功间隔属于本服务采样策略，不是平台官方配额。每轮有 75 秒循环预算，已开始的单次请求仍受传输器预算约束；超时/部分失败明确记为 PARTIAL。

撤稿只对重新观测到的 HN 删除/死亡条目和 GitHub 排除状态生效。退出 HN 采样列表的条目、GitHub 404、DEV 删除等没有实现完整复核，不能声称已覆盖所有撤稿。

## 构建与验证

使用 JDK 21。Maven 实际运行的 JDK 用 `mvn -version` 确认，不能只看 `java.version` 属性。

~~~bash
mvn -f service/news-digest-service/pom.xml compile
mvn -f service/news-digest-service/pom.xml test
mvn -f service/news-digest-service/pom.xml package -DskipTests
~~~

编译启用 `-Xlint:all -Werror`。默认测试只使用固定材料和本地数据库，不发送消息；`LiveFeedSmokeTest` 和 `LiveTechnicalSourcesTest` 默认跳过。

单独验证公开 RSS 的真实 Java 传输器：

~~~bash
mvn -f service/news-digest-service/pom.xml test -Dtest=LiveFeedSmokeTest -Dnews.liveProbe=true
~~~

该验证只读取中国新闻网公开 RSS，不写数据库、不输出正文、不发消息；本机成功不证明实际执行节点可访问，也不代表拥有全文再分发或模型加工权限。

单独验证三类技术来源的真实适配器与传输器：

~~~bash
mvn -f service/news-digest-service/pom.xml test -Dtest=LiveTechnicalSourcesTest -Dnews.liveTechProbe=true
~~~

每类只读三个样本；匿名请求、元数据模式、不写数据库、不跟踪旧仓库、不发送消息。阶段结果见 [第二批验证记录](../../docs/verification/daily-news/2026-10-18-technical-sources.md)。

Gateway 相关回归：

~~~bash
mvn -f service/mytools-gateway/pom.xml test -Dtest=NewsGatewayControllerTest,GatewayRequestFilterTest
~~~

## 本地/测试环境配置

先单独准备 `mytools_news` schema 与最小权限账户，敏感值通过已有环境配置设施注入。不要将真实值写入示例、命令记录或仓库。

| 环境配置 | 默认值/含义 |
| --- | --- |
| NEWS_HTTP_HOST / NEWS_HTTP_PORT | 127.0.0.1 / 23350；已纳入 services.json，服务默认不自动启动。 |
| NEWS_DB_HOST / NEWS_DB_PORT / NEWS_DB_NAME | 127.0.0.1 / 3306 / mytools_news。 |
| NEWS_DB_USER / NEWS_DB_PASSWORD | 无默认凭据。 |
| NEWS_INTERNAL_TOKEN | Gateway 用户代理令牌，空值拒绝调用。 |
| NEWS_ADMIN_TOKEN | 独立来源管理令牌，空值拒绝调用；不能与用户代理令牌相同。 |
| NEWS_COLLECTION_ENABLED | false；必须显式启用才可调用实时采集。 |
| NEWS_ZONE / NEWS_CUTOFF_TIME | Asia/Shanghai / 07:30。 |
| NEWS_REGION_NODE_ID | 默认空；必须与实际 Executor 节点名一致。 |
| NEWS_REGION_REPORT_TOKEN | 默认空；仅供流水线关闭时的旧接口诊断，至少 32 字符，不能与其他令牌相同。 |
| NEWS_REGION_MAXIMUM_CITY_RADIUS_KM | 50；允许 1—500，单位 km。 |
| NEWS_REGION_ALLOW_PROVINCE_FALLBACK | true；显式允许使用已登记省/州来源包。 |
| NEWS_GITHUB_TOKEN | 可选、默认空；仅发送到 `api.github.com`，不进入日志/返回值。 |
| NEWS_TECH_ITEM_LIMIT | 30；范围 1—50，单轮列表预算。 |
| NEWS_TECH_DETAIL_LIMIT | 10；范围 0—10，DEV 详情请求预算。 |
| NEWS_GITHUB_TRACKED_LIMIT | 30；范围 0—30，GitHub 旧仓库跟踪预算。 |

启动打包产物：

~~~bash
java -jar service/news-digest-service/target/news-digest-service-0.1.0-SNAPSHOT.jar
~~~

Gateway 已内置下面的配置映射。令牌使用部署环境中的引用，不能传入真实值到用户浏览器。

~~~yaml
gateway:
  news:
    route-enabled: ${GATEWAY_NEWS_ROUTE_ENABLED:false}
    url: ${NEWS_SERVICE_URL:http://127.0.0.1:23350}
    token: ${NEWS_INTERNAL_TOKEN:}
~~~

配置有意默认关闭。周期采集与任务代码已存在，但尚未部署或启用生产定时器，也没有账户目的地绑定。

## 手动验收顺序

1. 使用管理令牌登记已确认可使用的来源，初次登记 `enabled=false`。来源请求示例见 [API 协议](../contracts/news-digest-v1.md)。
2. 审核来源网址、出版者归属、分类和权限后，仅将该来源切换为 `enabled=true`；配置启用采集。
3. 调用一次来源 collect，检查 `health`、`coverage` 和 `nextAttemptAt`。论坛/RSS 看 `newestPublishedAt`；GitHub 看 `newestObservedAt`，其 `newestPublishedAt` 仍为原始仓库创建时间。FRESH 不证明数量充足、趋势合格或内容可信。
4. 在采集结束后选择 cutoff，再从 Gateway 生成预览。若 cutoff 早于此次第一次采集，材料不会进入该历史窗口，这是预期行为。
5. 检查原文引用、缺口、所属 owner；重复相同 key/cutoff 必须返回同一 ID，改 cutoff 必须 409。
6. 重启服务，确认历史和快照仍然可读。预览本身始终不发送。

## 数据与运维约束

- V1 三张内容表：`news_source`、`news_article`、`news_edition`。V2 增加 `news_github_snapshot`、`news_source_withdrawal`、`news_provider_cooldown`，并回填旧文章排名时间。V3 增加 `news_region_observation`、`news_region_source_pack`、`news_region_snapshot`。V4 增加 `news_subscription`、`news_daily_run`、`news_task_outbox`。V5 增加 `news_source_poll`、`news_region_job`、`news_region_schedule`；Outbox 新增 job_kind，旧记录回填 EDITION。文章和日报不能原地覆盖，旧版 JSON 缺失字段按兼容默认值读取。
- 地区观测只保存 HMAC 出口指纹；公开的地区视图、日报和任务结果不包含指纹或完整 IP。报告令牌、指纹密钥、MaxMind 凭据不进入 Scheduler 参数或包源码。
- 采集租约 120 秒；网络请求在事务之外，提交前核验当前租约。过期旧请求不能覆盖新采集。
- 修改来源启停使现有租约失效。关闭来源后不进入新榜单，已保存快照不回写。
- 历史列表最多 100 条；游标分页、正文保留清理、预算指标和告警仍是后续工作，长期开启采集前必须完成。
- 原文最多保留 20,000 码点的清洗文本；METADATA_ONLY 不持久化详情正文。
- 数据来自实际来源，测试夹具不能用于生产日报或标记真实验收完成。

紧急停止新生成可关闭 NEWS_SCHEDULING_ENABLED；该开关同时停止远端任务对账，恢复后仍沿用原幂等键。常规停用应先停用订阅并等待任务取消收敛，再关闭调度。完全回退可关闭 Gateway route-enabled 与 NEWS_COLLECTION_ENABLED，停止新闻服务；保留 schema 和幂等记录。旧消息系统行为未修改。


## 个人新闻网页

现有登录前端 `webapp` 增加 `/news`。页面包含订阅启停/时区/截止、预览、历史和四分类阅读、证据引用、当前与历史地区、详情/趋势缺口、生成记录及取消；READY 不显示为已投递。最多读取最近 100 份日报和 30 次运行；存在活动任务时每二十秒刷新列表，后台刷新不重置表单。

使用同源 `/api/v1/news`；开发代理变量为 VITE_NEWS_GATEWAY_URL，默认 http://127.0.0.1:23200。生产反向代理须将该前缀路由至 Gateway，且 Gateway 的新闻路由与内部 News 身份需正确配置。内部服务令牌不得写入任何 VITE_* 变量。页面无虚构内容回退，服务不可用时显示错误并支持重试。

实现和离线验收见 [第六批记录](../../docs/verification/daily-news/2026-10-18-news-web.md)；页面已完成不代表当前生产站点已部署。


## News delivery outbox

V6 captures up to five verified Messaging destination IDs at daily-run creation and freezes rendered parts during the edition publication transaction. FULL and BRIEF are explicit modes. Preview remains read-only with respect to delivery.

Configure `NEWS_DELIVERY_ENABLED`, `MESSAGING_SERVICE_URL`, `MESSAGING_OUTBOUND_CLIENT_TOKEN`, and a public HTTPS `NEWS_READER_URL` only after Messaging v2, its worker package and the default-disabled Scheduler task are installed. `NEWS_DELIVERY_TIMER_ENABLED=false` is useful for controlled local tests. No address or Webhook belongs in News configuration or user requests.

See [delivery API details](../contracts/news-digest-v1.md#delivery-integration-batch-8) and [Messaging v2](../contracts/messaging-outbound-v2.md). No real destination has been contacted by the local tests. The delivery webpage panel is implemented and locally verified; production acceptance remains pending. See [batch 9 verification](../../docs/verification/daily-news/2026-10-18-news-delivery-web.md).


## Retention and operations

V7 adds default-disabled bounded cleanup, source-specific shorter evidence limits, persistent checkpoints and content-expiration responses. Private Prometheus uses the news administration token, while anonymous health hides details. See [operations runbook](../../docs/runbooks/daily-news-retention-operations.md) and [batch 10 verification](../../docs/verification/daily-news/2026-10-18-news-retention-operations.md). The News implementation does not yet compact Messaging bodies or impose a total database size quota.


## 发布准备进度

News 已进入默认关闭的服务清单，Gateway 环境映射、Executor 新闻集群、指定注册节点标签和地区包依赖安装已接入。现有私有环境支持默认预览的缺失项增补，发布前执行文件/包摘要及 staged/shadow/delivery 配置检查；完整本地发布装配已通过。实际 MySQL/节点/渠道联调仍待验收。见[发布准备手册](../../docs/runbooks/daily-news-release.md)。

News、Messaging、Scheduler 已统一到 Spring Boot 3.5.16 和 Flyway core/mysql 11.20.3。隔离 MySQL 8.4.11 / Java 21 的八项用例通过，覆盖 7/20/176 个完整迁移、固定旧数据、关键 repository、新旧引擎接续及校验和异常阻断，新引擎不再产生此前版本提示。`MySqlCompatibilityTest` 需显式设置私有 `NEWS_MYSQL_TEST_CONFIG`，默认跳过；旧引擎用例另需测试专用依赖目录，见[发布手册](../../docs/runbooks/daily-news-release.md)。实际跨服务联调、生产数据库副本、节点及渠道仍待验收，详见[第十六批记录](../../docs/verification/daily-news/2026-10-18-flyway-upgrade.md)。


### Collection storage admission

V8 adds persistent row and UTF-8 payload budgets for articles, GitHub snapshots and withdrawals. Defaults are 250,000 article rows, 250,000 snapshot rows, 100,000 withdrawal rows and 512 MiB of charged JSON, including 128 bytes of expiration headroom per article. Configure `NEWS_CAPACITY_ARTICLE_ROWS`, `NEWS_CAPACITY_SNAPSHOT_ROWS`, `NEWS_CAPACITY_WITHDRAWAL_ROWS` and `NEWS_CAPACITY_PAYLOAD_BYTES` consistently across instances.

Collection and cleanup share a transactional ledger lock. Duplicate observations do not spend again; an over-budget batch rolls back and returns `NEWS_028`, preserving source checkpoints. Collector failures mark `STORAGE_FULL` with a 15-minute cooldown. Cleanup returns bytes and deleted row slots atomically. Admin operations expose aggregate usage, measured counts and `accountingMatches`; they never reset a mismatched ledger. Upgrade with all old News writers stopped. This is a collection-data budget, not a quota for all database tables or physical disk usage. See [batch 17 verification](../../docs/verification/daily-news/2026-10-18-collection-capacity.md).


### Retained control identities

V9 adds admission budgets for 1,000 subscription owners, 1,000 target-setting owners, 100,000 daily runs and 100,000 preview identities. Configure `NEWS_CAPACITY_SUBSCRIPTIONS`, `NEWS_CAPACITY_TARGET_OWNERS`, `NEWS_CAPACITY_DAILY_RUNS` and `NEWS_CAPACITY_PREVIEW_IDENTITIES` consistently across instances. Region bindings and editions share an identity. Scheduled content uses the accepted run reservation, so a full budget does not prevent existing work from finishing.

New work over quota returns `NEWS_029` without advancing its schedule. Cancellation, disabling and content expiration retain identity slots; existing operations remain available. Delivery targets are captured once, including an empty set while delivery is disabled. Admin operations expose `controlCapacity` with read-only accounting comparison. Stop all old News writers before V9 migration. Legacy content identities are conservatively charged to preview capacity; this is not a whole-database byte quota. See [batch 18 verification](../../docs/verification/daily-news/2026-10-18-control-capacity.md).


### Source and region registry admission

V10 reserves finite global rows for sources (1,000), immutable source-pack versions (10,000), region nodes (1,000), region jobs (100,000), and direct observations (10,000). Configure `NEWS_CAPACITY_SOURCES`, `NEWS_CAPACITY_SOURCE_PACKS`, `NEWS_CAPACITY_REGION_NODES`, `NEWS_CAPACITY_REGION_JOBS`, and `NEWS_CAPACITY_DIRECT_OBSERVATIONS` consistently across instances. New source/pack JSON is capped at 32 KiB and observation JSON at 8 KiB. Existing payloads are not rewritten.

Each accepted region job reserves exactly one report slot. Completing or replaying it does not acquire the global registry capacity lock, even when every quota is full. New jobs, node cursors and their outbox commit together; rejected admission returns `NEWS_030` without orphan cursors. Direct reports cannot borrow job slots, and one job cannot store multiple reports. Source enable/disable and exact replays remain available at capacity. Frozen edition regions remain unchanged.

Admin operations include `registryCapacity` and a read-only accounting comparison. Fixed metrics expose usage/limits with the existing private monitoring policy; the supplied 80% alert requires deployment validation. Retained identities are never automatically released. V10 backfills old rows and links existing job reports; duplicate old report assignments or orphan polling cursors stop migration. Stop old writers before migration and use a V10-aware rollback artifact. This is not a physical disk quota. See [batch 20 verification](../../docs/verification/daily-news/2026-10-18-registry-capacity.md).
