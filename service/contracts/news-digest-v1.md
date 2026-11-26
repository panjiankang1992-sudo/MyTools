# Daily news API v1 — content, subscriptions and generation

状态：基础内容、技术来源、节点地区、内容订阅/生成及第五批周期流水线协议已实现；第六批网页订阅/阅读已接入上述用户协议；多渠道投递仍以[设计文档](../../docs/design/2026-10-18-daily-news-digest-design.md)为目标。服务边界见[README](../news-digest-service/README.md)。新增字段保持旧日报兼容，路径仍为 v1；持久化旧日报保留原评分与地区版本。

## 用户接口

Gateway 前缀 `/api/app/v1/news`，兼容别名 `/api/v1/news`。要求现有 MyTools Bearer 登录令牌，所有者从 GatewayPrincipal 注入。禁止在 JSON 中传 ownerId；用户查询参数和 X-Owner-Id 不参与所有者计算。

| 方法 | 路径 | 输入 | 输出 |
| --- | --- | --- | --- |
| GET | /capabilities | 无 | latestCutoff、rankingVersion、headlineLimit、detailLimit、detailMethod、regionStatus、deliveryEnabled。 |
| GET | /local-region | 无 | 当前指定节点的脱敏 RegionSnapshot；不接受用户城市或节点选择。 |
| POST | /previews | idempotencyKey、cutoff | 同步 200，完整 Edition；不会发送消息。 |
| GET | /editions | limit 默认 30，1—100 | 本人最近的 EditionHeader 数组。 |
| GET | /editions/{uuid} | 无 | 本人完整 Edition；不存在或非本人统一 404。 |
| GET | /subscription | 无 | subscription（初次为 null）、schedulingEnabled、deliveryEnabled=false。 |
| PUT | /subscription | expectedRevision、enabled、zoneId、cutoffTime | 更新后的 Subscription；版本冲突 409。 |
| GET | /runs | limit 默认 30，1—100 | 本人最近 DailyRun 数组。 |
| GET | /runs/{uuid} | 无 | 本人 DailyRun；不存在或非本人统一 404。 |
| POST | /runs/{uuid}/cancel | 无 | 当前 DailyRun；已 READY 的内容保留。 |

预览请求：

~~~json
{
  "idempotencyKey": "preview-2026-10-18-001",
  "cutoff": "2026-10-17T23:30:00Z"
}
~~~

cutoff 必填、不能晚于服务端当前时间，不能早于当前时间 30 天。可以先读取 capabilities 中的最近截止时刻。仅使用已在截止前采集的材料；新抓取不能回填进已保存历史快照。

`news-run:` 前缀为生成任务保留，预览请求使用该前缀返回 NEWS_002。幂等作用域为 `(ownerId, SHA256(idempotencyKey))`。相同键与请求重放原快照，cutoff 改变返回 409，不会覆盖。首次生成后，后来采集的数据不会改变此快照；需要新的预览使用新 key。

Gateway 成功信封为 `{"code":"0000","message":"OK","data": ...}`；内部服务直接返回数据。业务错误信封不再嵌套 data。

Edition 包含 id、ownerId、cutoff、createdAt、zone、rankingVersion、region、quality、sections。sections 以 TECH/WORLD/DOMESTIC/LOCAL 为键：

- windowStart/windowEnd：实际半开统计窗口。
- candidateCount：过滤与聚类后的事件数。
- headlineCount/detailCount：实际输出数量，不补齐缺失项。
- quality/gaps：COMPLETE 或 DEGRADED；数量/地区缺口为 HEADLINE_SHORTFALL、DETAIL_SHORTFALL、REGION_UNRESOLVED；趋势不足增加 `GITHUB_TREND_` + 趋势状态。
- items：rank、eventId、title、url、publishedAt、sourceIds、score、detailStatus、detail、technical（可空）。
- detail：原文 text、method=SOURCE_EXCERPT_V1、citations；引用具有 articleId/url/sourceId/contentHash/fetchedAt。
- rank 1—10 的详情允许为空，并通过 EVIDENCE_TOO_SHORT 或 NOT_AUTHORIZED 解释；11—20 为 HEADLINE_ONLY。

