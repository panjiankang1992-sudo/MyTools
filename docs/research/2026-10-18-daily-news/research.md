# 每日新闻速报：来源、渠道与项目现状调研

调研日期：2026-10-18（Asia/Shanghai）。

配套文档：[详细设计](../../design/2026-10-18-daily-news-digest-design.md)；[公开端点实测记录](source-probe.json)。

## 1. 调研范围与证据等级

本次完成官方文档核查、当前本地代码阅读，以及 8 个公开数据端点的有界 GET 探测。探测发生在本地工作站，没有连接生产服务器，没有调用付费模型或发送邮件、飞书、QQ 消息。用户已明确首期个人使用，本地新闻依执行任务服务的 IP 所在地确定。

| 等级 | 含义 | 不能据此推断的内容 |
| --- | --- | --- |
| 文档确认 | 已阅读来源方官方文档或项目仓库 | 生产网络一定可达、当前账户已获权限 |
| 本机实测 | 已从本地工作站取得并解析响应 | 长期稳定、生产出口可达、内容可任意再分发 |
| 待验证 | 已列入候选，但未完成接口、条款或内容质量验证 | 不能写进首期必达来源清单 |
| 设计建议 | 基于调研作出的产品或工程决策 | 不是平台强制要求，也不是已实现功能 |

接口返回 200、存在 RSS、项目采用开源许可，分别只能证明请求成功、提供订阅格式、代码许可情况，不能合并理解为新闻正文、图片及 AI 摘要再分发权限。

## 2. 数据源调研

### 2.1 技术与科技

