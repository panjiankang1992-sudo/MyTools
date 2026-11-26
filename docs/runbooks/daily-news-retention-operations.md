# 每日新闻：保留清理与运行检查

状态：第十批工程实现，未在生产启用。真实 MySQL、生产指标抓取及告警触发待验收。

## 配置与处理范围

| 配置 | 默认值 | 限制与含义 |
| --- | --- | --- |
| NEWS_RETENTION_ENABLED | false | 清理总开关；关闭时仍可预览和查询状态 |
| NEWS_RETENTION_TIMER_ENABLED | true | 总开关打开后，每分钟处理一轮 |
| NEWS_EVIDENCE_RETENTION_HOURS | 168 | 1—168 小时；文章原文、GitHub 描述正文 |
| NEWS_METADATA_RETENTION_DAYS | 90 | 38—365 日；文章元数据与 GitHub 指标快照 |
| NEWS_EDITION_RETENTION_DAYS | 180 | 1—730 日；日报正文，未完成关联投递会阻止清理 |
| NEWS_DELIVERY_RETENTION_DAYS | 90 | 1—365 日；News 冻结消息副本，仅清理已过期的已知终态 |
| NEWS_RETENTION_BATCH_SIZE | 250 | 1—500；每类每轮的最大处理量 |
| NEWS_DB_CONNECTION_TIMEOUT_MS | 3000 | 数据库连接池等待上限，单位毫秒 |

来源可以通过管理接口配置更短的正文保留小时数，上限为系统 evidenceHours。更短条款取更短配置；禁止落地正文的来源应使用 METADATA_ONLY，而不是依赖稍后清理。该设置控制原文副本；已生成节选/日报的权限仍应在来源准入时核实。

默认每分钟处理有界批次以持续消化积压，不执行一次无上限的全表删除。实例共享持久租约；每轮事务上限 30 秒，租约 120 秒。失败回滚本轮修改并记录 FAILED；进程中断后租约到期可恢复。未完成的租约不会被人工清空。

原文释放时保留文章 ID、URL、原内容哈希和过期标记，新生成日报显示 EVIDENCE_EXPIRED。GitHub 只释放描述，七日趋势计算需要的指标仍按元数据周期保留。最终删除超过元数据期的旧候选，不改变已生成日报的内容快照。

日报到期后仅置空正文，保留 owner、请求哈希、幂等键和 ID。读取返回 HTTP 410 / NEWS_026；原键重放仍返回过期，不新建第二份日报。可读历史列表不再列出这些正文已过期的记录。

News 投递正文仅在已过期、超过保留期、无重试意图、无活动租约，且状态为 PROVIDER_ACCEPTED / CANCELLED / EXPIRED / FAILED 时释放。保留原 ID、哈希和状态；即便取消操作重新提前处理时间，已释放内容也不能被重新领取。UNKNOWN、REJECTED、待执行与待重试记录保留，须先完成对账/修复；不可假定它们未发送。

## 本机检查工具

在 News 服务所在主机的受控进程环境提供 NEWS_ADMIN_TOKEN。工具只允许 HTTP 回环 IP，禁用环境代理和重定向；令牌不能通过 CLI 参数传入，也不应输出整个环境文件。

```bash
python3 service/deploy/news_operations.py
python3 service/deploy/news_operations.py --action preview
```

默认查询 `/internal/admin/operations`，包含聚合状态、保留策略、到期候选和最后检查点。不包含 owner、目标地址、正文、租约凭据或密钥。

先确认 `preview` 中的候选数量及当前保留策略，再执行一个批次：

```bash
python3 service/deploy/news_operations.py --action sweep
python3 service/deploy/news_operations.py --action sweep --execute
```

第一条仅显示需要显式执行，绝不发送请求；第二条才执行清理。服务总开关关闭时返回 503。若其他实例持有租约，返回空计数 `{}`，本次不修改内容。不要为了避开租约创建新清理身份或手工置空锁。

按已核实的来源要求缩短保留期：

```bash
python3 service/deploy/news_operations.py --action source-policy \
  --source-id verified-source --evidence-hours 24
python3 service/deploy/news_operations.py --action source-policy \
  --source-id verified-source --evidence-hours 24 --execute
```

`verified-source` 是格式示例，应替换为已登记的来源 ID。调长保留期不会恢复已经释放的正文。

## 指标与告警

