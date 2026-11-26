# MyTools 每日新闻速报：调研结论与详细设计

> 2026-10-18 实施阶段修订：腾讯官方发送消息文档明确注明“主动推送能力于 2025 年 4 月 21 日起不再提供”。本文先前关于 QQ 每日主动消息的方案已失效；当前实现关闭 QQ 定时投递并返回平台能力限制，不调用旧会话回复绕过限制。邮件和飞书继续实施，QQ 用户请求后获取日报属于可选后续入口。依据：[腾讯官方文档](https://github.com/tencent-connect/bot-docs/blob/main/docs/develop/api-v2/server-inter/message/send-receive/send.md)。新版目的地和投递契约见 [Messaging v2](../../service/contracts/messaging-outbound-v2.md)。

版本：v1.0 · 日期：2026-10-18 · 状态：实施中，进度见[实施计划](2026-10-18-daily-news-implementation-plan.md)。

目标用户：首期个人使用，数据和接口预留多用户订阅。

已确认的本地口径：**以执行新闻任务的服务节点公网出口 IP 所在地为本地新闻地区。**

配套材料：[来源与渠道调研](../research/2026-10-18-daily-news/research.md)、[8 个公开端点实测记录](../research/2026-10-18-daily-news/source-probe.json)。

实施进度（2026-10-18）：已完成基础内容链路、GitHub/HN/DEV 来源与趋势快照，以及第三批节点地区报告、来源包和冻结链路。实际执行节点定位尚未验收；当前详情采用原文节选，持续调度、订阅和三渠道投递仍待接入。下文描述完整目标设计，实际实现以[服务说明](../../service/news-digest-service/README.md)和[第三批验证](../verification/daily-news/2026-10-18-node-region.md)为准。

## 1. 设计结论

建议新增独立的 news-digest-service，负责采集、新闻事件聚合、排名、摘要、日报快照和订阅；复用项目已有的 Scheduler / Executor 执行任务，通过 Messaging 统一投递邮件、飞书和 QQ。

每天生成技术、国际、国内、本地四个分类，每类目标 20 条独立事件，第 1—10 条提供有来源的详情，第 11—20 条提供标题和来源链接。四类合计最多 80 条、40 篇详情。完整日报以网页/邮件承载，飞书和 QQ 默认发送分类索引及重点摘要，并链接到同一份完整日报；支持切换为分片全文发送。

关键决策：

1. 技术类使用最近七日热点，每天刷新，兼顾 GitHub 和技术论坛；其他三类默认统计过去 24 小时。
2. “Top”定义为已接入来源范围内、按公开规则选出的分类热点，不能宣传为全网客观排名。
3. 先把多家媒体报道聚合为同一事件，再排名；转载不能反复占位或放大可信度。
4. 详情由正文、官方公告、项目 README/Release 等可核验材料生成，标题不足以支撑详情。
5. 本地地区由指定新闻执行节点解析并保存快照；同一期任务重试或更换 Executor 时不改变地区。
6. 通过官方 API / RSS 建立基础来源，HTML 和热榜作为可停用的适配器。逐项管理来源使用范围。
7. 持续采集与每日生成分离，避免每天早晨才抓一次遗漏当天热点。
8. 日报内容生成一次，多渠道复用；内容发布状态与各渠道投递状态独立。
9. 以 MySQL、事务 Outbox 和现有任务系统完成首期，不增加 Kafka、Elasticsearch 或向量数据库的必需依赖。
10. 首期先实现内容闭环和邮件，再接飞书与 QQ；正式承诺三渠道可用前，必须完成主动发送契约与平台联调。

## 2. 需求边界和默认值

### 2.1 已确认需求

| 项目 | 设计口径 |
| --- | --- |
| 分类 | 技术、国际、国内、本地，可扩展其他分类 |
| 技术内容 | 本周 GitHub 及其他技术论坛最新热点 |
| 新闻内容 | 国内外新闻门户、原始公告及论坛线索 |
| 每日数量 | 每类目标 Top20，前 Top10 有详情 |
| 发送渠道 | 邮件、飞书、QQ；统一投递接口预留其他渠道 |
| 本地位置 | 执行新闻任务的服务节点公网出口 IP 对应地区 |
| 用户规模 | 个人先用，预留多用户订阅 |

### 2.2 本方案采用的默认值

以下是建议默认值，可通过订阅配置调整，不是用户已经指定的时间和参数。

| 参数 | 默认值 |
| --- | --- |
| 输出语言 | 简体中文；保留外国机构、人名、项目名及原文链接 |
| 日报发送时间 | 每日 08:05，Asia/Shanghai |
| 新闻资格截止时间 | 当日 07:30 |
| 国内、国际、本地窗口 | 前日 07:30 至当日 07:30，左闭右开 |
| 技术窗口 | 截止时间前滚动 7 × 24 小时，展示名为“近七日技术热点” |
| 自然周选项 | 可切换周一 00:00 至截止时刻；不能与滚动七日混用 |
| 详情长度 | 每条约 180—300 个中文字符，另加来源和必要要点 |
| 邮件模式 | 完整日报：前十详情、后十标题 |
| 飞书 / QQ 模式 | 分类索引 + 重点摘要 + 完整日报链接；可选分片全文 |
| 重复事件 | 没有实质进展则降权；重大更新允许再次上榜并显示变化 |
| 本地范围 | 优先城市级，定位精度不足时明确降级省/州 |
| 新地区没有来源包 | 显示“本地来源待补全”，不自动抓取未知网站 |

日常信息量较大：40 条详情按 220 字估算约 8,800 字，再加标题与来源，通常超过一万字。因此“速报”首页提供短总览和分类目录，完整内容可展开阅读，避免消息渠道默认刷出数十条长消息。

### 2.3 首期不包含

实时突发新闻告警、自动投资建议、付费墙绕过、任意网站全量爬取、音视频新闻转写、全网舆情监测、公众商业新闻分发，以及对所有城市保证足量本地新闻。它们不影响本次个人日报能力的主链路。

## 3. 现有项目能力与必须补齐的部分

本节基于当前本地工作区代码；没有据此推断生产服务已部署或开关已开启。

| 能力 | 已存在 | 本次增量 |
| --- | --- | --- |
| Java 21 / Spring Boot / Maven | 项目及服务化工作区已有规范 | 新建独立服务，遵守 service/AGENTS.md |
| 定时与异步任务 | Scheduler 有 cron、misfire、重叠、租约、取消和节点标签 | 新增新闻任务定义、任务包和执行节点亲和性 |
| 统一消息 | Messaging 有 owner、幂等投递、尝试记录、Outbox | 日报投递意图、频道能力检查与对账 |
| 邮件 | EmailDeliveryProvider 已发送纯文本 | HTML 双版本、日报模板；先保留纯文本可用 |
| 飞书 | ChannelType 中没有 FEISHU | 新增渠道、Webhook provider、凭据绑定、限流及结果映射 |
| QQ | 官方 Connector 有主动请求底层能力和持久化 WAL | 新增无原消息依赖的主动发送契约与 QqDeliveryProvider |
| 出站 Provider | 当前一般 DeliveryProvider 实现为邮件；QQ 是 InboundReplyProvider | 不能仅填 channelType=QQ 就认为主动投递已可用 |
| 任务执行 fence | DeliveryService 仍检查 send_email 步骤名 | 扩展通用发送任务时同步调整校验与任务定义 |
| 订阅和日报页面 | 本轮未发现完整新闻领域实现 | 新建分类、订阅、历史日报、来源和运维页面 |

详细文件证据见[调研第 7 节](../research/2026-10-18-daily-news/research.md#7-本地代码证据与缺口)。新服务不加入根 Maven 构建，不在设计阶段更改既有生产入口。

## 4. 来源策略

### 4.1 来源分层

| 层级 | 用途 | 代表来源 | 处理原则 |
| --- | --- | --- | --- |
| A：第一手事实 | 详情、事实核验 | 官方公告、机构发布、项目 Release | 保存发布日期、生效时间、版本和原始出处 |
| B：新闻与专业内容 | 新闻候选与详情材料 | 中新网、获适用权限的国际媒体、地方融媒体 | 记录原创媒体与转载链，遵守各源许可 |
| C：讨论和热榜 | 热度和发现线索 | HN、V2EX、DEV、门户热榜 | 与事实证据分开；讨论量不能直接证明事件 |
| D：聚合基础设施 | 接口适配与补充发现 | RSSHub、NewsNow、GDELT | 保留上游真实来源，不将聚合器计作独立媒体 |

来源准入表至少记录：来源标识、发布者/媒体集团、官方入口、类别、语言、地域范围、采集方式、请求预算、更新周期、时间可信度、允许字段、正文保留期限、是否允许模型处理与摘要分发、适配器版本、最后复核日期、当前状态。

状态包括 PENDING、ACTIVE、DEGRADED、PAUSED、RETIRED。技术可达性与使用权限是两个独立字段，未知权限不能自动视为允许。

发布时间缺失时标记 UNKNOWN，抓取时间只说明首次观测，不自动证明是新新闻。明显未来时间、只改排版的 updatedAt 及重复转载需要校验；正文中的实质进展才可重置事件的新鲜度。

### 4.2 建议首批来源

- 技术：GitHub 仓库 API + Trending 补充发现、HN、DEV，以及选定技术项目官方发布；V2EX 在令牌准备后加入。
- 国内：中新网 RSS 作为基础候选，再接入至少两家通过准入的媒体或官方发布源，避免单一来源主导。
- 国际：中新网国际、BBC RSS 及 Guardian API 等候选中通过使用范围核查者；详情优先原始发布和允许加工的正文。
- 本地：根据节点地区选择地区来源包，包含市/省政府公开信息、交通/气象/应急、当地媒体和少量社区线索。

本机已验证 GitHub、HN、DEV、中新网和 BBC 的相关公开入口能返回数据；不代表生产网络或正文授权已验证。人民网 world RSS 虽然返回 200，但本次全部 100 条记录中的最新日期为 2025-06-05，因此排除这个具体地址。[实测记录](../research/2026-10-18-daily-news/source-probe.json)

### 4.3 采集频率

| 类型 | 建议频率 | 自适应规则 |
| --- | --- | --- |
| 高频新闻 RSS / 门户列表 | 10—20 分钟 | 列表经常全部换新时缩短周期；遵守来源预算 |
| HN / 技术论坛 | 20—30 分钟 | 只更新活跃候选，避免重复抓取全部评论 |
| GitHub Trending | 2—4 小时 | 结构变更熔断，转入其他发现源 |
| GitHub 候选仓库汇总 | 6 小时 | 初期候选池上限约 200 个，按 API 预算调整 |
| 地方公告 | 30—60 分钟 | 按真实更新频率调节；不是全天必有更新 |
| IP 地区 | 每日及出口变化时 | 对同一出口缓存 24 小时；每期冻结快照 |

实现 ETag / Last-Modified、内容哈希、重试退避和每域名并发限制。RSS 若仅保留 30 条，当天一次抓取无法恢复已被挤出的旧条目，因此必须持续增量入库。

### 4.4 适配器边界

统一输出 NormalizedSourceItem：sourceId、externalId、canonicalUrl、originalTitle、language、publishedAt、updatedAt、fetchedAt、publisherId、originPublisherId、summaryText、contentRef、metrics、regionHints、rightsPolicyId、parserVersion。

抓取、解析与模型处理分离。列表只负责候选发现；前排候选再获取正文。首期建议 Java HttpClient 负责有界网络请求、ROME 解析 RSS/Atom、jsoup 进行 HTML 解析与清洗，不使用已弃用的 ROME Fetcher。[ROME 官方说明](https://rometools.github.io/rome/)、[jsoup 官方说明](https://jsoup.org/)

复杂正文提取需要时，再以版本化 Python 任务包扩展现有 Executor；不为首期另建 Python 常驻服务。需要登录、验证码或浏览器大规模渲染的来源先不纳入核心可用性承诺。

## 5. 执行节点 IP 与本地新闻

### 5.1 定位对象

“执行任务服务 IP”解释为：**负责该订阅新闻生成任务的指定执行节点，其新闻任务网络环境对外访问时使用的公网出口 IP。**

不使用用户浏览器 IP、桌面电脑 IP、Gateway 入站地址、Docker 内网地址或随机采集子任务的 IP。若节点使用 NAT 或显式代理，定位到 NAT / 代理出口；页面标明“服务出口 IP 定位”。国内新闻默认范围与发送时区不随出口所在地自动改变。

### 5.2 解析流程

1. 新闻任务绑定固定的执行节点策略，例如 requiredNodeLabels 中的 news-region-primary 标签；标签不能被当作唯一节点约束，部署时指定一个主地区节点，记录实际选中节点。
2. 由该节点的同一网络命名空间和出口配置执行地区解析。优先可信出口探测 + 本地 GeoLite City 库；也可使用可配置的 City/me 服务。需要账户的服务通过 Secret 引用配置。
3. 校验为公网 IPv4 / IPv6，获得国家、省/州、城市、GeoNames ID、accuracyRadiusKm、数据库/供应商版本和解析时间。
4. 映射至系统内部 regionId，选择已登记的 region_source_pack。
5. 在生成前固化 regionSnapshotId；本期所有本地来源采集、排名、详情与重试使用同一快照。
6. 若主节点不可用而改用备节点，只有新一期在重新解析后才可切换地区；当前期继续使用已保存快照，采集子任务无需物理位于该城市。

MaxMind 明确说明 IP 位置可能缺少城市，并提供定位半径；不应把国家级坐标当作城市地址。[官方定位说明](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data/)、[Web Service](https://dev.maxmind.com/geoip/docs/web-services/)

### 5.3 精度与故障规则

| 情况 | 默认处理 |
| --- | --- |
| 城市字段存在、与省/州一致、精度半径满足配置 | 使用城市；建议初始半径阈值 50 km，属于工程默认值，不是精度保证 |
| 半径过大或城市缺失，但省/州可靠 | 输出“本地（省/州级）”，使用对应来源包 |
| 只有国家信息 | 本地分类标记不可用，不扩成整个国家再称本地 |
| 出口未变，定位服务暂时失败 | 可使用不超过 7 日的最近成功地区，显示缓存时间与降级状态 |
| 出口已经变化 | 不沿用旧 IP 的缓存；重新解析，失败则本地暂不可用 |
| IPv4 / IPv6 或不同解析器给出不同城市 | 按实际配置的主出口族固定本期；无法确认时省级降级并保留冲突诊断 |
| 出口城市变化 | 下期自动切换并在日报提示；旧期不可变；新城市无来源包则显示覆盖不足 |
| 海外节点 | 使用当地城市/州新闻并翻译；国内栏仍保持中国新闻 |

地区信息用于新闻范围，不用来精确定位个人。日报只展示地区与精度级别，不展示完整公网 IP；原始 IP 如需运维保存应加密、短期保留，日志只含节点标识与 IP 指纹。

### 5.4 地方来源包

region_source_pack 保存 regionId、parentRegionId、语言、时区、政府/交通/气象/媒体源、允许的上级补充范围及已验证日期。国内地区可映射行政区划标识；海外地区使用国家 + 省/州 + GeoNames ID。

首期只为实际执行节点的地区维护来源包；未来扩展多节点或用户自选地区时逐步增加。不能因为能查到任意 IP 城市，就声称所有城市都有足量新闻。

本地匹配优先依据事件发生地、影响范围、机构辖区和公告对象。仅在新闻中提到城市名，不足以归入本地；同名城市、公司名称中的地名、媒体总部所在地需要消歧。

## 6. 分类、去重与热度排名

### 6.1 分类规则

| 分类 | 入选口径 | 排除或降权 |
| --- | --- | --- |
| 技术 | 开源项目、开发技术、AI/安全/系统/数据库等专业热点及重要技术发布 | 纯营销、无实质更新的旧教程、与技术无关的论坛生活帖 |
| 国际 | 主要发生于境外或具有跨国公共影响的事件 | 同一事件被多个媒体转载，不重复入选 |
| 国内 | 中国范围的重要公共事件、经济社会与政策新闻 | 无可靠来源的自媒体传闻、只换标题的旧闻 |
| 本地 | 对节点所在城市/省州居民有直接影响的事件 | 无地域关联的全国热搜、纯粹在文中提及地名 |

事件可有多个标签，但同一份四类日报默认只在一个分类占排名。直接影响当地的事项优先本地，专业技术事项优先技术，其余按事件主范围归入国内或国际；其他栏目可放“相关事件”链接，不重复占 Top20。该规则是展示策略，不否认事件具有多重属性。

### 6.2 去重与事件聚类

1. URL 归一化：去除明确的追踪参数，保留有语义的 ID 参数；规范 canonical 和重定向后的官方 URL。
2. 文档级去重：sourceId + externalId、canonicalUrl 哈希、正文哈希共同识别同稿。
3. 转载识别：来源署名、原文链接、正文相似度建立 originalPublisher / wireStoryId；同一通讯稿只算一个独立事实来源。
4. 事件级聚类：实体、时间、地点、动作、版本号和标题相似度召回候选；对不确定项才使用多语言 embedding / 模型裁决。
5. 约束防误合并：同公司不同产品、同城市不同事故、同项目不同重大版本不因词语相似直接合并。
6. 保存可撤销的 membership、聚类版本及 merge/split 审计；支持人工拆分误聚类。

相似度阈值必须用中英混合样本校准。首期无需向量数据库：按时间/实体分桶，内存计算有界候选，向量字段可选存储；规模确有需要再引入专用索引。

### 6.3 可解释排名

所有分量归一化到 0—1。以下权重是首版待调参方案，不是研究证明的通用最优值。

~~~
NewsScore = 100 × (0.25F + 0.25C + 0.20H + 0.15A + 0.15I)
LocalScore = 100 × (0.20F + 0.20C + 0.15H + 0.15A + 0.10I + 0.20L)
TechScore = 100 × (0.30M + 0.20E + 0.20Q + 0.15F + 0.15N)
~~~

| 分量 | 定义及实现 |
| --- | --- |
| F：时效 | 以事件新事实/原文发布时间计算半衰期衰减；国内国际本地初始 18 小时，技术初始 72 小时；爬取时间不充当发布时间 |
| C：独立覆盖 | 对原创媒体/第一手来源去重后计算，例如 min(1, log(1+n)/log(6))；一个源的转载不能增加 n |
| H：公共关注 | 来源内部的榜单位置、上榜时长、互动增量等先转换成同源同窗口分位数；缺失值使用带低证据标记的中性先验 |
| A：证据可靠度 | 依据原始材料可获得性、来源类型、是否有相互印证和更正记录配置；不能理解为某媒体永远正确 |
| I：公共影响 | 有界标签评分：影响范围、持续时间、公共服务变化等；模型必须输出理由，不直接自由打总分 |
| L：本地相关度 | 城市内直接影响最高；明确影响本地的省级事项次之；地理不相关的直接排除 |
| M：技术增长 | 仓库按观测期 Star 净增长/速度，论坛按同源互动增长与热门分位；缺测明确标记，不跨单位相加 |
| E：技术曝光 | 多个独立技术社区/官方发布的出现情况和持续热度，转载去重 |
| Q：技术证据质量 | README、正式发布、可核验用途、维护状态等；不把 README 自述当作实测性能结论 |
| N：新颖度 | 新项目、实质版本升级、首次进入日报或发生有证据的新进展 |

H 和 M 的计算在来源/信号类型内部完成。例如 500 次 HN 投票和 5,000 个 GitHub Star 不可相加；只有完成分位数转换后才能进入事件评分。没有热度指标的政府公告仍可通过 A、I、L 入选，不伪造浏览量。

对多来源热度使用独立来源组中最高的少量信号聚合，限制单个平台的贡献；样本很少时采用固定中性先验并标注不足。把字段、原始值、归一化方法、缺失状态、权重与最终分数写入 score_breakdown。

### 6.4 GitHub 周热点

- 候选发现：Trending、主题 Search、关注组织的 Release、技术论坛提到的仓库共同构成候选池。
- 每 6 小时保存总 Star 快照。只有窗口两端存在合格快照时才计算净增量；记录实际观测跨度与采样间隔。
- 七日净增长 = 当前快照 Star − 七日前基准快照 Star；允许出现负值，展示保留净值，增长评分可按零下限处理。
- 新纳入观察的项目显示“已观测 36 小时”等真实跨度，不补造七日历史、不按比例冒充真实周增量。
- Trending 显示的“本周 Star”与系统快照值使用不同字段和来源标签；两者不合并成一个真假难辨的指标。
- pushed 时间代表代码推送，不代表发布事件；created 时间代表创建，不代表最近走红；总 Star 只作背景信息。
- 技术候选按照 GitHub/论坛/官方发布子池校准，最终受多样性约束，避免整个技术榜被大型仓库占满。

### 6.5 多样性、跨日重复和数量不足

建议每类 Top20 中同一主来源最多 6 条，技术类初始目标为约 8 条开源项目、8 条社区/技术发布、4 条可弹性分配；这些是软目标，不能为了配额降低事实质量。重大事件可有记录地突破来源上限。

在基础分数上加入重复惩罚和多样性重排，保存 baseScore 与 selectionScore；按 selectionScore 形成最终序号。已经发送过且没有新事实的事件降权；再次入选时展示“持续关注”及本次变化。模型改写措辞不算新进展。

如果合格事件少于 20 条，显示“今日 13/20 条”；不复制同事件、不把多日前旧闻当今日新闻补齐。本地可提供单独的“省级补充”栏目并标识范围，默认不计入城市 Top20。

前 10 条缺少可用详情时维持事件排名并标记缺口，优先补充同事件其他合格来源；不能悄悄将重要事件移到第 11 位来制造详情达标。正常版要求前十全部具备详情；不足时仅能发布明确标记的降级版，记录 detail_count 与原因。

## 7. 详情生成与事实质量

### 7.1 通用详情结构

| 字段 | 内容 |
| --- | --- |
| 标题 | 忠实于事实的中文标题，保留原始标题供查看 |
| 一句话概述 | 说明发生了什么，不增加原文没有的结论 |
| 事实要点 | 2—4 条，覆盖时间、主体、行为、数字及地点 |
| 背景与影响 | 解释相关性；推断必须标为“分析”，无法支持则省略 |
| 最新进展 | 与前一期相比新增的事实，可为空 |
| 证据 | 1—3 个明确出处，显示媒体/机构、发布时间、原文链接 |
| 信息状态 | 官方发布 / 多源印证 / 单源报道 / 尚待核实 / 已更正 |

技术条目另含：项目或议题用途、适用场景、主要变化、语言/许可证（能够核验时）、热度指标的观测窗口及官方入口。不得声称系统亲自运行或评测过项目。

本地条目另含：影响地区、生效/截止时间、办理或出行入口、适用对象；交通和活动时间保留原时区，必要时换算展示。

### 7.2 生成流程

1. 轻量筛选后，每类预选约 40—60 个候选事件；对有机会进入前十者优先补正文。
2. 解析正文及出处，剔除导航、广告和重复段落；保留内容版本和摘要证据片段。
3. 从正文中提取事实对象、数字、时间和引用关系；材料不足标为 INSUFFICIENT。
4. 模型仅接收许可允许处理的材料，生成受 JSON Schema 约束的摘要与 citationRef。
5. 校验每个 citationRef 都指向输入材料；核对关键数字、日期和主体，检查额外无来源断言。
6. 对事故、冲突、公共卫生等易造成误解的信息，要求原始权威材料或独立交叉印证；否则保留归属表达和不确定性，必要时仅列标题。
7. 冻结通过验证的摘要版本，统一渲染邮件、网页与消息渠道。

语言模型负责归纳、翻译和辅助分类，不负责凭空补事实，不自由浏览或调用消息发送工具。网页中的提示词、脚本和“忽略前述要求”都是不可信正文。

### 7.3 模型与降级

首期使用可替换的 SummaryModelClient；具体供应商、模型和单价在实施时确定，不假定项目已有统一模型网关。按 source rightsPolicy 限制哪些内容可发送给外部模型。

缓存键包含 eventRevision + evidenceHash + rightsPolicyVersion + language + modelVersion + promptVersion + summarySchemaVersion。相同事件可跨渠道复用；不同用户的私有来源不能共享到公共缓存。

模型超时或预算耗尽时，先复用内容未改变的合格摘要，再使用可核验的抽取式要点；只有标题时明确“详情暂缺”。不得将降级内容标记为正常达标。

## 8. 系统架构与职责

~~~mermaid
flowchart LR
    U["个人订阅 / 后续多用户"] --> G["MyTools Gateway"]
    G --> N["News Digest Service"]
    S["Task Scheduler"] --> X["Task Executor"]
    X --> R["节点出口 IP 与地区解析"]
    X --> N
    N --> C["来源适配与受控抓取"]
    C --> P["官方 API / RSS / 获准页面"]
    N --> A["去重 / 事件聚类 / 排名"]
    A --> L["证据摘要 / 质量校验"]
    L --> D["不可变日报与网页"]
    N --> DB[("独立 MySQL schema")]
    D --> O["事务 Outbox"]
    O --> M["Messaging Service"]
    M --> E["Email Provider"]
    M --> F["Feishu Provider"]
    M --> Q["QQ Connector"]
~~~

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| News Digest | 来源配置、事件、排名、日报、地区策略、订阅、发送意图 | SMTP、Webhook 密钥与 QQ 协议细节 |
| Scheduler | cron、任务实例、租约、重试、超时、父子关系、节点约束 | 新闻排名和数据库内容操作 |
| Executor | 运行已发布任务包，携带 fence 调用受控内部 API | 直接改 Scheduler 数据库或持久化渠道 Secret |
| Messaging | 目标绑定、渠道适配、发送状态、尝试记录、限流 | 内容采集与新闻事实判断 |
| Gateway | 身份认证、owner 绑定、用户 API、阅读授权 | 接受客户端任意 ownerId 或直接公开内部 API |
| Automation | 后续处理“订阅、退订、补发”等授权入站命令 | 首期定时新闻生成的必经环节 |

新闻服务采用模块化单服务：source、region、ingestion、event、ranking、summary、edition、subscription、dispatch。数据库使用独立 mytools_news schema，迁移由 Flyway 管理。首期文本和结构化快照存数据库，较大且获准保存的证据正文可接既有 Storage Gateway，仅持有存储引用。

## 9. 每日执行与可靠性

### 9.1 推荐任务

| 任务名 | 触发方式 | 幂等业务键 | 关键限制 |
| --- | --- | --- | --- |
| news_resolve_region | 每日 / 出口变化 | 节点策略 + 解析时隙 | 解析失败只降级本地类 |
| news_collect_source | 来源周期 | sourceId + 时间桶 + 配置版本 | 分源限流、断点和独立熔断 |
| news_snapshot_repository | 每 6 小时 | repoId + 时间桶 | 不遍历 Star 用户名单 |
| news_build_edition | 每日截止后 | profileHash + 业务日期 + revision | 固化地区、候选、规则与材料快照 |
| news_enrich_event | 生成任务的子任务 | eventRevision + 摘要缓存键 | 有界正文与模型预算 |
| news_dispatch_edition | 发布后 | 订阅 + 日报版本 + 目标 + partIndex | 内容不重算，同键不同载荷拒绝 |
| news_reconcile_delivery | 周期 / 发送后 | dispatchId | 读取 Messaging 终态，不重复发件 |
| news_cleanup_retention | 每日低峰 | 策略版本 + 日期 | 保留审计关系，按来源权限清理正文 |

同一类型的周期触发使用现有 Scheduler。订阅量增长后，通过少量 tick 任务扫描 due subscription，按 profileHash 合并生成，而非每个订阅复制全部采集任务。

Scheduler 参数只携带 sourceId、collectionRunId、editionId、dispatchId 等不透明标识及非敏感版本号；来源凭据、收件地址、正文、Webhook、完整公网 IP 不进入任务参数或结果。

业务作业创建与 news_task_outbox 在同一事务中提交，再由 Relay 使用稳定幂等键向 Scheduler 注册实例；响应丢失继续同键恢复。避免“数据库已有生成请求，但调用 Scheduler 前进程退出”后永久丢任务。

### 9.2 时间线

| 时间（建议默认） | 工作 |
| --- | --- |
| 全天 | 增量采集、指标快照、候选预提取 |
| 06:00 前后 | 检查主地区节点、出口及来源包；地区变化及时准备 |
| 07:30 | 冻结新闻入选时间窗口，建立日报构建实例 |
| 07:30—07:45 | 事件聚类、最终候选和排名 |
| 07:45—07:55 | 前十详情补充、验证与渲染 |
| 08:00 前 | 发布不可变阅读版，确定 NORMAL / DEGRADED 质量状态 |
| 08:05 起 | 按渠道预算推送，可加入小幅分散调度 |
| 08:10 | 检查投递结果；失败只重试相应目标/分片 |

入选截止时间与证据取得时间分别记录。截止后取得的正文如包含截止后的新进展，应留给下期或明确作为更正，不伪称 07:30 已知事实。构建完成时冻结 evidenceManifest，后续重试从持久化版本重放，不重新抓取并改变同一期内容。

建议构建任务使用 RUN_ONCE misfire + SKIP/QUEUE 重叠策略；停机多日后不补发所有过期日报。恢复时仅生成最新到期一期；若超过 12 小时的新鲜度发送期限，保留归档并显示未按时发送，是否补发由明确操作触发。

### 9.3 状态分离

- 构建：PENDING → COLLECTING → RANKING → ENRICHING → READY → PUBLISHED；失败为 FAILED，人工中止为 CANCELLED。
- 质量：NORMAL / DEGRADED / EMPTY，独立于构建状态；本地不足不阻止其他栏目发布。
- 发送意图：PENDING → SUBMITTING → ACCEPTED → PROVIDER_ACCEPTED；异常进入 RETRY_WAIT / UNKNOWN / FAILED / CANCELLED / EXPIRED。

现有 Messaging 的 DELIVERED 在日报产品中解释为“渠道已接受”，不自动声称“已阅读”。如渠道将来提供可靠送达回执，可另存 receipt，不改写既有语义。

发布动作将 edition 与 dispatch_outbox 写入同一短事务；Relay 调用 Messaging 成功后记录 deliveryId，丢失响应时使用同一幂等键查询/重放。每次执行使用租约和 fence，外部网络调用不占数据库事务。

### 9.4 外部发送不能承诺严格仅一次

本地数据库可以保证一个逻辑发送意图，外部 SMTP、飞书 Webhook 和 QQ 主动消息在“服务端已接受、响应丢失”时仍可能存在不确定结果。

默认日报策略：确定未发送的失败可退避重试；结果未知进入 UNKNOWN，先对账，不盲目立即重发。无查询能力的渠道在个人版默认保留网页可读并提示待处理，可配置有界重发且标注可能重复。稳定 Message-ID、msg_seq 和本地 WAL 均不能单独证明跨平台 exactly-once。

这需要在扩展 Messaging 时增加明确的不确定结果映射，而不只是把所有 RuntimeException 当作可重试失败。

## 10. 数据模型

以下是建议逻辑模型，实际 DDL 在实施任务中细化；所有时间保存 UTC，业务日期按订阅时区计算。

| 表 | 核心字段 | 约束 / 作用 |
| --- | --- | --- |
| news_source | id、publisher_group、adapter_type、region_scope、config_version、rights_policy、status | 配置按版本冻结；Secret 只保存引用 |
| news_fetch_run | source_id、time_bucket、config_version、cursor、status、freshness、error_code | 唯一(source_id, time_bucket, config_version) |
| news_document | source_id、external_id、canonical_url_hash、published_at、origin_publisher_id | 来源内身份唯一；URL 去重不能丢失不同来源署名 |
| news_document_version | document_id、content_hash、captured_at、content_ref、rights_version、expires_at | 唯一(document_id, content_hash)，保留证据版本 |
| news_metric_snapshot | source_id、subject_type、subject_id、metric_type、observed_at、time_bucket、value、window | 唯一(source_id, subject_type, subject_id, metric_type, time_bucket, window) |
| news_event | id、event_type、entity_keys、event_time、region_id、current_revision | 表示事实事件，不等于单篇文章 |
| news_event_document | event_id、event_revision、document_version_id、origin_group、match_reason | 保存可审计的版本化聚类成员 |
| news_summary | event_id、event_revision、cache_key、structured_content、citations、quality、model_version | cache_key 唯一，避免重复模型调用 |
| news_region_snapshot | node_policy_id、actual_node_id、egress_fingerprint、region_id、precision、provider_version、resolved_at | 每期引用固定快照，完整 IP 不公开 |
| news_region_source_pack | region_id、version、source_ids、fallback_scope、verified_at | 唯一(region_id, version)，控制本地来源覆盖 |
| news_subscription | owner_id、categories、timezone、send_time、local_mode、node_policy_id、profile_version、status | owner 隔离；首期 local_mode=EXECUTOR_EGRESS_IP |
| news_subscription_target | subscription_id、destination_id、channel、render_mode、enabled | destination 属于同一 owner；目标由 Messaging 验证 |
| news_edition | business_date、profile_hash、revision、region_snapshot_id、cutoff、manifest_hash、status、quality | 唯一(business_date, profile_hash, revision) |
| news_edition_item | edition_id、category、rank、event_id、event_revision、summary_id、score_breakdown | 唯一(edition_id, category, rank)；同一期事件只占一个排名 |
| news_task_outbox | job_type、job_id、task_name、idempotency_key、scheduler_task_id、status、next_retry_at | 任务提交意图持久化，恢复时不创建第二个逻辑任务 |
| news_dispatch_outbox | edition_id、subscription_id、destination_id、part_index、payload_hash、delivery_id、lease、attempt、status | 唯一逻辑发送键；载荷冻结 |
| news_operation_audit | owner_id、actor、operation、target_id、before_version、after_version、reason | 重排、合并、纠错、补发、停用有记录 |

幂等键建议取稳定语义串的 SHA-256，例如 news:dispatch:<hash>，输入包含 ownerId、subscriptionId、editionId、revision、destinationId、channel、partIndex、renderVersion。按目标分片而不是按整个日报“失败重发全部”。

profileHash 包含分类、窗口、语言、地区快照、来源集版本、排名版本及内容偏好；不包含收件人地址。相同内容档案可共享生成结果，订阅与投递仍独立。私有来源引入 visibilityScope / ownerId 后，必须成为内容缓存及查询过滤条件。

建议索引：文档(source_id, published_at)、事件(region_id, event_time)、指标(subject_id, observed_at)、订阅(status, next_due_at)、Outbox(status, next_retry_at)。首期不分库分表，观察实际体量再决定分区。

## 11. API 与契约

### 11.1 用户 API（新增建议）

| 方法与路径 | 用途 | 约束 |
| --- | --- | --- |
| GET /api/v1/news/categories | 分类及数量配置 | 返回可用能力 |
| GET /api/v1/news/local-region | 当前节点策略解析地区、精度和来源覆盖 | 不返回完整 IP、Secret |
| POST /api/v1/news/subscriptions | 创建个人订阅 | owner 来自 Gateway 身份；验证目标所有权 |
| PATCH /api/v1/news/subscriptions/{id} | 修改、暂停、恢复 | If-Match / version 防并发覆盖 |
| GET /api/v1/news/subscriptions | 订阅及下一次发送时间 | 仅当前 owner |
| POST /api/v1/news/previews | 构建预览 | 异步 202，不创建发送意图 |
| GET /api/v1/news/editions | 历史日报 | 业务日期、质量状态、可读性 |
| GET /api/v1/news/editions/{id} | 完整结构化日报 | 校验订阅或分享授权 |
| POST /api/v1/news/editions/{id}/dispatches | 发送/补发已发布版本 | 明确绑定目标 + Idempotency-Key |
| GET /api/v1/news/dispatches/{id} | 各目标和分片结果 | 不暴露 provider 内部引用 |
| POST /api/v1/news/unsubscribe | 退订 | 登录或短作用域签名凭证，幂等 |

来源编辑、聚类修正、排除事件和精确死信恢复为管理员接口，不向普通订阅者开放任意 URL 抓取功能。

nodePolicyId 必须引用管理员配置且当前 owner 有权使用的节点策略；请求不能自定义执行标签、主机地址、出口代理或脚本内容。预览接口响应提供 jobId 与状态查询入口，完成后返回 editionId。

建议订阅请求示例，所有代码标识符与配置值使用英文：

~~~json
{
  "categories": ["TECH", "WORLD", "DOMESTIC", "LOCAL"],
  "timezone": "Asia/Shanghai",
  "sendTime": "08:05",
  "headlineLimit": 20,
  "detailLimit": 10,
  "techWindow": "ROLLING_7_DAYS",
  "localMode": "EXECUTOR_EGRESS_IP",
  "nodePolicyId": "news-primary",
  "language": "zh-CN",
  "targets": [
    {
      "destinationId": "018f0000-0000-7000-8000-000000000001",
      "channel": "EMAIL",
      "renderMode": "FULL"
    }
  ]
}
~~~

### 11.2 内部 API（新增建议）

- POST /internal/v1/news/collections/{runId}/execute
- POST /internal/v1/news/region-resolutions/{runId}/complete
- POST /internal/v1/news/editions/{editionId}/build
- POST /internal/v1/news/enrichments/{jobId}/execute
- POST /internal/v1/news/dispatches/{dispatchId}/reconcile

这些接口接受 Scheduler 授权的任务身份、步骤版本、有效租约和 fence，验证业务标识匹配。实例重放不能改变 owner、地区、来源版本或正文快照。

### 11.3 Messaging 增量

保留现有 /internal/v1/deliveries 邮件契约，新增版本化的出站契约以承载 destinationId、contentType、结构化 content、payloadHash、idempotencyKey，禁止悄悄改变旧 body 的含义。

Messaging 建议增加 destination 绑定、验证和 capability 查询；绑定内容含 EMAIL 地址、FEISHU Webhook 或 QQ OpenID 等，由 Messaging 管理。News 只保存 destinationId。新增 QqDeliveryProvider 与 FeishuDeliveryProvider，并发布 message_send_channel_message 对应任务包和兼容的 execute fence。

QQ Connector 新入口明确 mode=ACTIVE、conversationType=C2C/GROUP、targetRef、text、idempotencyKey，无需 messageId；保留旧回复入口兼容已有自动化。接口应区分“已接受排队”与“平台已接受”，支持通过请求标识查询 WAL 终态。

错误码统一在新闻服务 common/ErrorCode.java 管理，例如 NEWS_001 来源不可用、NEWS_002 地区不可确定、NEWS_003 详情不足、NEWS_004 日报版本冲突、NEWS_005 渠道未绑定、NEWS_006 日报未发布、NEWS_007 模型预算耗尽、NEWS_008 越权或资源不存在。对外返回稳定 code 和英文 message，UI 负责中文展示。

## 12. 渠道内容与阅读体验

### 12.1 完整阅读版

页面包括：日期、统计截止、技术时间窗、本地地区及定位方式、四类目录、每类 1—20 排名、前十详情、来源、质量缺口和更正记录。

页面先展示每类最重要的 3 条摘要，展开后查看完整 Top20；第 1—10 条的“详情”直接定位到正文，第 11—20 条可打开原文。可查看历史日期、关注/屏蔽关键词、暂停推送，后续再加入内容反馈。

示意结构（不含真实新闻）：

~~~
每日新闻速报 · 2026-10-18
统计截至 07:30 · 技术近七日
本地：City / Region（服务出口 IP，城市级）

技术 20/20 · 详情 10/10
01. 项目发布重要更新
    一句话概述 → 事实要点 → 使用场景 → 原始来源
...
11. 标题 · 来源 · 原文
...
20. 标题 · 来源 · 原文

国际 / 国内 / 本地同样结构
来源覆盖、降级说明、生成时间、订阅设置
~~~

### 12.2 各渠道策略

| 渠道 | 默认发送 | 完整模式 | 工程要求 |
| --- | --- | --- | --- |
| 邮件 | 一封完整日报，目录 + 40 条详情 + 其余标题 | 同默认，可选择附件导出作为后续功能 | 先纯文本，后 multipart；主题带日期，来源链接保留 |
| 飞书 | 一条总览 + 每类一张分类卡；卡中含 Top20 标题及前几条短摘要 | 按分类拆成多张卡，分片包含前十完整详情 | 自定义机器人序列化请求内部预算 16 KB，平台文档上限 20 KB |
| QQ | 总览与按字数拆分的分类标题索引，附完整阅读链接 | 分片发送 40 条详情和其余标题 | 本地现有 Connector 上限 2,000 字符，建议正文预算 1,500 字符并预留分页信息 |

消息分片在发送前生成并冻结，禁止重试时重新按变化后的模板切片。每片都有日期、栏目、partIndex/partCount；按条目边界拆分，避免截断 URL 和 Unicode 字符。

默认飞书/QQ 是摘要与索引模式，完整日报中仍必须有每类 Top20 和前十详情，不能把“只推送三条”当成需求完成。若用户选择全文模式，则所有内容都通过分片发送，预览显示预计消息数量。

飞书大小与频控依据[自定义机器人文档](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot)；QQ 主动接收及 C2C/群频控依据[官方消息概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html)。QQ 的 2,000 字符是本项目现有实现限制，不能宣称是所有 QQ 消息类型的平台统一限制。

### 12.3 目标验证、退订与分享

邮件地址通过验证码或已有可信绑定确认；飞书 Webhook 由用户配置后通过显式测试验证；QQ 通过机器人会话绑定对应 OpenID，群目标需要验证操作者具备管理该绑定的权限。

默认阅读链接需要登录，首期个人使用最简单。群内无需登录的阅读能力通过独立、可撤销、带期限的分享授权实现，分享页面不含订阅身份、完整 IP、私有来源或渠道凭据；不能直接把个人 owner-bound 地址改为永久公开。

用户暂停/退订后取消尚未提交的投递意图，已经进入平台发送中的消息标记尽力取消。邮件的退订链接先进入设置或确认页；状态变更使用 POST，避免链接扫描器访问 GET 导致误退订。

## 13. 安全与来源使用范围

1. 抓取器仅允许已登记公网域名和 HTTP(S)，校验 DNS、IPv4/IPv6、每次重定向，拒绝内网、回环、云 metadata 和非网络协议；设置大小、时间和重定向上限。
2. RSS/XML 禁用外部实体与 DTD；正文与模型输出统一 HTML 清洗，链接禁止 javascript 等执行协议。
3. 模型输入明确区分指令与来源文本；输出引用只能选择已存在的 evidence ID，不能输出任意工具调用或伪造链接。
4. owner 由可信身份层传递；订阅、日报、目标绑定和投递都做 owner/分享授权检查，不仅依赖 ID 难猜。
5. 新闻源 API key、模型 key、Webhook、QQ Secret、SMTP 授权码只由对应服务保存 Secret 引用；日志不记录完整请求体。
6. 每个来源登记标题展示、正文缓存、摘要加工、外部模型处理、个人发送及公共分发的允许范围。没有通过的动作禁用，并使用其他合格证据源。
7. robots 和访问控制受尊重，不绕过登录、验证码及付费墙。公开可访问并不自动代表可摘编；例如新华网声明明确限制未经授权的转载与摘编。[官方版权声明](https://www.news.cn/copyrightad.htm)
8. 如后续转为面向公众的服务，另行评估内容许可、新闻服务适用要求和投诉更正机制；本版只实现个人订阅范围。

## 14. 保留、成本与容量

### 14.1 建议保留期

| 数据 | 初始策略 |
| --- | --- |
| 原始页面/正文证据 | 默认最多 7 日，来源条款更短则取更短；禁止保存时不落全文 |
| 规范化标题、来源与事件 | 默认 90 日；历史日报仍可保留指向来源的元数据 |
| 排名指标快照 | 90 日，足够计算七日趋势及复核 |
| 日报与生成摘要 | 默认 180 日，按来源权限和个人存储配置缩短 |
| 投递尝试、错误和操作审计 | 默认 90 日，不保存 Secret 或多余正文副本 |
| 完整出口 IP | 如确有排障需要，最多 7 日密文；日常只保留指纹和地区 |

清理正文后仍保存证据 ID、哈希和原始 URL；但此时无法承诺完整重放正文，审计页面必须显示证据已到期。许可撤销时可以停止展示相应摘要并留更正说明，不为“历史不可变”继续传播不可用内容。

### 14.2 容量估算

假设 20 个来源、平均 30 分钟轮询、每天约 2,000 条去重前候选、最终 40 篇新详情、1 个地区、1 个用户：

- 列表请求约 20 × 48 = 960 次/日；HN item、正文与 GitHub 汇总请求另算。
- 200 个仓库每 6 小时快照约 800 次仓库请求/日；需在小时内分散，并为发现查询留预算。
- 若保留 2,000 条 × 5 KB 元数据，约 10 MB/日；假设仅 200 条补充正文、每条 30 KB，约 6 MB/日。
- 元数据 90 日与正文 7 日的原始量约 0.94 GB，实际加索引、版本和备份可按 2—4 倍规划；均为估算，不是压测结果。
- 新服务可先按 1—2 vCPU、1—2 GB 内存试部署，数据库及既有服务资源另算；通过实测调整。

不能把所有源都套用相同请求预算，也不能认为 GitHub 总日量低就不会触发分钟或次级限流。

### 14.3 模型费用

不在设计阶段锁定供应商或购买套餐。用下式估算，价格参数采用实施时的有效报价：

~~~
DailyModelCost =
  inputTokens / 1_000_000 × inputPrice
  + outputTokens / 1_000_000 × outputPrice
  + embeddingCost
~~~

示例仅用于敏感性分析：40 篇 × 每篇 6,000 输入 token = 240,000；每篇 600 输出 token = 24,000，计入 25% 重试/校验余量后约 300,000 输入、30,000 输出。若假设每百万输入为 1 个计价单位、输出为 5 个单位，则约 0.45 单位/日、13.5 单位/30 日；价格翻十倍则费用翻十倍。这不是任何厂商报价，也不含额外分类、翻译或授权费用。

先用规则筛选、分层获取正文、合格摘要缓存和每期预算上限控制成本。多用户共享相同 edition/profile 可降低模型重复调用；不同地区的本地内容成本会增长。

个人首期不选 NewsAPI 作为主要付费依赖：其免费档只限开发测试且有延迟，调研时 Business 月付为 USD 449，并不提供完整新闻正文。[官方价格与限制](https://newsapi.org/pricing)

## 15. 观测、验收与失败场景

### 15.1 观测指标

- 采集：按来源统计请求成功率、解析成功率、最新发布时间距今时长、空列表、游标推进、预算耗用。
- 内容：每类候选数、事件数、正文可用率、详情达标率、独立来源组数量、重复率、分类/地域抽检。
- 地区：出口变化、定位精度、缓存年龄、来源包覆盖、当地有效新闻数量。
- 生成：各阶段耗时、模型 token/费用、缓存命中、证据校验失败、降级原因。
- 发送：目标/分片受理、渠道接受、未知、失败、重试和死信；这些指标不能全部统称送达率。

来源新鲜度阈值按类型设定：持续新闻源长时间无新记录告警；地方公告周末没有更新不直接判故障。告警合并、去重，只在故障或状态有意义变化时提示。

### 15.2 首期验收标准

| 验收项 | 标准 |
| --- | --- |
| 内容结构 | 正常版四类各 20 个独立事件，前 10 条详情完整；不足版准确展示实际数量和原因 |
| 证据完整 | 100% 详情含至少一个允许使用的出处；不能引用输入中不存在的材料 |
| 事实质量 | 从四类合计抽检至少 200 条事实陈述，严重无依据断言为 0；非关键错误率目标小于 2%，保留分母与样本 |
| 去重 | 人工标注至少 100 对同/不同事件，误合并和漏合并分别统计；最终日报明显重复占位为 0 |
| 排名合理性 | 同源指标可追溯；人工评估候选池重要事件的 Top20 覆盖，目标至少 80%，不以主观排名冒充全网真值 |
| GitHub | 不足七日的快照显示实际观测跨度；净增量不以总 Star 替代 |
| 本地 | 已知测试 IP 的地区映射、低精度降级、出口变化、海外地区与重试快照全部通过 |
| 时效 | 连续 7 日影子运行，默认时点后 5 分钟内完成可发布版本；正式 30 日目标按时发布率至少 95% |
| 渠道 | 三种渠道逐一验证默认模式和全文模式；失败不重发已经确认成功的其他分片 |
| 幂等 | 同键重复提交仅一条逻辑意图；同键不同正文拒绝；未知结果可见且不会无限盲重试 |
| 隔离 | 不同 owner 不能读取或操作彼此订阅、目标、投递和私有日报 |
| 退订 | 暂停后未开始的发送停止，进行中发送有明确的尽力取消结果 |

时间指标不强制等待每个外部源成功；只有质量门禁满足或明确进入降级版才可发布。“正常版必须足量”与“实际可降级发布”应分开统计。

### 15.3 必测失败场景

| 场景 | 预期结果 |
| --- | --- |
| Feed 返回 200 但日期停在去年 | 判内容停更，不进入今日候选 |
| 一个事件在十个门户转载 | 一个排名，独立来源数按真实原创来源计算 |
| 模型编造数字或引用 | 校验失败，重试或抽取式降级，不发布伪详情 |
| 新闻正文不可访问 | 保留可用标题/出处，前十详情缺口显式记录 |
| IP 定位到香港、东京等境外地区 | 本地跟随节点，国内范围和发送时区保持配置值 |
| 节点重试迁移到另一个城市 | 同一期仍使用原地区快照 |
| 本地当天仅 8 个有效事件 | 显示 8/20，不拿全国热搜凑数 |
| 飞书卡片超预算 | 发送前按条目拆片，保证序列化后大小合法 |
| QQ 主动接收关闭 | 对应目标明确失败/停用，其他渠道继续 |
| SMTP 已接受但响应丢失 | 标记未知、优先对账，不能宣传严格不重复 |
| 发送后正文被媒体更正 | 新 edition revision 及更正记录，按更正策略通知 |
| 停机三天后恢复 | 只处理最新有效一期，不向用户倾倒三天历史邮件 |

## 16. 实施分期与工作量

以下为一名熟悉本项目的开发者的初步工程估算，包含基础测试；数据许可确认、账号审核与 7 日观测按外部日历时间另算。

| 阶段 | 交付物 | 估算 |
| --- | --- | --- |
| P0：来源和地区验证 | 指定执行节点出口定位、当地来源包、首批 8—12 个来源准入、样本集 | 2—4 人日 |
| P1：内容闭环 | News 服务/schema、增量采集、聚类、排名、证据摘要、Top20/Top10 快照与简单阅读页 | 7—10 人日 |
| P2：个人可用版 | 订阅设置、Scheduler 编排、Outbox、邮件完整日报、预览/退订/补发 | 3—5 人日 |
| P3：飞书与 QQ | 通用 Messaging 契约、飞书 provider、QQ 主动入口、分片、未知结果处理与联调 | 4—6 人日 |
| P4：稳定性验收 | 7 日影子采集、内容抽检、故障演练、限流/成本调参和修复 | 3—5 人日工程工作，至少 7 日观测 |
| 合计 | 四类 + 三渠道 + IP 本地化的个人正式版 | 约 19—30 人日 |

不应把简单“抓热榜发消息”的一两天原型估算套到完整需求。工期主要取决于本地源覆盖、新闻详情可用性、内容许可和消息接口补齐，而非定时器代码。

### 16.1 推荐实施顺序

1. 先确认真实执行节点的地区及来源包，跑一周影子采集；与此同时完成 News 服务基础。
2. 先打通可审阅的网页和纯文本邮件，稳定 20/10 规则、证据和去重。
3. 将 HTML 邮件、飞书及 QQ 接到同一日报快照；多渠道不再次调用模型生成新版本。
4. 通过验收后启用个人自动发送；多用户阶段再开放目标绑定、配额、地区配置和内容档案共享。

### 16.2 预计改动范围

- 新增 service/news-digest-service/：Java 服务、独立 schema 迁移、内部与外部模型。
- 扩展 service/mytools-gateway/：新闻 API 路由及 owner 透传。
- 扩展 service/task-scheduler-service/ 和版本化任务包：新闻任务定义、节点约束及运行协议。
- 扩展 service/messaging-service/：目的地绑定、富文本内容、FEISHU/QQ 主动 Provider、未知结果。
- 扩展 service/qq-connector-service/：兼容现有回复的主动出站接口与状态查询。
- 现有管理前端：订阅、历史日报、来源健康与精确补发页面；首期不强制改动所有客户端。

所有 public Java 方法写中文 Javadoc，关键逻辑写中文注释，代码标识符与配置键使用英文，业务错误通过统一 ErrorCode。各子服务独立编译和验证，根工程不隐式引入新服务。

### 16.3 发布与回退

建议开关分为 NEWS_ENABLED、NEWS_COLLECTION_ENABLED、NEWS_PUBLISH_ENABLED、NEWS_DISPATCH_ENABLED，初始自动发送关闭。先仅采集和预览，再开启个人单目标，最后扩展其他渠道。

回退关闭发送与生成开关，保留既有日报阅读和证据；不删除历史发送记录、不重置幂等键。新增迁移优先向后兼容，不破坏既有注册邮件和自动化回复。

后续 Ubuntu 实施使用当前本地 SSH 配置解析目标并先验证连接，不通过飞书或 UbuntuHermes 传送部署命令；不在本文件记录主机凭据或生产环境内容。本次仅完成设计与公开来源调研，未进行部署。

## 17. 下一阶段需要确定的配置

产品方向已经明确，不需要再确认“个人使用”和“执行服务 IP 本地化”。实施前需要填入：

1. 实际新闻执行节点策略与出口网络配置，用于建立首个本地来源包。
2. 首期接收目标和渠道顺序；邮件、飞书群及 QQ 会话通过安全绑定流程录入。
3. 是否采用建议的 08:05 发送时间、滚动七日技术窗及消息摘要模式。
4. 允许使用的模型供应商、每月费用上限，以及来源内容的可处理范围。

以上均为实施配置项，不阻止按本设计拆分开发任务；在未配置接收目标及启用自动发送前，预览和影子采集可以独立完成。


## 18. 第四批实现对照（2026-10-18）

内容订阅、每日运行和持久任务 Outbox 已实现。当前具体运行策略为：每 owner 一份四分类内容订阅；配置变更从之后 cutoff 生效；每订阅每业务日唯一；停机只补最近一期，超过十二小时停止生成。Scheduler 生成任务通过在线租约/fence 校验，地区先冻结，日报与运行 READY 在同一事务发布。

本批没有增加消息目的地或发送动作。地区报告仍使用第三批节点令牌；周期采集、周期地区探测和真实节点联调待完成。新的 NEWS_SCHEDULING_ENABLED 默认 false；Scheduler V173 的任务/集群均默认关闭，包需要按现有不可变发布流程上线。

接口与运维以 [API 协议](../../service/contracts/news-digest-v1.md) 和 [服务 README](../../service/news-digest-service/README.md) 为准，验证证据见 [第四批记录](../verification/daily-news/2026-10-18-subscription-scheduling.md)。设计中的发送时间、模型摘要和多渠道目标依然是后续阶段，不能据此推断已上线。


## 19. 第五批实现对照与推进目标（2026-10-18）

周期来源采集及指定节点地区任务已完成工程实现与离线验证。来源由 News 受控抓取器执行，轮询游标、租约和退避持久化；地区定位仍由指定 Executor 实际运行，按 UTC 六小时桶调度。NEWS_PIPELINE_ENABLED 默认 false，并依赖采集/调度开关与节点配置；不自动启用来源或创建收件人。

地区包 1.1.0 增加在线执行授权和提交前复核，报告与 STORED 原子保存；先查已提交回执使执行重试不会刷新观测时间。自动流水线开启时旧静态令牌上报入口关闭。Scheduler V174 默认关闭；News V5 兼容旧队列并支持来源及地区任务恢复。真实节点、真实 MySQL 和七日持续观测尚未验收，不能把离线材料当作实际本地新闻证据。

建议下一步完成现有登录前端的订阅和日报阅读，随后接入目的地绑定与邮件投递，再扩展飞书/QQ。每个目标的验收范围见 [实施计划](2026-10-18-daily-news-implementation-plan.md) 的“主动推荐的后续目标”。新增周期规则、接口及复现证据见 [服务 README](../../service/news-digest-service/README.md)、[协议](../../service/contracts/news-digest-v1.md) 和 [第五批记录](../verification/daily-news/2026-10-18-periodic-pipeline.md)。


## 20. 第六批实现对照（2026-10-18）

已完成现有 webapp 的个人新闻页面 `/news`：订阅设置、预览、历史、四分类阅读、前十详情/引用、地区与趋势缺口、运行状态及取消。复用登录态并接入 Gateway 用户协议；自动生成关闭时仍可保存设置，页面不提供尚未实现的渠道发送按钮。

前端保留不可变日报的截止时刻与地区，不以当前地区覆盖历史；同页面预览重试沿用原请求；版本冲突保留草稿并要求重新载入。来源内容只按文本展示，危险链接不生成可点击入口。桌面及手机布局、网络异常与认证拦截已用本地夹具和现有 Gateway 回归验证，详见 [第六批记录](../verification/daily-news/2026-10-18-news-web.md)。邮件/飞书/QQ、真实部署和持续运行仍未完成。
