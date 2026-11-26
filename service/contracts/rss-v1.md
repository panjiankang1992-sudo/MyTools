# RSS v1 契约

公开前缀 `/api/app/v1/rss` 和 `/api/v1/rss` 等价；使用既有 MyTools Bearer 登录会话。Gateway 固定转发到 `/internal/v1/rss`，内部令牌与 `X-RSS-Owner` 由认证主体生成，客户端不能指定 owner。返回现有 `{code,message,data}` 信封，成功码 `0000`，创建 201，刷新排队 202。

所有 UUID、文章 ID 为字符串；业务时间为 UTC epoch milliseconds，可缺省时间为 null。发现预览的发布时间使用 ISO 8601 字符串。列表不携带正文，详情 GET 不自动设为已读。

| 方法 / 路径 | 请求或作用 |
| --- | --- |
| GET `/capabilities` | pollingEnabled、opml、maxSubscriptions、protocols |
| POST `/discover` | `{url}`，只获取一次；直接返回 preview，或网站声明的至多 10 个 candidates，选择后再次检测 |
| GET / POST `/subscriptions` | 列出 / `{url,title,groupId,initialReadAll}` 创建，重复返回原订阅且不重置状态 |
| GET / PUT `/subscriptions/{uuid}` | 详情 / `{expectedRevision,title,groupId,status,intervalMinutes}`，status 为 ACTIVE 或 PAUSED |
| DELETE `/subscriptions/{uuid}` | 取消订阅；收藏保留；重新添加恢复同一身份 |
| POST `/subscriptions/{uuid}/refresh` | 请求后台抓取，服从退避与冷却，非同步抓取 |
| GET `/entries` | view=unread/all/starred，subscriptionId、groupId、q（100 字）、cursor、limit（1–100，默认 30） |
| GET `/entries/{id}` | 清洗后的正文与用户状态 |
| PUT `/entries/{id}/state` | `{expectedRevision,read,starred}`，两个布尔值必须显式提供；冲突 409，不重放 |
| POST `/entries/mark-read` | `{subscriptionId,groupId,q}`，标记当前范围的事务快照，后续新文章保持未读 |
| GET / POST `/groups` | 列表 / `{name,sortOrder}` 创建 |
| PUT / DELETE `/groups/{uuid}` | `{name,sortOrder,expectedRevision}` / 删除后来源转为未分组 |
| POST `/opml/preview` | `{xml}`，至多 200 条；状态 NEW、DUPLICATE、INVALID；不抓取、不写库 |
| POST `/opml/import` | `{xml}`，逐项返回 IMPORTED、FAILED、DUPLICATE、INVALID；不覆盖已有设置 |
| GET `/opml/export` | `{xml}`，只导出本人有效订阅 |

JSON 请求体最多 2 MiB（含分块传输），Feed 解压前后各最多 2 MiB，禁用 DTD/外部实体；一个用户最多 100 个有效订阅和 100 个分组。App 文件选择器限制 OPML 为 256 KiB。首次订阅默认最近 50 篇未读，可选历史全已读；以后新增文章未读。

分页游标绑定 owner、筛选和首屏最大 ID，翻页排除随后新增文章。文章修订保留首次发现时间、已读与收藏。超过 90 天且未收藏的正文分批清理，保留身份墓碑防止重复抓取后重新出现。

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| RSS_001 | 400 | 参数、游标或版本字段格式无效 |
| RSS_002 | 422 | 非受支持的公开 HTTPS 地址 |
| RSS_003 | 502 | 远端获取失败 |
| RSS_004 | 422 | 不可解析的 Feed/XML |
| RSS_005 | 404 | 不存在或不属于当前用户 |
| RSS_006 | 413 | 字节、深度或条目规模超限 |
| RSS_007 | 429 | 冷却或并发限制 |
| RSS_008 | 422 | OPML 等输入无效 |
| RSS_009 | 409 | 版本/唯一约束冲突 |
| RSS_010 | 401 | 内部认证拒绝（不会原样转发给 App） |
| RSS_011 | 500 | 内部失败（Gateway 返回统一服务错误） |
| RSS_012 | 422 | 个人订阅或分组配额已满 |

数据库除四张业务表外，另有 `rss_owner` 协调行用于用户写入互斥与持久冷却。抓取不持数据库锁；提交需匹配未过期租约，设置变更会使租约失效。首版仅支持单 RSS 进程部署，同主机并发闸门不跨进程。