`/actuator/health` 可匿名读取整体状态，隐藏内部详情。`/actuator/prometheus` 仅接受 NEWS_ADMIN_TOKEN，Gateway 用户代理令牌无权读取；指标具有 `application="news-digest-service"` 标签。

固定指标包括数据库可用性、启用/失败来源数、采集逾期、任务死信、待生成数量、投递待处理/未知/拒绝、最老待投递时间、最老到期文章证据积压和清理失败状态。不存在 owner、来源 URL、目标或正文等高基数/敏感标签。聚合查询缓存三十秒；数据库失败也缓存，避免一次抓取重复耗尽连接超时。失败时数据库可用性为 0，依赖数据库的业务计数为 NaN，不伪装为 0。

告警模板位于 `service/deploy/monitoring/news-digest-alerts.yml`。保留失败/积压告警只在清理启用时生效。模板不包含通知接收人，尚未安装到真实 Prometheus。当前环境缺少 promtool；已验证 YAML 结构及实际导出指标，PromQL 规则检查和现场告警演练仍需执行。

建议部署前检查：

```bash
promtool check rules service/deploy/monitoring/news-digest-alerts.yml
```

保留积压告警覆盖文章证据，其他类型的到期数量通过管理接口查询。健康 UP 仅表示进程和依赖可用，不代表来源数量达标、消息已送达或不存在 UNKNOWN。

## 恢复与回退

1. 发布 V7 与兼容正文过期的新读取代码，初始保持清理总开关关闭。先在真实 MySQL 的副本验证迁移、索引与查询计划；本地 H2 不能替代此项。
2. 预览候选，核对来源权限、保留期及备份方案。先关闭 timer，仅显式执行一个批次，再查 checkpoint 和到期数量，确认后启用定时器。
3. 遇到 FAILED，检查数据库容量、连接和损坏记录。修复根因后重复原清理操作即可；不修改文章身份、投递身份或 Scheduler 任务键。
4. UNKNOWN / REJECTED 积压按 Messaging 真实状态对账；原键恢复不能被替换成创建新请求。客户端显示“提供方已接收”不等于收件箱送达或已读。
5. 关闭 NEWS_RETENTION_ENABLED 会停止后续清理，不会还原已释放正文。**一旦启用过清理，应用回退版本必须识别正文为空和 NEWS_026；不能回退到 V7 之前的读取代码。** 保留迁移与幂等记录，不通过重建数据库回退。

当前仍保留精简身份/审计行、地区记录、撤稿历史和控制表；它们不是无限量正文副本，但总行数仍会随使用增长。Messaging v2 消息副本清理和旧日报实时引用状态已在第十一批实现，见下文；跨来源派生内容许可到期、存储配额和完整发布装配继续推进。本文不声称全部存储已经有总容量上限。


## Messaging v2 副本保留（V20）

独立配置 `MESSAGING_OUTBOUND_RETENTION_ENABLED=false`，`MESSAGING_OUTBOUND_RETENTION_TIMER_ENABLED=true`；总开关关闭时定时器不启动。`MESSAGING_OUTBOUND_CONTENT_RETENTION_DAYS`、`MESSAGING_OUTBOUND_ATTEMPT_RETENTION_DAYS` 默认 90，允许 1—365；`MESSAGING_OUTBOUND_RETENTION_BATCH_SIZE` 默认 250，允许 1—500。

仅清理 `messaging_outbound` 及 `messaging_outbound_attempt`，不清理既有邮件/入站历史。正文要求 PROVIDER_ACCEPTED/FAILED/CANCELLED/EXPIRED、发送期限已过、updated_ms 超过保留天数、无活动 relay 租约。尝试要求已结束且超过保留期、所属正文已清理、所属投递已知终态；未核对 UNKNOWN 尝试不清理。每类每批各受上限约束，持久租约 120 秒、事务 30 秒。

管理接口位于 Messaging 服务 `/internal/v2/messaging/admin/retention`（GET），执行一批使用其 `/sweep`（POST），仅接受 `MESSAGING_OUTBOUND_ADMIN_TOKEN`。状态仅返回策略、候选计数、未知/发送中计数和检查点；不输出消息、地址、租约或凭据。不要把此令牌写进命令参数、日志或共享文档，也不要使用 News 管理工具向 Messaging 发送请求。