当前新日报 `rankingVersion=personal-evidence-v3`。详情接受至少 80 码点且不等于标题的来源摘要，仍最多展示 300 码点。HN 自带正文的 citation.url 为讨论页；补读外部原文成功时为实际原文地址。item.url 仍为故事指向，不能仅凭它推断文字来自哪里。历史日报及其旧版本号不变。

`technical` 字段如下：

| 字段 | 语义 |
| --- | --- |
| externalId | 平台数字身份的字符串；GitHub 改名不改变身份。 |
| discussionUrl | HN 讨论页、DEV 原文或 GitHub 仓库页。 |
| evidenceUrl | 可空；补读原文后记录实际证据地址。旧材料无此字段时，HN 使用讨论页，其他来源使用文章地址。 |
| activityAt | HN/DEV 原始发布时间；GitHub 实际快照观测时间。 |
| nativeScore / commentCount | 平台原生计数；HN score/descendants，DEV positive_reactions_count/comments_count；GitHub 为累计 stars/0，不直接作为排名热度。 |
| github | 非 GitHub 为 null；包含 repositoryId、stars、forks、observedAt、baselineAt、baselineStars、netStars、coverageSeconds、sampleCount、status。 |

GitHub 条目的 `publishedAt` 始终保留仓库创建时间，不能展示成“今天发布”；排行榜窗口使用 `activityAt`。`netStars` 为有符号的两个实际快照差值，缺少基线时为 null。允许基线范围为最近观测前 168—174 小时，准确跨度由 `coverageSeconds` 表达。

趋势状态为 WARMING_UP、MISSING_BASELINE、GAPPED_HISTORY、STALE、READY。仅 READY 的正净增量进入热度；负净增量保留展示。READY 表示采样口径合格，不代表官方 GitHub Trending。已保存日报不会因后续采集、趋势过期或已观测撤稿而更新。

## 内部代理接口

前缀 `/internal/v1/news`，路径同上；除 capabilities/local-region 外要求 `ownerId` 为正整数查询参数。Bearer 必须是 NEWS_INTERNAL_TOKEN。网络访问限 Gateway/受信任内部调用方，不能把此令牌交给用户。

## 来源管理接口

独立前缀 `/internal/admin/news/sources`，只接受 NEWS_ADMIN_TOKEN；Gateway 不提供代理。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | / | 来源登记和健康记录。 |
| PUT | /{id} | 新建来源或仅切换启停；其余配置变化须新 ID。 |
| POST | /{id}/collect | 实时采集单个已登记且启用的来源；全局采集开关也必须打开。 |
| POST | /{id}/articles | 仅 RSS_ATOM 接收受控采集器证据，抓取时间由本服务记录；技术 API 来源必须走 collect，不接受伪造指标的手工导入。 |

来源示例为不启用的公开 RSS 登记，METADATA_ONLY 不授权正文加工：

~~~json
{
  "id": "chinanews-world-rss",
  "name": "Chinanews World RSS",
  "feedUrl": "https://www.chinanews.com.cn/rss/world.xml",
  "category": "WORLD",
  "publisherKey": "chinanews",
  "regionCode": null,
  "evidencePolicy": "METADATA_ONLY",
  "rightsReference": "https://www.chinanews.com.cn/rss/",
  "authority": 0.8,
  "enabled": false,
  "kind": "RSS_ATOM"
}
~~~

rightsReference 是管理员核验记录的引用，不是自动判定许可的依据。LOCAL 来源必须有明确 regionCode，并属于已登记来源包；只登记本地来源不会自动进入日报。

`kind` 缺省为 RSS_ATOM，以下技术协议只允许 category=TECH 并使用精确 feedUrl，不接受自行拼接查询参数。已登记来源的 kind 不可变，切换协议需新 ID。

本地协议 `CHINANEWS_JIANGSU` 仅接受 category=LOCAL、regionCode=CN-JS 和 feedUrl=`https://www.js.chinanews.com.cn/`，成功冷却 30 分钟。地区报告 provider 另支持 `IPWHOIS`；它不提供城市 GeoNames ID 或精度半径，因此使用已登记的省级来源包，保留 `PROVINCE_FALLBACK` 状态。

