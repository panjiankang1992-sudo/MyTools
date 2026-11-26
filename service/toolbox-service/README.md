# toolbox-service

禹宇天的工具箱公开门面服务（Java 21 + Spring Boot 3.2.5）。

- 只实现匿名公开 API，不实现任何 PDF 上传/任务接口。
- 不加入根 `pom.xml` 与 `service/pom.xml`，只能通过 `-f service/toolbox-service/pom.xml` 构建。
- 所有响应统一信封：成功 `{"code":"OK","data":...,"requestId":...}`，失败 `{"code":"TOOLBOX_00N","messageKey":...,"details":...,"requestId":...}`。
- 服务端只返回 `messageKey`，不返回任何中文提示；中文由前端本地化解析。

## 1. 接口

上下文路径固定为 `/public/v1`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/public/v1/session` | 签发或复用匿名会话 cookie，返回 `{expiresAt, csrfToken}` |
| GET | `/public/v1/tools` | 工具注册表、能力、限额与 `policyVersion` |
| GET | `/public/v1/textbooks` | 已发布教材版本与 20 个必需槽位覆盖 |
| GET | `/public/v1/textbooks/{editionId}/versions/{version}` | 版本结构、计数与 `sha256`，支持 ETag/304 |
| GET | `/public/v1/textbooks/{editionId}/versions/{version}/entries` | 条目分页，支持 `unitIds`、`lessonIds`、`listTypes`、`cursor`、`limit` |
| POST | `/public/v1/events` | 匿名使用事件批量上报（最多 50 条、请求体最多 32 KiB） |
| POST | `/public/v1/translations` | 匿名中英翻译（最多 20 条，逐项成功或失败） |
| GET | `/public/v1/stats` | 聚合统计（`days`=7/30，`category`=all/pdf/copybook/words） |
| GET | `/public/v1/health` | Actuator 健康检查，`show-details=never`，不泄漏配置 |

事件和翻译接口必须同时满足：

1. 携带有效会话 cookie；
2. 请求头 `X-Toolbox-Csrf` 与会话绑定的 CSRF token 一致；
3. 若带 `Origin`，必须与 `toolbox.public-origin` 或 `toolbox.public-origins` 中的某一项精确相等（不允许 `*`）。

缺少或过期 cookie 返回 `TOOLBOX_002`（401）；CSRF 或来源不通过返回 `TOOLBOX_010`（403）。

## 2. 运行

```bash
# 编译
mvn -f service/toolbox-service/pom.xml clean compile

# 测试（H2 + MySQL 兼容模式）
mvn -f service/toolbox-service/pom.xml test

# 打包
mvn -f service/toolbox-service/pom.xml package -DskipTests

# 本地运行
TOOLBOX_DB_NAME=mytools_toolbox \
TOOLBOX_DB_USER=mytools_toolbox \
TOOLBOX_DB_PASSWORD= \
TOOLBOX_PUBLIC_ORIGIN=http://localhost:23111 \
TOOLBOX_CATALOG_PATH=./content \
mvn -f service/toolbox-service/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

默认端口 `23112`，默认上下文路径 `/public/v1`。`local`/`test` profile 会把会话 cookie 名称切换为 `toolbox_guest_local` 并关闭 `Secure`；生产默认使用 `__Host-toolbox_guest` 且强制 `Secure`。

## 3. 配置

环境变量名见 `.env.example`，配置文件为 `src/main/resources/application.yml`。

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `toolbox.public-origin` | 空 | 精确站点来源，用于同源/CSRF/来源校验 |
| `toolbox.public-origins` | 空 | 逗号分隔来源清单；非空时优先于 `public-origin` |
| `toolbox.site-id` | `yuyutian-toolbox` | 事件与聚合的站点隔离标识 |
| `toolbox.catalog-path` | `./content` | 只读的已发布教材根目录 |
| `toolbox.policy-version` | `2026-10-02` | 公开策略版本 |
| `toolbox.pdf-enabled` | `false` | 公开 PDF 执行链开关（本里程碑不实现执行） |
| `toolbox.stats-enabled` | `true` | 关闭时 `GET /stats` 返回 `TOOLBOX_008`（503） |
| `toolbox.session.cookie-name` | `__Host-toolbox_guest` | 会话 cookie 名称 |
| `toolbox.session.cookie-secure` | `true` | 仅 HTTPS 下发 |
| `toolbox.session.ttl-hours` | `24` | 会话固定有效期，创建后不延长 |
| `toolbox.events.max-batch-size` | `50` | 单批事件上限 |
| `toolbox.events.max-body-bytes` | `32768` | 事件请求体上限 |
| `toolbox.stats.cache-ttl-seconds` | `300` | 统计响应缓存秒数，0 表示关闭 |
| `toolbox.stats.aggregate-interval-ms` | `60000` | 聚合任务间隔 |
| `toolbox.stats.aggregation-enabled` | `true` | 是否启用定时聚合（测试中关闭并同步调用） |
| `toolbox.curriculum.entries-default-limit` | `100` | 条目分页默认条数 |
| `toolbox.curriculum.entries-max-limit` | `500` | 条目分页上限 |