首次先检查状态与数据库备份，在真实 MySQL 副本验证 V20；保持 timer 关闭进行单批验收，再开启定时器。清理保存严格内容身份摘要，保留原请求键、目标、摘要及状态。同键重放仍匹配原内容，包括缺省 HTML 与空 HTML 的区别。已清理内容不得再次发送。

遇到 FAILED 先修复数据库或损坏记录，再运行同一操作；UNKNOWN 先按提供方证据核对，不能清空状态冒充未发送。**一旦执行过清理，回退版本必须兼容 V20 的空正文和内容身份摘要；关闭开关不会恢复正文。** 未解决 UNKNOWN 及精简身份行仍会占空间，应纳入人工处置和容量观察。

## 日报中的当前引用状态

`GET /api/v1/news/editions/{id}/evidence`（APP 前缀同样支持）只核对本人日报已有引用，不能传 owner 或文章列表扩大范围。AVAILABLE 表示本地正文仍在当前保留窗口，RETENTION_EXPIRED 表示已到期或已清除，UNAVAILABLE 表示版本不符、元数据不存在或无保留正文。

状态即使在清理关闭时也按保留截止时间计算。已清除正文不会因调长来源期限而恢复。接口不返回原文，也不会检查外部网站。网页展示核对时刻，并可单独刷新；失败会清除旧的“证据已保留”标签，仍可阅读生成时的日报节选。冻结日报、引用 URL 和原哈希不被改写。

## 采集容量额度（第十七批）

News V8 为文章版本、GitHub 快照、撤稿记录增加持久额度。默认文章 250,000 行、快照 250,000 行、撤稿 100,000 行，文章和快照合计计费正文 536,870,912 字节。环境键分别为 `NEWS_CAPACITY_ARTICLE_ROWS`、`NEWS_CAPACITY_SNAPSHOT_ROWS`、`NEWS_CAPACITY_WITHDRAWAL_ROWS`、`NEWS_CAPACITY_PAYLOAD_BYTES`；所有实例必须使用一致配置。额度始终生效，没有关闭开关。

字节按序列化 JSON 的 UTF-8 长度计算，文章额外预留 128 字节用于未来到期标记；正文压缩后按实际字节结算，删除过期元数据后同时归还行额度和剩余字节。撤稿记录仍保留，达到其额度时拒绝新增撤稿所在的采集批次，不能自动删除撤稿来恢复采集。

额度锁、采集内容、来源 ETag/游标及扣费在同一事务内提交。任何一项超额，整个批次回滚，HTTP 返回 `503 / NEWS_028`；正常采集入口将来源标记为 `STORAGE_FULL` 并冷却 15 分钟。幂等重复版本不重复计费；已有日报和投递继续可读、可处理。调低额度不会删除已有数据，清理和重复请求仍可执行。

`GET /internal/admin/operations` 的 `collectionCapacity` 返回 `scope=COLLECTION_DATA`、`used`、`measured`、`accountingMatches` 与当前实例 `limits`。该管理查询会扫描三类记录的计费字段完成只读核对；它不是自动修账功能。若账本不一致，应先停止采集及清理写入，查明旧程序或绕过仓储的 SQL 写入，备份并制定恢复步骤。不要清零计数或删除日报/投递身份来绕过门禁。

Prometheus 公开四类用量及限额，固定指标前缀为 `news_collection_capacity_`；`NewsCollectionCapacityHigh` 在任一利用率达到 80% 且持续 10 分钟后报警。指标用量仍使用三十秒缓存，报警规则已随发布包装配；规则是否被实际监控系统加载需现场核对。

V8 先按现存数据回填，现有用量超过配置也不会使迁移删除数据；下一次新增写入受额度约束。升级时停止全部 News 写入进程、备份、迁移和启动新版本，避免旧新写入程序混跑。数据库 CHECK 会阻止旧程序省略计费字段的文章、快照、撤稿插入；这不表示任意直接 SQL 都能自动计费。回退到 V7 程序时保持采集和清理关闭，或使用经核验的升级前备份；禁止删除 V8 历史、手工 repair 或清零账本。

此额度覆盖采集数据的行数和计费 JSON，不包含索引、表空间、binlog、临时文件、订阅、日报身份、任务/投递 Outbox、地区、Messaging 数据及其他业务。不能称为整库或主机磁盘硬上限。N23 的完整控制记录额度和派生内容授权期限仍待完成。