| kind | feedUrl | 成功后最短采样间隔 |
| --- | --- | --- |
| HACKER_NEWS | https://hacker-news.firebaseio.com/v0/topstories.json | 15 分钟 |
| DEV | https://dev.to/api/articles | 1 小时 |
| GITHUB | https://api.github.com/search/repositories | 6 小时 |

完整登记样本见 [technical-sources.json](../news-digest-service/examples/technical-sources.json)，逐条使用 PUT 登记，未提供批量登记 API。

采集返回 sourceId/status/inserted/skipped/newestPublishedAt，并新增 coverage/nextAttemptAt/newestObservedAt（RSS 响应可空）。来源管理视图同步新增这三个字段。相同证据与指标不重复插入；GitHub 新采样桶构成新的观测证据，即使累计计数没变也会保留快照。

- `newestPublishedAt`：论坛/RSS 的原始日期，GitHub 为本轮有效仓库中最新创建日期。
- `newestObservedAt`：GitHub 本轮实际采样时刻；不冒充新闻发布日期。
- `coverage`：listed、visited、accepted、skipped、failed、limitations。GitHub listed 为搜索总命中量，visited 包含额外跟踪请求；DEV 只知道当前页大小；HN 为当前榜单 ID 数量，均不能作为全网覆盖率分母。failed 也统计详情请求失败，不能要求 accepted + skipped + failed = visited。
- `nextAttemptAt`：最早再次采集的时刻。上游限流还有按平台共享的冷却，最终以 429 响应的 Retry-After 为准；重新启停或更换同平台来源 ID 不清除限流。

NOT_MODIFIED 表示 RSS 304，健康仍按原发布时间重新计算。STALE、DATE_UNKNOWN、EMPTY、PARTIAL、FETCH_FAILED、RATE_LIMITED 可在来源列表区分；FRESH 只表示取得新鲜材料，不表示已积累周趋势或足够详情。PARTIAL 记录条目/详情失败、时间预算超限或上游不完整搜索。

`limitations` 常见值包括 CURRENT_TOP_STORIES_SAMPLE、TOP_SEVEN_DAYS_FIRST_PAGE、SEARCH_DISCOVERY_NOT_WEEKLY_TRENDING、BOUNDED_TRACKED_COHORT、SEARCH_FIRST_PAGE_ONLY、UPSTREAM_INCOMPLETE_SEARCH、ITEM_LIMIT_OR_TIME_BUDGET、TIME_BUDGET_EXCEEDED，以及对应失败标记。调用方需原样保留未知限制项，不能把抽样结果标为全站官方榜单。

导入请求：

~~~json
{
  "articles": [
    {
      "url": "https://example.com/news/item",
      "title": "Example fixture only",
      "publishedAt": "2026-10-17T22:00:00Z",
      "evidenceText": "Evidence must come from the registered source.",
      "engagement": 0
    }
  ]
}
~~~

每批 1—500 条。缺失或无法解析的 publishedAt 不使用 fetchedAt 替代，不参加排序。此示例不是实际新闻材料。

## 节点地区协议

### 指定节点报告

旧诊断入口 `POST /internal/worker/news/region-observations` 仅在 NEWS_PIPELINE_ENABLED=false 时可用；开启后返回 NEWS_016/409，必须使用下文的地区作业协议。此旧入口使用独立 `NEWS_REGION_REPORT_TOKEN`，不能使用用户代理或管理员令牌。服务端将 nodeId 与部署配置的 `NEWS_REGION_NODE_ID` 精确匹配；普通用户没有写地区的入口。

~~~json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "nodeId": "example-executor",
  "observedAt": "2026-10-18T00:00:00.000Z",
  "status": "RESOLVED",
  "egressFingerprint": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "addressFamily": "IPV4",
  "networkMode": "DIRECT",
  "provider": "MAXMIND_MMDB",
  "providerVersion": "fixture-only",
  "geo": {
    "countryCode": "CN",
    "subdivisionCode": "SC",
    "cityGeonameId": 123,
    "cityName": "Example City",
    "accuracyRadiusKm": 20
  }
}
~~~