## 4. 数据库

Flyway 迁移位于 `src/main/resources/db/migration/`；V1 为原公开门面结构，V2 仅新增 `toolbox_translation_quota` 字符用量表（pom 同时保留 `db/migrations -> db/migration` 的资源映射）。

- 业务表：`toolbox_guest`、`toolbox_event`、`toolbox_success_dedupe`、`toolbox_stat_outbox`、`toolbox_stat_applied`、`toolbox_daily_stat`、`toolbox_stat_cursor`。
- 预留表：`toolbox_upload`、`toolbox_task`、`toolbox_task_input`、`toolbox_cleanup`、`toolbox_execution_slot`、`toolbox_outbox`。当前 Java 代码不读写这些表。
- DDL 保持可移植（H2 MySQL 兼容模式可直接执行），并显式声明 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`，因此不需要额外的 MySQL 专用迁移。

## 5. 教材内容包

```
<root>/catalog/requirements.json
<root>/catalog/published.json
<root>/textbooks/<editionId>/<contentVersion>/book.json
```

- `published.json`：`{"catalogVersion":"...","editions":[TextbookEdition...]}`；每个 edition 必须包含 `editionId`、`contentVersion`、`contentSha256`（`book.json` 字节的 SHA-256）与 `availableEntryCount`。
- `book.json`：`{"edition":TextbookEdition,"units":[...],"lessons":[...],"entries":[...]}`。
- `requirements.json`：`{"requirementsVersion":"1","slots":[{"subject":"chinese","grade":1,"semester":"first"}, ...]}`，共 20 个槽位。

启动时校验清单与每个已发布正文：解析失败、文件缺失、哈希不一致、年级越界、条目归属错误都会让进程启动失败。目录或清单缺失时只记录警告并提供空的公开数据。

## 6. 实现决策

契约未明确或存在多种合理解释时，按“失败关闭、不伪造数据”的原则选择，并记录如下。

1. **messageKey 取值**：契约只给出了 `TOOLBOX_005 -> task.limitExceeded` 一个示例。其余键按语义固定为 `request.invalid`、`session.required`、`resource.notFound`、`request.conflict`、`request.rateLimited`、`resource.expired`、`service.unavailable`、`input.unsupported`、`request.forbidden`、`content.unusable`、`upstream.invalidResult`，由 `ErrorCode` 统一维护，禁止硬编码提示文案。
2. **未预期异常**：映射为 `TOOLBOX_008`（503，`service.unavailable`），服务端记录堆栈，响应只保留稳定字段。契约没有为内部错误定义单独错误码。
3. **CSRF token 来源**：契约第 5.1 节规定服务端“只保存 `sha256(token)`”。因此不新增列，CSRF token 由 `sha256(token_hash || guestId || "toolbox-csrf-v1")` 确定性派生：会话期间稳定，重发 `/session` 返回同一个值，且在不知道 cookie 的前提下无法伪造。
4. **`/session` 请求体**：只接受空体或 `{}`；出现任何字段按未知字段拒绝（422），超过 1 KiB 按 `TOOLBOX_005` 拒绝。实现直接读取原始字节，避免无 `Content-Type` 的客户端被 415/422 误判。
5. **来源校验缺省行为**：没有 `Origin` 头时不因来源被拒绝（同源导航与非浏览器客户端可能不带），但变更接口的 CSRF 校验仍然强制；带 `Origin` 且不属于允许清单时一律 403。
6. **教材 JSON 结构**：契约只给出 TypeScript 类型，未固定清单文件结构，本服务按第 5 节结构实现，并校验 `availableEntryCount` 等于正文实际条目数（不一致即启动失败，防止清单与正文漂移）。
7. **覆盖状态计算**：同一槽位没有版本为 `missing`，存在 `complete` 版本为 `complete`，否则为 `partial`。带过滤参数时覆盖按过滤后的版本集合计算，保证同一次响应内部自洽。
8. **条目排序**：单元顺序 → 课文顺序（未归属课文的条目排在该单元已有课文之后）→ 条目顺序 → `entryId`。`total` 恒等于按同一过滤条件全量翻页取得的数量。
9. **游标**：不透明 `base64url(JSON)`，内容为 `{versionKey, filterHash, offset}`，`filterHash` 是同维度排序去重后的 SHA-256。跨版本或跨过滤集合一律 `TOOLBOX_001`。
10. **`context` 字段**：按契约 3.1 逐字段返回。发布方必须遵守契约 3.2.8，不得在 `context` 中保留待填答案。
11. **事件的 details 与重复**：每条事件单独事务。`(site_id, guest_id, event_id)` 唯一键冲突报告 `duplicate_event`；成功语义键 `(site_id, source, guest_id, tool_id, operation_id)` 冲突报告 `duplicate_success`，并把整条事务回滚（不留下明细、不写 outbox）。
12. **聚合水位与完整性**：`complete_through` 只在“没有未完成事件”时推进到最新 `received_at`，否则停在最早未完成事件的接收时间。`series` 中非当天自然日只有在水位越过当日 24:00 时才标记 `complete=true`；水位缺失（从未聚合）时所有桶均为 `complete=false` 且计数为 `null`，绝不伪造成 0。当天一旦有水位即按已聚合数据呈现，滞后由顶层 `partial` 表达。
13. **统计口径**：`allTimeSuccess` 来自长期日聚合行；`totals` 与 `ranking` 都遵循 `category` 过滤（选择分类时累计值也只显示该分类）。计数为 JSON 数字，超过 `2^53-1` 时前端会丢失精度，当前聚合规模远低于该上限。
14. **`stats-enabled=false` 的优先级**：`GET /stats` 先判断开关，关闭时任何参数组合都返回 `TOOLBOX_008`，避免暴露参数细节。
15. **健康检查路径**：`management.endpoints.web.base-path=/`，因此健康检查位于 `/public/v1/health`，并设置 `show-details=never`、`show-components=never`。
16. **`count` 列名**：契约第 6.2 节固定为 `count`，H2 MySQL 兼容模式可直接使用，故保留原名而不改名。
17. **事件时间**：`occurred_at` 以 UTC 字面值存储，按 `Asia/Shanghai` 计算自然日；禁止使用 JVM 默认时区。
18. **PDF 限额声明**：`/tools.limits.pdfExecution` 返回契约 5.5 的初值，但 `active=false`、`noteKey=limits.pdfExecutionInactive`，明确本部署尚未启用执行链，不宣称已生效。

## 7. 自动翻译接口

`POST /public/v1/translations` 沿用匿名会话 Cookie 和 `X-Toolbox-Csrf`，不接收 MyTools 登录令牌或供应商密钥。请求为 `{"items":[{"id":"row-1","text":"Monday"}]}`，成功信封的 `data.items` 按输入顺序返回 `id`、原始 `sourceText`、`englishText`、`meaning`、`status`。状态只有 `translated` 和 `failed`；失败项的两种语言字段为 `null`，可以单项重试。整批无效输入返回既有错误信封，不部分处理。

- 每批 1–20 项，批内 ID 唯一且不超过 128 字符；正文非空、无控制字符、不超过 120 Unicode 码点和 500 UTF-8 字节；请求体最大 32 KiB。
- 已发布英语教材唯一精确匹配、星期、月份和常见数字优先本地解决。混合汉字和拉丁字母的条目除安全词典精确配对外逐项失败，防止把英文答案夹带进中文题面。
- 未知条目只调用固定的 MyMemory 官方 GET 地址，含汉字使用 `zh-CN|en`，其余使用 `en|zh-CN`；不使用公开投稿 `set` 接口、不上传用户 IP、email 或认证信息。实体解码后校验语言、非空、回声、控制字符及长度，不伪造上游结果。所有成功配对统一要求英文含拉丁字母且无汉字、中文含汉字且无拉丁字母；上游夹带英文答案的中文解释逐项失败且不缓存。
- 连接最多 2 秒，完整响应最多 4 秒、64 KiB；全局外部并发默认 2，队列最多 40 项。每批最多等待 25 秒，保留已经完成的结果并取消剩余任务。
- 成功缓存默认 2048 项、24 小时；失败不缓存。未知词缓存区分大小写，避免 `US` 和 `us` 相互污染。本地及缓存命中不消耗外部字符额度。
- `toolbox.translation.global-daily-characters=4500`、`guest-daily-characters=1000`，按 UTC 日期在数据库原子预占；已经发送的失败请求不退还额度。仅存日期、匿名计数键和字符数，不存翻译正文。MyMemory 上游可单独拒绝已耗尽的共享出口额度。
- 其他配置为 `concurrency`、`cache-entries`、`cache-ttl-seconds`、`batch-timeout-millis`。没有可由请求指定的上游 URL、模型或密钥。

官方规范已于 2026-10-04 核实：匿名调用每日 5000 字符、单条 `q` 最多 500 UTF-8 字节。参见 [Usage Limits](https://mymemory.translated.net/doc/usagelimits.php) 与 [API Specifications](https://mymemory.translated.net/doc/spec.php)。本机及部署源站各两次只读真实请求均返回 `butterfly → 蝴蝶`、`蝴蝶 → Butterfly`，HTTP 与 `responseStatus` 均为 200，`quotaFinished=false`；这仅确认上游通达性，不代替公开接口与界面验收。

V2 是增量计数表，不修改 V1 结构。回滚旧 JAR 时保留该表和 Flyway 历史记录，不执行 DROP 或回退数据库版本；旧服务不访问新表。H2 集成测试使用仅含 V1 的迁移目录执行 Flyway 校验和迁移，已确认 V2 表及计数保留。重新启用新版本时继续沿用已占额度，不能通过重启或回滚清零免费调用预算。

## 8. 明确不在范围内

- 全部 PDF 上传、任务、产物下载接口与公开 PDF 执行链（含专用包、Scheduler 域守卫、Storage 受控删除）。
- 客户端统计 SDK、前端文案与页面。
- 反代、TLS 部署与真实生产 MySQL 压测；测试使用 H2 MySQL 兼容模式，事务与唯一键语义仍需在最终集成环境复核。