| 来源 | 可获得的信息与入口 | 热度信号 | 验证情况 | 接入建议 |
| --- | --- | --- | --- | --- |
| GitHub REST | 仓库描述、语言、总 Star、更新时间、Release 等；[REST Search](https://docs.github.com/en/rest/search/search) | 自建快照计算观测期净新增 Star；发行事件 | 文档确认；仓库端点实测成功 | 核心来源。按主题发现候选并持续观察，不能用总 Star 冒充周增量 |
| GitHub Trending | [本周页面](https://github.com/trending?since=weekly) | 页面排名、页面显示的本周 Star | 本机返回 200，发现 10 个列表卡片标记与周 Star 标签 | 补充发现。页面结构和数量不构成接口契约，适配失败不应使整期失败 |
| Hacker News | [官方 API](https://github.com/HackerNews/API)，top/new/best/show 列表及 item | score、评论数、榜单出现时间 | 官方文档；topstories 实测 500 个 ID | 核心技术论坛。按 item 的时间过滤并排除 job/dead/deleted；外链正文需独立获取 |
| DEV / Forem | [官方 API](https://developers.forem.com/api/v1)，articles?top=7 | 近七日热门文章、互动和评论字段 | 文档确认；实测返回 20 条 | 核心补充。区分经验文章、产品发布和推广内容 |
| V2EX | [API 2.0](https://www.v2ex.com/help/api)，技术节点 topics | 回复与时间；自建观测 | 文档确认；没有使用用户令牌进行实测 | 第二批；需 PAT。只选技术节点，不将全站生活讨论归入技术 |
| 项目官方博客 / Release | 项目官网 RSS/Atom、GitHub Releases | 发布等级、版本差异、社区二次讨论 | 各站待逐一确认 | 为技术详情提供第一手事实；可弥补只有讨论链接的缺口 |
| 掘金、CSDN、SegmentFault、InfoQ | 门户或论坛页面、站点明确提供的订阅入口 | 官方榜单、评论等可用字段 | 作为候选，本轮未确认稳定开放接口 | 分站试接；不承诺存在通用免费 API，不依赖需登录的内部接口 |
| Reddit / Product Hunt | 官方开发者接口或获授权的数据供应商 | 社区互动或榜单 | 本轮未核验账户准入和当前条款 | 后续选项，不作为首期依赖 |

**GitHub 的三个口径必须分开：**

1. Trending 的周榜是 GitHub 页面给出的榜单。
2. Search 按创建时间、最近推送时间、总 Star 排序，得到的是符合查询条件的仓库集合。
3. 本系统在两个时间点采集总 Star 后相减，得到的是候选池内、观测区间内的净变化，不是全 GitHub 的完整新增 Star 排名。

GitHub Search 每次查询最多返回 1,000 个结果，搜索请求另有速率限制；普通 REST 同样有主要和次级限流。按响应头和退避策略控制请求，而不是靠扩大搜索分页采集全站。[Search 文档](https://docs.github.com/en/rest/search/search)、[REST 限流](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)。

GitHub 在 2026-06-30 公告中提出限制 stargazers/subscribers 名单访问。因此本设计不依赖遍历任意仓库的 Star 用户及其时间来重建历史；使用仓库汇总计数快照，并单独标记冷启动观测覆盖率。[官方公告](https://github.blog/changelog/2026-06-30-upcoming-access-restrictions-to-public-api-endpoints-and-ui-views/)。

### 2.2 国际、国内与本地

| 来源 | 推荐用途 | 当前证据 | 详情与限制 | 优先级 |
| --- | --- | --- | --- | --- |
| 中国新闻网 | 国内、国际基础候选 | [官方 RSS 目录](https://www.chinanews.com.cn/rss/)；china/world 两个 feed 实测各 30 条，含当日记录 | RSS 简介短，详情需单独正文或授权摘要；摘要加工权限需登记 | 第一批 |
| BBC News | 国际来源多样性与英文线索 | [官方历史 feed 说明](https://support.bbc.co.uk/platform/feeds/NewsFeeds.htm)；world RSS 实测 28 条且有当日记录 | 部分官网页面被研究浏览器 robots 阻止；Feed 可达不代表正文可采集或可任意改编 | 第一批候选，按条款确定用途 |
| The Guardian | 国际候选、结构化正文 | [Open Platform](https://open-platform.theguardian.com/access/) 与[接口文档](https://open-platform.theguardian.com/documentation/) | 需要 API key；开发级公开页面列明非商业、1 次/秒、500 次/日及正文访问；具体摘要、AI 加工和分发权限仍须核对 | 第一批候选，准入通过后启用 |
| 人民网 | 国内/国际补充来源 | [官方历史 RSS 说明](https://politics.people.com.cn/ywkx/GB/368825/index.html)；world feed 可解析但最新条目为 2025-06-05 | 本次实测地址应判为停更，不可作为日更源；其他入口需单独验证 | 本次 RSS 地址排除 |
| 新华网 | 重要事件发现、事实核验候选 | [官方站点说明](https://www.news.cn/aboutus.htm)、[版权声明](https://www.news.cn/copyrightad.htm) | 版权声明限制未经授权的转载与摘编；不能默认抓正文后改写发送 | 获得适用授权后再接详情 |
| 央视网 | 国内新闻与官方报道补充 | [国内栏目](https://news.cctv.com/china/)可检索 | 栏目页不是 RSS/API 承诺；列表更新、正文提取和使用权限需单独验证 | 第二批 |
| 澎湃、新浪、腾讯新闻、网易、凤凰 | 门户榜单、社会议题发现 | 候选清单；本轮未完成逐站开放接口确认 | 转发常来自同一原稿，需要识别原创来源；私有 JSON 接口不能视为稳定 API | 第二批逐站准入 |
| 百度、微博、知乎及其他论坛 | 社会讨论热度线索 | 候选清单；本轮未完成接口与许可确认 | 热搜不是已证实新闻；不得仅凭热搜标题生成事实详情 | 可选热度补充 |
| GDELT | 跨媒体线索发现与事件覆盖参考 | [项目官方说明](https://www.gdeltproject.org/) | 非权威真相库；覆盖数量受采样、转载和语言影响，不提供上游新闻的无限再分发授权 | 第二批，辅助去重和发现 |
| 市/省政府、应急、气象、交通部门 | 服务节点所在城市的本地消息 | 确定节点地区后按政府官网逐站登记 | 优先原始公告，保留生效时间、影响地点；部分仅省级数据可用 | 本地核心 |
| 城市融媒体、当地报社、本地论坛 | 地方事件与社区议题 | 地区尚未解析，因此没有虚构具体城市源 | 同名城市、地名简称及转载误归属要过滤；论坛只作线索 | 地区准入后接入 |

“国际”依据事件发生地与主题分类，不能按网站语言或域名分类；中文媒体的国际报道仍属于国际。“本地”依据执行节点 IP 解析出的地区，不能根据用户桌面时区推断城市。国内默认中国新闻，本地节点位于海外时仍不改变国内分类的默认范围。

### 2.3 商业聚合 API 是否值得购买

| 选项 | 已确认事实 | 判断 |
| --- | --- | --- |
| NewsAPI | 调研时 Developer 为 100 请求/日、新闻延迟 24 小时，仅限开发测试，不能用于内部生产；Business 页面月付为 USD 449；所有套餐的搜索结果都不提供完整正文。[价格与 FAQ](https://newsapi.org/pricing) | 不适合个人版首期主链路。付费也没有解决全部详情与上游授权问题 |
| Guardian Open Platform | 非商业开发访问有日配额且可获得正文，商业及其他用途需匹配对应条款。[官方准入页](https://open-platform.theguardian.com/access/) | 作为单一媒体候选很合适；不能替代多来源覆盖 |
| AP Media API | 提供合同授权范围内的新闻和多媒体内容。[官方 API](https://developer.ap.org/ap-media-api/)、[接入说明](https://api.ap.org/media/v/docs/Getting_Started_API.htm) | 面向公共产品时再评估采购，个人 MVP 不预置采购成本 |

以上价格是 2026-10-18 页面快照，不是采购报价。用户当前个人使用的前提，不意味着所有免费开发套餐都允许长期运行。

## 3. 实测结果与解释

以下均为本机一次性探测，日期为 2026-10-18；采集时间和原始计数见 [JSON 记录](source-probe.json)。

| 端点 | HTTP / 格式 | 结果 | 设计影响 |
| --- | --- | --- | --- |
| GitHub 仓库详情 | 200 / JSON | 存在 stargazers_count | 支持汇总计数快照方案；没有验证历史增量 |
| GitHub weekly Trending | 200 / HTML | 10 个列表卡片标记；包含周 Star 标签 | 可作为补充发现入口；不是完整解析成功率测试 |
| HN topstories | 200 / JSON | 500 个 ID | 可生成候选，仍需 item 请求与时间过滤 |
| DEV top=7 | 200 / JSON | 20 条，样本发布时间位于最近七天 | 支持论坛近七日候选 |
| 中新网 china RSS | 200 / XML | 30 条；最新为当日；简介去标签长度中位数 54.5 字符 | 标题采集可行，详情必须进一步补充 |
| 中新网 world RSS | 200 / XML | 30 条；最新为当日；简介长度中位数 80 字符 | 同上；应增量采集，防止短列表覆盖旧条目 |
| 人民网 world RSS | 200 / XML | 100 条；所有可解析日期中的最新为 2025-06-05 | 接口健康检查必须包含新闻新鲜度 |
| BBC world RSS | 200 / XML | 28 条；前三条样本含当日记录 | Feed 可作为候选；正文与条款仍是不同的验证项 |

这些数据不证明每天都能达到四类各 20 条，更不证明本地类必有 10 条可详细报道的独立事件。该问题需要在服务节点地区确定后，连续 7 日影子采集评估。

## 4. 发送渠道调研

### 4.1 邮件

本地 EmailDeliveryProvider 已使用 Spring JavaMail、服务端配置的发件人和稳定 Message-ID。当前代码使用纯文本正文，不支持 HTML multipart 模板，不能把“已有邮件发送”写成“已有精美 HTML 日报”。

首期可先发送完整纯文本日报，随后增加 text/plain + text/html 双版本。SMTP 接受成功只代表提交成功，不证明进入收件箱或被阅读；稳定 Message-ID 也不是端到端严格去重保证。

### 4.2 飞书

官方自定义机器人适合群内单向推送；单聊、互动订阅和跨群管理更适合应用机器人。[机器人能力对比](https://open.feishu.cn/document/client-docs/bot-v3/bot-overview)。

调研时官方自定义机器人文档标明：请求体上限 20 KB，单租户单机器人 100 次/分钟、5 次/秒，并支持签名等安全设置。[自定义机器人指南](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot)。

设计选择：个人版先用群 Webhook；序列化后的 UTF-8 JSON 预留空间、内部按 16 KB 分片预算。正文 20 KB 与“20,000 个汉字”不是同一个概念。Webhook 和签名密钥由 Messaging 保存，不进入订阅响应、页面或 Scheduler 参数。

### 4.3 QQ

2026-10-18 核查的官方文档已明确支持主动消息，并提供用户关闭主动接收的机制。当前概述给出的 C2C 和群聊每日上限均为 1,000 条/接收关系，同时存在认证类型、Bot、单关系等多层频控；频道和频道私信有不同规则。[消息概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html)。

日报首期限定官方 QQ Bot 的 C2C 与群聊，按场景分别处理：

- C2C 使用 user_openid，不能将用户输入的数字 QQ 号直接当作 OpenID。[单聊 API](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html)。
- 群聊使用 group_openid，授权与接收开关单独验证。[群聊 API](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)。
- 发送主动日报不依赖昨日报文的 msg_id。官方被动回复时效在部分页面字段与概述中存在差异，不把它作为每日主动发送的前提。
- 本地 Connector 的文本校验目前为 2,000 字符，并要求 messageId 非空，现有 HTTP 接口主要服务自动化回复；需新增正式主动投递契约，不能通过伪造 messageId 接入。
- News 服务不直接调用 NapCat。OneBot 只作为已有账户场景的后续可选适配，不承担首期官方 QQ 方案的默认路径。

## 5. IP 地区解析

MaxMind 的 GeoLite/GeoIP 提供城市与省/州级数据，但明确说明定位不精确、部分 IP 没有城市字段，并返回精度半径。[GeoLite 说明](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data/)、[城市字段](https://dev.maxmind.com/geoip/docs/databases/city-and-country/city-binary/)。

官方 Web Service 支持 city/me 解析请求方 IP，可由指定任务节点调用。也可采用本地 MMDB + 可信公网出口探测，减少日常外部查询。[Web Service 文档](https://dev.maxmind.com/geoip/docs/web-services/)。

推荐在个人 MVP 使用 GeoLite City 本地库，按其许可和更新要求维护数据；账户及下载密钥独立保管。城市不足时降级到省/州，没有省/州时明确本地不可用，不把国家中心坐标反向推成城市。IPinfo Lite 是候选国家/ASN 补充，需要城市数据时应另行核验相应产品，不能将 Lite 当作城市定位来源。[IPinfo Lite](https://ipinfo.io/lite)。

本轮没有读取生产出口 IP，也没有确认某个实际城市。最终定位必须从执行新闻任务的服务节点网络中进行。

## 6. 可复用项目与自研边界

| 项目 | 已公开的相关能力 | 适合复用或参考 | 不直接替代的需求 |
| --- | --- | --- | --- |
| [RSSHub](https://github.com/DIYgod/RSSHub) | 将多站内容转为 RSS | 自托管少量允许接入的路由；来源适配隔离 | 稳定授权、事件事实核查、跨源排名与投递审计 |
| [TrendRadar](https://github.com/sansan0/TrendRadar) | 多平台热点、RSS、筛选、AI 简报及多渠道通知 | 独立原型、来源规则和模板思路 | 与 MyTools 的 owner、Scheduler、Messaging、版本快照融合需要另做工程 |
| [NewsNow](https://github.com/newsnext/newsnow) | 热点聚合、缓存和自适应采集 | 热榜适配与榜单交互参考 | 每类 Top20、Top10 证据详情、IP 地区、本地订阅与可靠投递不是本次确认的现成闭环 |

推荐“独立新闻领域服务 + 现有基础设施”，避免再引入第二套无人维护的调度和消息状态机。若只需一次原型，TrendRadar 更快；若作为 MyTools 长期能力，集成设计更匹配。代码复用前单独检查具体版本许可，不把代码开源许可当成上游内容授权。

## 7. 本地代码证据与缺口

以下是当前工作区源码事实，不是生产部署状态：

| 文件 | 已确认事实 | 新能力需要补充 |
| --- | --- | --- |
| [service/AGENTS.md](../../../service/AGENTS.md) | 新服务独立构建、脚本版本不可变、禁止 Secret 入库 | 独立 news-digest-service；不隐式挂入根 Maven |
| [Scheduler README](../../../service/task-scheduler-service/README.md) | 持久化 cron、misfire、重叠策略、租约、节点标签与父子任务 | 新闻任务定义、节点地区快照与固定配置版本 |
| [DeliveryService](../../../service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/service/DeliveryService.java) | owner + 幂等键、投递任务和 provider 路由；执行 fence 仍检查 send_email 步骤名 | 通用频道任务、步骤 fence 与 payload 契约一并扩展 |
| [CreateDeliveryRequest](../../../service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/model/CreateDeliveryRequest.java) | recipient/subject/body 为现有请求字段 | 不透明 destinationId、contentType 与结构化频道正文 |
| [EmailDeliveryProvider](../../../service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/provider/EmailDeliveryProvider.java) | 当前是纯文本邮件 | HTML multipart 及模板渲染 |
| [ChannelType](../../../service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/model/ChannelType.java) | EMAIL/QQ/TELEGRAM/ONEBOT，无 FEISHU | 飞书枚举、provider、账户配置与任务注册 |
| [QqInboundReplyProvider](../../../service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/provider/QqInboundReplyProvider.java) | 现有 QQ provider 用于原消息回复 | 新增主动 QqDeliveryProvider，不能把 InboundReplyProvider 当作出站通用实现 |
| [QQ main.py](../../../service/qq-connector-service/src/mytools_qq_connector/main.py) | 文本入口需要 messageId，限制 2,000 字符 | 明确 C2C/GROUP 的主动消息入口与状态查询 |
| [QQ client.py](../../../service/qq-connector-service/src/mytools_qq_connector/client.py) | 已有 active_only 路径与主动消息请求能力 | 暴露正式契约，补充不确定结果处理与频道隔离 |
| [Messaging README](../../../service/messaging-service/README.md) | Outbox、投递尝试和内部鉴权等基础设施 | 渠道能力查询、日报投递结果对账 |
| [Automation README](../../../service/message-automation-service/README.md) | 已有授权入站规则与动作编排 | 后续订阅/退订/补发命令；定时日报无需强制经过 Automation |

## 8. 上线前尚需验证

1. 在实际新闻执行节点解析出口 IP，确认地区与本地来源包覆盖；本机网络测试不能代替这一项。
2. 对首批来源逐项登记可采集范围、保留时间、摘要加工、模型处理与发送范围；不满足的只作允许范围内的线索或停用。
3. 连续 7 日评估每类有效事件数、正文充足率、转载聚类和技术周榜冷启动。
4. 在已绑定的测试接收目标完成邮件、飞书、QQ 主动发送和退订验证，覆盖平台限流与结果未知。
5. 完成各新接口、持久化格式与 Scheduler fence 的兼容验证，再打开自动发布与发送开关。

## 9. 第二批落地复核：技术官方 API

2026-10-18 在 Java 21 生产传输器上进一步验证了三类适配器，每类读取三个元数据样本，均成功。该验证与第 3 节的一次性端点探测分开记录，见 [第二批验证](../../verification/daily-news/2026-10-18-technical-sources.md)。

| 来源 | 官方字段/查询口径 | 落地决策 |
| --- | --- | --- |
| [HN API](https://github.com/HackerNews/API) | topstories 返回 ID；item 区分 type、dead、deleted，time 为原始 Unix 时间 | 有界读取 story；不抓外链正文；原生 score 与讨论页引用单独保留 |
| [Forem API v1](https://developers.forem.com/api/v1) | top 按过去指定天数的热门文章筛选；详情端点包含 body_html | 使用 top=7 第一页，再按 published_at 校验；加工权限允许才请求最多十篇详情 |
| [GitHub Search](https://docs.github.com/en/rest/search/search) | 搜索返回 total_count、incomplete_results 和分页结果 | 只作为候选发现入口；保留不完整搜索/第一页限制，不称官方 Trending |
| [GitHub Repositories](https://docs.github.com/en/rest/repos/repos) | 数字 id、创建/推送日期与累计 stars/forks 是不同字段 | 数字 ID 连接改名后的快照；真实观测时间单独保存；不伪造项目发布日期 |
| [GitHub Rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api) | 配额和限流响应需要结合响应头处理 | 无自动请求重试；来源与平台冷却持久化，不因服务重启恢复立即请求 |

当前采样预算为 GitHub/HN/DEV 各 30 个列表条目；GitHub 额外跟踪最多 30 个旧仓库，DEV 最多请求 10 篇已允许的详情。正常采样间隔分别为 6 小时、15 分钟和 1 小时，是本服务工程策略，不是官方平台配额。

GitHub 周趋势使用真实快照差值，允许基线比七日前目标最多早六小时，因此展示实际跨度而不宣称精确七日新增点赞人数。首次运行保留 null 增量及 WARMING_UP；29 次固定时钟采样已验证算法，真实七日连续数据仍未积累。