以上是格式夹具，不能用于实际节点验收。探测包从实际出口生成该结构，不允许 Scheduler 任务参数指定城市。首次报告需在服务时钟过去五分钟至未来三十秒内，时间精度为毫秒；未知额外字段（包括 publicIp）被拒绝。

状态 RESOLVED 要求 geo/指纹/地址族；LOOKUP_FAILED 要求确认指纹/地址族且 geo=null；EGRESS_UNAVAILABLE 要求指纹、地址族、geo 都为 null。只有报告 ID 和全部内容完全一致才可重放，重放不刷新接收时间；同 ID 改内容返回 NEWS_004。成功回执为 `{"id":"...","status":"STORED"}`，只表示存储成功，不表示本地新闻可用。

报告原始 JSON 仅供内部仓储使用，无普通用户查询接口。服务不存完整公网 IP；指纹由节点使用独立 HMAC 密钥计算，公开日报与任务结果也不返回指纹。旧入口采用可信节点令牌；第五批自动任务使用 Scheduler 实时租约，不能回退旧入口绕过授权。

### 地区来源包

`GET/POST /internal/admin/news/region-source-packs` 使用 NEWS_ADMIN_TOKEN。POST 输入：

~~~json
{
  "regionCode": "GEONAME-123",
  "version": 1,
  "label": "Example City",
  "sourceIds": ["approved-local-source"],
  "verifiedAt": "2026-10-17T00:00:00Z"
}
~~~

必须先登记匹配 regionCode 的 LOCAL 来源。sourceIds 为 1—50 个不同来源，不能包含其他分类、其他地区或未登记来源。城市代码为 GEONAME-{id}，省/州为国家 ISO + 一级行政区 ISO，例如 CN-SC。版本不可原地修改；同版本同内容重放不改变生效时间。选取截止前已登记的最高版本，并在日报地区快照中保留版本与 sourceIds。

### 公开地区视图与冻结

RegionSnapshot 保留 status/regionCode/nodeId/resolvedAt，新增可空 details：snapshotId、precision、countryCode、subdivisionCode、cityName、accuracyRadiusKm、provider、providerVersion、networkMode、observedAt、expiresAt、cached、sourcePackLabel、sourcePackVersion、sourceIds。

- VERIFIED：城市精度符合配置且存在来源包。
- PROVINCE_FALLBACK：使用明确允许的省/州来源包，仍标记降级。
- CACHED：新的出口探测成功但定位失败，复用同一连续出口七日内的地区；resolvedAt 为原解析时间，observedAt 为当前出口确认时间。precision 仍区分城市或省级。
- SOURCE_PACK_MISSING / COUNTRY_ONLY / STALE / LOOKUP_FAILED / EGRESS_UNAVAILABLE / UNRESOLVED：不填充本地栏目，保持可解释缺口。

LOCAL gaps 记录 `REGION_` + 非 VERIFIED 状态。前两类和 CACHED 只允许名单内来源；SOURCE_PACK_MISSING 不自动搜索或抓取未知本地网站。

`GET /local-region` 的 details.snapshotId 为空，因为它是当前视图。新日报在内容生成前以 `(ownerId, idempotencyKey)` 绑定地区，已解析结果携带 snapshotId；未观测地区也会冻结为不可变的未解析值。即使内容生成失败，重试仍复用绑定值；更换截止时刻需新 key。历史日报旧版 details=null 按原样读取，不补写推断出的地区。

节点报告和来源包都必须在 cutoff 前已被服务接收；迟到报告不能回填过去。capabilities.regionStatus 对应 latestCutoff，local-region 对应当前时刻，历史日报对应冻结时刻，客户端需分别展示。

## 错误码

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| NEWS_001 | 401 | 内部身份错误或令牌缺失。 |
| NEWS_002 | 400 | 参数无效。 |
| NEWS_003 | 404 | 资源不存在或无权读取。 |
| NEWS_004 | 409 | 同键内容不一致，或企图更改已登记来源身份。 |
| NEWS_005 | 503 | 来源或全局采集关闭。 |
| NEWS_006 | 502 | 来源网络访问失败。 |
| NEWS_007 | 422 | XML/JSON/大小等来源约束不满足。 |
| NEWS_008 | 400 | 不允许的网址。 |
| NEWS_009 | 500 | 未公开的内部错误。 |
| NEWS_010 | 409 | 采集正在进行或租约已失效。 |
| NEWS_011 | 422 | 候选超过 10,000，未输出截断后有偏差的榜单。 |
| NEWS_012 | 429 | 本服务采样间隔或上游限流冷却未结束；带 RFC 1123 格式 Retry-After。 |
| NEWS_013 | 422 | 节点身份、观测时间或地区报告字段组合不符合要求。仅用于节点上报入口。 |