## 订阅与内容身份额度（第十八批）

News V9 增加 `news_control_capacity` 和 `news_content_identity`。默认配置为：

| 配置键 | 默认值 | 占用规则 |
| --- | ---: | --- |
| `NEWS_CAPACITY_SUBSCRIPTIONS` | 1000 | 每位所有者首次建立订阅占一行，停用不归还。 |
| `NEWS_CAPACITY_TARGET_OWNERS` | 1000 | 每位所有者首次保存目标设置占一行，清空目标不归还。 |
| `NEWS_CAPACITY_DAILY_RUNS` | 100000 | 每次首次接收每日运行占一行，包含过期和取消记录。 |
| `NEWS_CAPACITY_PREVIEW_IDENTITIES` | 100000 | 非定时预览身份占一行，地区绑定和日报共享额度。 |

额度为全服务范围，所有实例必须一致；它们预留多用户数据隔离，但不是每用户独立公平配额。新请求超限返回 `503 / NEWS_029`。调度接收失败会一起回滚运行、Outbox、目标批次和订阅日期推进，不能跳过这一天后假装已处理。

定时运行在接收时已占用后续工作所需的身份槽位。发布时不再申请预览额度，即使预览和运行额度已满，也允许已有任务绑定地区、生成日报及固定投递分片。定时身份必须对应真实运行、所有者、截止时间和时区，一个运行最多一个内容身份。目标在首次扫描时固定一次，后续重试不能把新目标追加给旧日报；即使接收时投递关闭，也已完成空目标捕获。现有每期最多 5 个目标、每目标最多 100 个分片继续生效。

由这些聚合约束可推得：新运行对应的日报、冻结地区各最多一行；新闻生成任务 Outbox 最多每运行一行；投递批次最多 `5 × dailyRuns`，分片最多 `500 × dailyRuns`；当前目标行最多 `5 × targetOwners`。地区任务的 Outbox 不在这里，它仍需独立额度。历史数据按实际记录回填，不因超过新额度而被删除；本批没有对历史异常数据重新削减行数。

预览先固定地区。生成失败后，已提交的地区身份仍保留，重试不再次扣费；若地区解析在事务提交前失败，身份和额度一起回滚。正文到期、停止发送、UNKNOWN 对账或取消不会释放身份额度，防止原幂等键被重新使用。额度满时仍可读取、重放、停用订阅、移除目标、取消或重试已有投递。

V9 将旧日报与旧地区绑定按 `(owner_id, idempotency_hash, request_hash)` 合并为 `LEGACY` 身份，统一计入预览额度；旧定时内容可能因此保守地占用预览和运行两类额度。旧记录相同 owner/key 却使用不同 request_hash 时迁移失败，需要先调查，禁止自动 repair。所有旧运行标记为已经捕获目标，避免升级后为旧日报新增收件人。两个内容表的外键阻止未登记身份的新写入。

管理状态新增 `controlCapacity`，包含 `used`、`measured`、`accountingMatches`、`limits`。只读核对扫描固定控制表，不会修账。Prometheus `news_control_capacity_*` 使用固定指标和三十秒缓存，`NewsControlCapacityHigh` 在任一利用率达到 80% 持续十分钟后报警。账本差异应先停止写入调查，禁止靠删除身份或清零计数恢复服务。

升级必须停止全部旧 News 写入者，备份后迁移到 V9 再启动新版本。旧代码不会更新控制账本，不能与新代码混跑；内容外键也意味着旧版不能直接写入新内容键。回退时保持 News 采集、调度和清理关闭，使用经验证的升级前备份或前滚修复。

本批覆盖控制记录的行数预留，仍不代表整体数据库、索引、日志或磁盘字节上限；来源登记、地区观测/任务/来源包以及 Messaging 独立数据的额度尚待补齐。


## 第十九批：Messaging v2 容量

Messaging V21 的新目的地、普通投递、仅取消身份、冻结 JSON 分别设定有限额度，默认 1000 / 100000 / 100000 / 512 MiB。四项 `MESSAGING_OUTBOUND_CAPACITY_*` 配置详见消息协议；共享同一库的实例必须一致。它不表示整库磁盘容量，也不限制旧邮件、入站或其他服务表。