Gateway 对用户接口保留 NEWS_002/003/004/011；内部身份错误与其他服务失败统一映射为已有 Gateway 下游错误，避免泄漏内部凭据状态。


## 内容订阅与每日运行（第四批）

~~~json
{
  "expectedRevision": 0,
  "enabled": true,
  "zoneId": "Asia/Shanghai",
  "cutoffTime": "07:30"
}
~~~

仅接受以上四个字段，每个 owner 首期一份内容订阅。zoneId 必须是 Java ZoneId 可识别的时区；cutoffTime 严格 HH:mm。初次 expectedRevision=0，之后携带服务返回的 revision。Subscription 输出 id/revision/enabled/zoneId/cutoffTime/nextDate/nextDueAt/updatedAt；时间为 ISO 8601。GET 返回外层 subscription/schedulingEnabled/deliveryEnabled，不存在时 subscription=null。

启用和修改只安排不早于更新时刻的下一 cutoff；修改会取消之前未完成运行。停用立即阻止新发布，远程取消由出站队列收敛，已 READY 日报保持可读。每日唯一键不含配置版本，因而同日修改、重启或重复扫描均不会补建第二个运行。恢复仅处理最近截止的一期，早期漏掉天数记录在 skippedDays；距离 cutoff 达到十二小时为 EXPIRED。DST 缺失时刻按缺口长度向后移动，重叠取较早偏移。

DailyRun 字段：id、subscriptionId、ownerId、subscriptionRevision、businessDate、zoneId、cutoff、expiresAt、skippedDays、nodeId、status、taskId、taskStatus、editionId、lastFence、errorCode、createdAt、updatedAt。不返回任何租约令牌。status 为 QUEUED/DISPATCHED/READY/FAILED/CANCELLED/EXPIRED；READY 只表示生成，不表示内容质量完整或已投递。

用户 API 的 409 在订阅变更时映射为 NEWS_014，预览冲突仍为 NEWS_004。NEWS_015 表示调度暂不可用，NEWS_016 为无效/过期执行租约，NEWS_017 为 Scheduler 永久拒绝，NEWS_018 为自动编排关闭，NEWS_019 表示远程任务终止但没有日报。

## 生成工作协议

- `POST /internal/jobs/news/runs/{id}/generate` 使用独立 NEWS_JOB_TOKEN，并提供 UUID 头 `X-Task-Execution-Id`、`X-Task-Lease-Token`。请求体为空对象；路径只携带运行标识，所有者和截止从数据库读取。
- News 用独立 Scheduler 身份调用 `POST /api/v1/task-instances/news/executions/{executionId}/authorize`，携带业务凭据和当前租约。返回 taskId/businessId/idempotencyKey/nodeId/fencingToken/leaseUntil，响应 `Cache-Control: no-store, private`。
- Scheduler 检查活动 RUNNING 租约、未取消任务、新闻任务域与最大 fence；News 再匹配冻结运行、节点及幂等键，要求剩余租约大于五秒。生成后重新校验；最终提交前再次检查本地时间预算。跨服务校验不是分布式事务，极短的校验后撤销窗口以数据库 fence、状态检查和五秒余量约束。
- 成功输出严格 `{"runId":"...","editionId":"...","status":"READY"}`，不包含日报正文或租约。同一有效任务重试重放 editionId。不能把用户或管理员令牌用于工作回调。
- 任务名称 news_generate_edition；businessType=NEWS_EDITION；businessId=runId；idempotencyKey=news:{runId}；参数仅 runId；requiredNodeLabels 仅 news.node。Scheduler 对新闻业务身份施加双向域限制。

## 编排运维协议

使用 NEWS_ADMIN_TOKEN，前缀 `/internal/admin/news/scheduling`。`POST /scan` 返回 created；`POST /relay` 返回 processed。两者必须先开启 NEWS_SCHEDULING_ENABLED。

`GET /runs/{id}/outbox` 返回 state/attempts/available_ms/lease_until_ms/error_code（数据库列名），没有队列记录时 state=NOT_REQUIRED；时间以 UTC epoch 毫秒表示。`POST /runs/{id}/redrive` 只恢复 DEAD 记录，保留原运行、任务绑定和尝试次数，响应同查询。暂停期间可修复队列，但没有开关就不会派发。

这些入口不发送消息。失败恢复不得删除幂等记录、复制新 runId 或修改历史日报。


## 周期流水线管理协议（第五批）

前缀 `/internal/admin/news/pipeline`，使用 NEWS_ADMIN_TOKEN，不经 Gateway 暴露。读取进度、取消和死信修复不要求启用触发器；采样/扫描/派发要求 NEWS_PIPELINE_ENABLED=true，地区工作还要求 NEWS_SCHEDULING_ENABLED=true，否则 NEWS_018/503。开关开启时启动校验要求全局采集、调度身份和指定节点配置完整。

| 方法与相对路径 | 返回 | 作用 |
| --- | --- | --- |
| GET /sources | PollState 数组，最多 100 条 | 返回持久采样游标，不返回租约令牌。 |
| POST /poll | {processed: integer} | 一次有界来源采样，最多五个；结果数量不代表全部成功。 |
| GET /region-jobs | RegionJob 数组，最多 100 条 | 最近的地区任务及报告状态，无 IP/指纹。 |
| POST /region/scan | {created: boolean} | 仅检查当前节点的当前 UTC 六小时桶。 |
| POST /region/relay | {processed: integer} | 推进最多五条 REGION 队列记录。 |
| POST /region-jobs/{id}/cancel | RegionJob | 取消未完成任务，保留已提交报告和原任务身份。 |
| POST /region-jobs/{id}/redrive | RegionJob | 仅恢复 DEAD 队列；保留 jobId/远端 taskId/尝试次数。 |

PollState 字段：sourceId、nextDueAt、failures、lastStatus、lastFinishedAt、leaseUntil。首次尚未采样时状态/完成时间可为 null；时间为 ISO 8601。普通失败指数退避六十秒至一小时，平台冷却按 Retry-After。来源停用后不再由轮询领取；开启后沿用持久进度，不自动追补历史快照。

RegionJob 字段：id、nodeId、bucketAt、expiresAt、taskId、taskStatus、status、lastFence、observationId、observationStatus、errorCode、createdAt、updatedAt。status 为 QUEUED/DISPATCHED/STORED/FAILED/CANCELLED/EXPIRED；observationStatus 与地区报告一致，可以是 RESOLVED/LOOKUP_FAILED/EGRESS_UNAVAILABLE。STORED 不保证定位成功，taskStatus 单独记录 Scheduler 状态。NEWS_020/502 表示任务终止而没有报告，通常体现在作业 errorCode。

## 地区作业工作协议（第五批）

使用 NEWS_JOB_TOKEN 和 UUID 头 X-Task-Execution-Id、X-Task-Lease-Token。路径为 `/internal/jobs/news/region-jobs/{id}`；不能使用用户、管理或旧地区报告令牌。

1. `POST /receipt`，空对象请求；返回 `{"receipt":null}` 或 `{"receipt":{"jobId":"...","observationId":"...","status":"STORED","observationStatus":"RESOLVED"}}`。先验证在线执行租约、作业身份、指定节点和有效期；已有报告直接返回原回执，不再次探测。
2. `POST /observation`，请求体沿用 RegionObservation；report.id 必须等于执行 ID，nodeId 必须等于作业和当前指定节点。提交前再次核验 Scheduler 租约；报告与 STORED 在同一事务提交。返回上述内层回执四个字段。
3. 同执行重试沿用原报告 ID、内容和 observedAt。相同任务的新执行可以通过 receipt 获得原结果；不能以新的 observationId 覆盖原结果。取消、到期、旧 fence、失效租约或错误任务范围拒绝提交。