管理接口 `GET /internal/v2/messaging/admin/retention` 增加 `capacity`，包含账本、实际聚合、当前限额及 `accountingMatches`。达到 80% 时应评估保留策略和经审核的扩容；账本差异需调查，不能重置计数或删除幂等身份。暂未新增 Messaging Prometheus 暴露或现场告警加载。

- 新准入超额返回 503 / MESSAGING_026。原请求重放不重复计费；已接纳消息继续执行、取消、重试和未知结果对账。
- 未知键取消使用独立的有限保留额度，仅写 CANCELLED 和严格身份摘要，不写正文。普通投递满额仍可使用；取消额度也满时保留取消待处理状态，不能显示成功。
- 已结束正文按既有策略清理，字节和正文在同一事务释放；长期身份不释放。UNKNOWN/SENDING 继续占用正文空间。
- V21 回填存量消息，保守计入普通投递；旧正文和状态不改变。尝试记录新增父记录关联和 1–100 代约束，异常历史须调查，不能自动删除。
- 迁移前停止旧 v2 写入者；V21 生效后旧未计费写法会失败。回退需保留兼容 V21 的应用版本，不能直接回退旧 JAR。


## 第二十批：来源与地区登记容量

News V10 增加五项全局身份额度：`NEWS_CAPACITY_SOURCES=1000`、`NEWS_CAPACITY_SOURCE_PACKS=10000`、`NEWS_CAPACITY_REGION_NODES=1000`、`NEWS_CAPACITY_REGION_JOBS=100000`、`NEWS_CAPACITY_DIRECT_OBSERVATIONS=10000`。同库实例须使用一致配置。普通 API 验证之外，新来源/来源包 JSON 最多 32 KiB，新观测最多 8 KiB，防止用一个无限长字段绕过行数限制；不改变存量载荷。

新来源/版本/独立观测或地区任务超额返回 503 / NEWS_030，计费与写入整体回滚。来源启停、同内容重放、地区任务取消和已接纳任务报告不申请新额度。地区任务和节点游标、Outbox 原子预留，任务创建失败不留下新空游标；每个任务独占一个报告名额，不能借给其他节点或第二个报告。独立观测与任务报告身份不能互相挪用。

管理员 operations 响应增加 `registryCapacity`，只读比对五项账本与实际记录。固定用量/限额指标沿用三十秒缓存和私有监控身份，`NewsRegistryCapacityHigh` 在任一项达到 80% 持续十分钟时告警。规则仍须在目标监控环境加载验收。

身份不自动删除或归还，历史日报的地区快照和来源包不改写。需要扩容时先核对账本与真实增长，再审查配置；禁止清空旧报告、来源包或账本恢复名额。这是限定表的准入预算，不是数据库物理磁盘配额。V10 迁移与回退条件见发布手册。


## Messaging 明确正文截止（V22）

`contentExpiresAt` 为客户端声明并冻结的绝对期限，与默认 90 天普通清理分开。未声明的历史消息继续使用旧策略；News 每来源派生期限传播尚未实现。发送和重试按当前时间阻止过期正文，渠道适配器在构造邮件/等待飞书配额之后、发起外部调用之前再次检查。已开始的调用可以完成并保留真实结果，不宣称外部消息能被撤回。

独立定时器每分钟处理一批，即使 `MESSAGING_OUTBOUND_ENABLED=false` 或普通保留清理关闭仍运行。管理员可读取原 retention 接口的 `deadlineCleanup`，或 POST `/internal/v2/messaging/admin/retention/deadlines/sweep` 执行一批。限额沿用保留批次配置，使用独立 120 秒租约/检查点和 30 秒事务；空批次不覆盖普通清理失败。数据库不可用或有积压时物理删除会延迟，发送期限门禁不依赖后台删除进度。

明确到期允许删除 UNKNOWN/SENDING 的正文，但必须保留状态、尝试、严格摘要及身份；不能把未知结果改为“未发送”。正文和计费字节同事务释放，账本错误会回滚并标记失败。已知远端标识可按原接口查询/取消；创建响应丢失且正文已删时，使用 `cancel-by-identity` 提交原始冻结摘要和期限。不能删除幂等身份或伪造空正文补发。

启用期限客户端前升级全部 Messaging 写入者。接受过明确期限后回退必须保持 V22 到期门禁和独立清理；V21 不能作为等价回退。停止进程会停止物理清理，运维需检查积压和恢复后的批次收敛。