任务名称 news_resolve_region，businessType=NEWS_REGION，businessId=jobId，idempotencyKey=news:region:{jobId}，参数只有 jobId，requiredNodeLabels 只有指定 news.node。Scheduler 授权仅接受新闻任务名与业务类型的正确配对；地区和日报身份不能交叉使用。有效期十五分钟且剩余租约需大于五秒；在线核验后的极短撤销窗口仍适用生成协议中的约束。

出站队列新增 job_kind=REGION/EDITION，旧 V4 记录迁移为 EDITION。两种轮询器互不领取；原创建键、取消和 DEAD 恢复规则保持一致。报告已提交、但创建请求回执稍后到达时仍保留 STORED，不能误记 NEWS_020。


## 网页消费者（第六批）

`webapp /news` 使用现有登录态访问同源 `/api/v1/news`，不接受 owner、地区、节点或接收目标输入。历史 limit=100、运行 limit=30，仍遵守原接口上限。预览截止取 capabilities.latestCutoff；UNKNOWN 网络结果在当前页面内复用 idempotencyKey/cutoff。离开页面后的未确认请求不保存在浏览器持久存储中，需要先查历史。

订阅 PUT 使用当前 revision（未创建为零）；409/NEWS_014 保留草稿，显式重新载入后才能再次提交。列表刷新不隐式刷新订阅 revision；原文及引用按文本和安全网页链接渲染，不执行 HTML。当前地区与 edition.region 分开，不覆盖旧日报。


## Delivery integration (batch 8)

Public paths below are under `/api/v1/news` or `/api/app/v1/news`; Gateway injects ownerId for `/internal/v1/news`. Unknown DTO fields, raw addresses and owner overrides are rejected.

- `GET /delivery-capabilities`: `enabled`, `supportedChannels` (EMAIL, FEISHU), `qqStatus=PROACTIVE_PUSH_UNAVAILABLE`, `bindingMode=ADMIN_VERIFIED`, `targetLimit=5`.
- `GET /destinations`: opaque verified destinations belonging to the current owner. Sending must be configured; errors do not expose credentials.
- `GET /delivery-targets`: `{revision,targets:[{destinationId,channel,mode,enabled}]}`.
- `PUT /delivery-targets`: `{expectedRevision,targets:[{destinationId,mode,enabled}]}`. Mode FULL or BRIEF; maximum five distinct targets. New enabled targets are checked with Messaging. Removing/disabling targets can proceed while Messaging is unavailable. Conflict is NEWS_022.
- `GET /deliveries`: most recent 50 batches, with part statuses and no message bodies.
- `GET /deliveries/{id}`: one owned batch.
- `POST /deliveries/{id}/cancel`: requests cancellation; already started delivery may still succeed.
- `POST /deliveries/{id}/parts/{part}/retry`: `{expectedGeneration,acknowledgeDuplicateRisk}`. Only FAILED/UNKNOWN, before expiry; UNKNOWN requires explicit risk acknowledgement. Same-generation retries are idempotent.
- `POST /deliveries/{id}/parts/{part}/redrive`: after repairing configuration, resumes a REJECTED part with its original key and frozen content.

Target settings are captured when each daily run is created. Adding a destination or changing FULL/BRIEF applies to subsequent runs; it does not add recipients to old editions. Disabling a target requests cancellation of its old batches. Disabling the content subscription requests cancellation of all its batches. Editing the schedule only cancels batches still waiting for content.

V6 adds delivery settings, target selections, captured delivery batches and the dispatch outbox. A scheduled edition, READY state and all generated parts commit atomically. Preview creation never creates a delivery batch. FULL includes all available 20 headlines and top-10 details per category, retaining insufficiency notices. BRIEF includes all headlines plus details for ranks 1–3 when available in each category, labels omissions and links to the authenticated full edition. HTML is escaped; only absolute HTTP(S) links without embedded credentials become links. Each part is frozen under `news-delivery-v1`; splitting does not truncate URLs or Unicode.

Feishu serialization accounts for quotes, UTF-8 bytes, signature overhead and mention neutralization and stays within 14 KiB (below Messaging's 16 KiB limit). Email uses bounded HTML/plain-text parts. Each part uses `news-part:{partId}` and the v2 content hash. Earlier parts must be accepted before later parts are submitted, except that cancellation can settle every part. One channel's failure does not resubmit other accepted channels.

PENDING/SUBMITTING are local submission states; ACCEPTED/SENDING/PROVIDER_ACCEPTED/FAILED/UNKNOWN/CANCELLED/EXPIRED reflect Messaging. REJECTED means a request/contract/configuration rejection requiring explicit recovery. Mixed terminal results display PARTIAL. UNKNOWN is polled for external reconciliation but never automatically resent. Lost-create cancellation uses Messaging's cancel-by-key tombstone, not another sendable creation.

Configuration (all secrets stay server-side):

- `NEWS_DELIVERY_ENABLED=false`, `NEWS_DELIVERY_TIMER_ENABLED=true`.
- `MESSAGING_SERVICE_URL` and `MESSAGING_OUTBOUND_CLIENT_TOKEN`.
- `NEWS_READER_URL`: HTTPS reading page without credentials, query or fragment; `?edition=<id>` is appended. Existing login and owner checks protect the edition.

Sending requires both News and Messaging outbound flags plus the enabled, deployed Scheduler task. Setting the News flag off stops new News submission; it does not revoke already accepted provider work. Use target/subscription cancellation and verify actual batch states before treating an existing delivery as stopped.

The Gateway routes and backend are implemented. The webpage delivery panel and real provider acceptance are still pending at this batch.


### Web integration status (batch 9)

The authenticated news page now supports verified target selection, per-target FULL/BRIEF mode, revision conflicts, part status, stop requests, explicit retry risk, and original-request recovery. Browser requests carry no owner, destination address or provider secret. Local verification is recorded in `docs/verification/daily-news/2026-10-18-news-delivery-web.md`; no real provider delivery is claimed.


## Retention and operations (batch 10)

- Owner-scoped edition reads and original-key replays return HTTP 410 / NEWS_026 after payload expiry; expired bodies are excluded from the readable history list. Identity, request hash and owner isolation remain in force.
- New editions may report EVIDENCE_EXPIRED for a top-ten item whose source evidence was compacted. Historical editions retain their frozen excerpts until edition retention applies.
- Admin-only GET `/internal/admin/operations` and `/internal/admin/retention/preview` return aggregate counts without owner, target or body data. POST `/internal/admin/retention/sweep` processes a bounded batch and requires NEWS_RETENTION_ENABLED; disabled returns NEWS_027 / 503.
- PUT `/internal/admin/sources/{id}/retention` accepts only `{ "evidenceHours": 24 }`; actual hours must be positive and no longer than the configured global maximum. No source is created by this endpoint.
- `/actuator/prometheus` and other exposed actuator detail endpoints require NEWS_ADMIN_TOKEN. Only `/actuator/health` remains anonymous with hidden details.
- Releasing content is irreversible without an appropriate backup; post-compaction rollback binaries must understand nullable payloads. The deployment runbook records remaining MySQL, Messaging and live acceptance work.


## Live citation retention state (batch 11)

`GET /internal/v1/news/editions/{id}/evidence?ownerId=...` is proxied by both authenticated Gateway news prefixes. Gateway injects the authenticated owner, ignoring client owner/article parameters. Ownership is checked before citation lookup; missing/other-owner editions return 404, expired owned editions 410/NEWS_026.

Response: `{editionId, checkedAt, items:[{articleId,status,expiresAt}]}`. Items are deduplicated citations from the saved edition, never an arbitrary client article list. AVAILABLE means retained local source evidence within its current policy, RETENTION_EXPIRED means deadline reached or evidence removed, UNAVAILABLE means missing/mismatched version or no retained text. Missing/mismatched records have null expiresAt. No article text is returned and no external page fetch occurs.

The effective deadline is fetchedAt plus the shorter of the source override and global evidenceHours. Deadline evaluation does not wait for an enabled cleanup timer. Removed content remains expired even if policy is later extended. This endpoint and its web view are independent of the immutable edition; existing excerpts and citations remain unchanged. Failed status refresh must not present stale availability as a successful current check.
