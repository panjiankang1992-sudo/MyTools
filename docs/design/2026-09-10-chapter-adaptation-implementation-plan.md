# 书架章节改编实施计划与目标

创建：2026-09-10。状态：实施中。

依据：[细化设计](./2026-09-09-shelf-chapter-adaptation-design.md)。本文件记录实施进度，不能把设计承诺或未执行测试标为已完成。

最新差距、集中交付范围与验证结果见 [差距与集中交付](./2026-09-10-adaptation-gap-and-batch.md)。按用户要求执行一次差距盘点、集中开发、统一验证；修复失败后仅复验受影响范围，不再为每个小改动重复所有服务全量测试。

## 完成目标

用户从本人书架目录选择章节、输入必填意图后创建后台改编；后端冻结原章及邻章，按事实和剧情约束生成并校验，保存每次业务版本和技术调用。再次点击目录图标可查看结果与历史，右上角可优化当前结果或基于原章重新改编。原章节及阅读进度保持可用。

验收必须同时覆盖：本人书架授权、服务端章节身份、意图校验、异步进度、原文快照、三种版本谱系、取消/恢复/幂等、历史只读、受约束生成、App 完整路径及部署配置。真实 Provider 联调和生产开启分别记录，不把 Fake Provider 测试当成真实模型验证。

## 工作区基线

- 基线提交：`6aa5e609`；工作区包含其他任务的大量改动，保留它们。
- 开始实施时 Reader migration 到 V30；Scheduler 工作区现已有其他任务的 V137–V144。本任务追加 Reader V31–V38 和 Scheduler V145–V147；新增迁移前再次检查编号冲突。
- Java 21、Maven 可用；Docker CLI 存在但 daemon 当前不可用。MySQL 验证不能用 H2 的成功替代。
- 此任务新建的设计文档尚未提交；不自动提交或部署。

## 顺序与验收

| 阶段 | 交付 | 验收证据 | 状态 |
|---|---|---|---|
| P1 数据与领域基础 | V31 书架内容绑定、准备任务、目录/正文投影、章节身份；改编状态机、请求与快照校验、稳定错误码 | 迁移测试、跨 owner 外键、CHECK、Unicode/指纹/谱系/状态测试、Reader 回归 | 本地实现及 H2 验证完成；MySQL 门禁待 P7 |
| P2 受控章节读取 | exact source version 目录准备、locator 加密、服务端 canonical catalog/content；remote Media attestation 与受管投影 | source/remote fixtures、目录 seal、旧版本回写拒绝、缓存失效后快照仍可读 | 进行中：source 准备/目录/正文 Reader 路径及夹具已完成；remote 与真实运行时 egress 待实施 |
| P3 业务版本与 API | V32 收据、同意、版本/attempt/validation/lineage/selection；首次/优化/重建、进度/历史/详情/比较/取消/删除 | 幂等重放、单章并发、租户隔离、不可变历史、原文不覆盖 | 进行中：创建/谱系/收据、查询/取消、context seal、冻结比较、受权约束/校验/采用回写及版本化同意/撤销已实现；删除清理及完整前端验收仍待完成 |
| P4 异步执行与恢复 | outbox workers；Scheduler assertion/claim/heartbeat/introspection；Executor broker/回执、预算与取消恢复 | 崩溃点、fence 接管、授权续期、迟到回写、未知结果不重发 | 进行中：派发/取消屏障、授权、Reader 账本/采用/恢复视图及独立未决扫描、加密 relay、运行循环、Host/UDS/Worker 分流及周期恢复已实现；真实隔离及联合验收待完成 |
| P5 模型流水线 | 固定 Provider adapter、规划/生成/校验/一次修复、共享熔断与公版 probe、Fake Provider | 严格解析、约束 fixtures、失败留痕、调用预算、脱敏与取消 | 进行中：Reader 约束/校验/采用及独立入库 schema、Provider/Reader 客户端、四阶段编排及专属任务包已实现；仍需独立平台审核及隔离、公版 probe 和实际模型合约 |
| P6 Gateway 与 App | 书架真实 ID、目录图标、意图面板、进度、结果/版本历史、优化/重建与状态恢复 | ArkTS 编译与相关 App 测试、端到端 API fixtures | 进行中：公开网关、权威目录、必填意图、历史/详情/候选/轮询/取消、来源核验及派生、共享授权与独立账户入口、冻结比较、中文阶段和重启待确认请求已实现；真实联合及设备验收待完成 |
| P7 集成与交付 | MySQL 精确版本门禁、构建回归、发布包/配置/说明、真实 Provider 合约结果 | MySQL/Java/App/任务包测试报告，明确未通过或未执行项 | 待实施 |

P1→P2→P3 先完成数据与业务契约；P4/P5 完成后连接真实异步执行；P6 依据已冻结 DTO 接入；P7 完成才能将整个目标标为完成。每阶段结束更新本文件，未完成项目继续保留。

## 实施约定

- 只使用用户指定 Provider；模型 ID、认证格式和容量通过配置及合约测试明确，不把密钥写进代码或文档。
- 创作操作创建新业务版本；内部重试复用当前业务版本并追加技术记录。
- 每个后台步骤在网络调用前持久化身份与预算，取消/删除/旧 fence 不得推进正式状态。
- 公共 API 不接受任意正文、owner、模型、URL 或 Prompt。
- 以新增类/迁移为主，修改现有文件前检查当前 diff，兼容其他任务改动。

## 当前实施记录

- 已建立任务目标并核对代码、迁移、构建环境。
- P1/P2 新增 V31：7 张绑定、准备、staging、locator、投影、canonical chapter 表，以及 1 张用户准备限额锁表；跨 owner 复合外键均为 RESTRICT，没有修改原章节或读取进度。V31 尚未发布，仅在新建测试库执行；本轮直接补强该未发布迁移，禁止对已部署 V31 的数据库跳过 checksum 检查。
- P1 新增请求意图/Unicode/幂等指纹、三种版本谱系、受守卫状态机、不可变上下文领域对象和 `READER_030`–`READER_059` 错误码。上下文领域对象已经验证角色及邻接身份；持久化 context seal 属于 P3，不能将领域对象校验当作已完成数据库封存。
- P2 为 DiscoveryRepository 增加 owner + source ID + exact version 查询；运行时地址也来自指定版本的快照，不取当前书源行地址，不回落到其他版本。
- P2 ReaderRuntimeClient 新路径使用 owner/source/version/invocation 隔离 namespace；拒绝重复目录定位符、缺失章节、无效定位符、超长正文、重复 JSON 键、压缩响应和重定向。字节限制在接收过程中执行，超时覆盖完整响应体。
- P2 部署配置 `reader.chapter-content.*` 已接入校验，默认：50000 章、目录响应 32 MiB、正文响应 2 MiB、正文 120000 code points、每次完整运行时 HTTP 请求 30 秒。
- P2 source ensure 已落地：只读取本人未删除书架的 source/TXT/EPUB 元数据；短事务建立 binding、加密 BOOK locator 和准备任务。重复 ensure 合并，owner 锁使同时准备的图书数默认不超过 2。
- P2 `SourceCatalogProjectionWorker` 已落地：默认关闭、显式开启后自动轮询。数据库领取租约，锁外取目录，先持久冻结完整 manifest，再分批写 staging、核验连续 ordinal/数量/摘要，最终同事务发布 canonical chapters。过期租约、绑定变更、书架版本变更和缺章均被拒绝；技术重试保留 invocation ID，最多 3 次。
- P2 `SourceLocatorCipher` 使用独立 owner-only 文件中的 AES-256-GCM 密钥环，关联 owner/binding/revision/source version/用途，可同时保留少量旧密钥用于轮换。公开目录只含不透明章节 ID；游标使用用途隔离的派生 HMAC 密钥，绑定 owner/shelf/binding/catalog/末尾序号和 15 分钟期限。
- P2 `SourceLocatorPolicy` 已实现规范化及有限 DNS 预检（3 秒、最多 4 个解析线程），拒绝凭据、片段、歧义数字主机、非标准端口以及私网/特殊地址。此处不是实际取文运行时的 DNS pinning/逐跳 egress 实现，不能据此宣称已解决 DNS rebinding；`runtime-egress-verified` 默认 false，真实运行时隔离仍是 P7 的未完成门禁。
- P2 Reader 私网路由已增加 capability、ensure、catalog 和 chapter content，路径位于 `/api/v1/reader-state/shelves/{shelfBookId}`，由 `reader.shelf-chapters.enabled=false` 默认关闭。ensure 拒绝额外正文/URL/owner/model 字段，新增 `READER_060` 对应 400。Gateway 尚未转发这些新路由，App 尚未连接。
- P2 原站目录变化会标记 STALE；同一绑定刷新使用新 preparation round、复用仍存在的 chapter ID，旧游标失效。正文读取先重验完整目录，再取文并以绑定/目录修订回写 SHA，不覆盖原书或阅读进度。
- P3 新增 V32，共 12 张改编相关表：部署合约、告知/同意、请求收据、业务版本、上下文、谱系、技术调用、约束、校验、采用关系、章节历史删除任务；同时为 canonical chapter 增加不回退的版本分配计数。当前同样是未发布迁移，只在新建 H2 库应用，未应用到生产 MySQL。
- P3 `ChapterAdaptationRepository` 在 owner → shelf → binding → chapter 的短事务内验证当前范围、目录修订、已知正文摘要、登记的启用部署、未撤销同意和限额，原子保存收据/新业务版本/谱系。默认同章仅一个活跃版本，每个 owner 最多两个；没有网络调用处于事务内。创建只进入 `PENDING_DISPATCH/CONTEXT_PENDING`，未宣称已经冻结正文。
- P3 请求收据先于活跃检查和新建开关处理；同 key 相同规范指纹返回原始 202 快照，不随任务状态改变；不同指纹 409。收据另保存不依赖可删除历史外键的 shelf/chapter/trigger 标识，优化/重建的触发历史被删后也能重放 410，并优先识别不同输入的冲突。删除收据读取已验证，实际删除 worker 尚未实现。
- P3 优化保存 parent/trigger，重新改编只保存 trigger 并继承 root；都要求触发版本具有通过校验的采用结果，以及匹配的来源摘要和目录修订。这里拒绝已知不一致，不能把目录缓存检查替代后台 context seal 的实时可信读取。
- P3 详情只读取一个通过校验的采用正文；其他完整、允许展示的 GENERATE/REPAIR 按需读取。PLAN/CRITIC 和安全隔离候选不能通过公开投影读取。每次读取正文再次检查码点数量及摘要；历史分页不包含候选正文。
- P3 `ChapterAdaptationController`/`ChapterAdaptationService` 已提供受既有 Reader 服务令牌保护的私网创建、优化、重建、状态、详情、历史、候选和取消路由。拒绝额外字段、重复 JSON 键、尾随第二份 JSON、非整数修订及超长请求；所有本功能成功/错误响应设置 `no-store, private` 和 `nosniff`。后续 P6 已补 Gateway 转发，真实认证联合链仍待验证。
- P3 新增独立 `reader.adaptation.read-enabled/create-enabled`，创建默认 false；历史签名游标绑定用途、owner、书籍、章节和 15 分钟期限。密钥环路径有配置时，即使章节准备开关关闭也加载旧密钥，以保持历史分页可用；没有配置密钥不能签发游标。
- P3 取消已终态是幂等读取。只有从未派发且没有任何调用预留的任务立即收敛到 CANCELLED；派发结果有歧义或存在未决调用时保持 CANCEL_REQUESTED，等待 P4 恢复链结算。
- P3 `AdaptationContextRepository`/`AdaptationContextService` 已实现两段式封存：从数据库解析同版目录的目标/邻章/真实边界，锁外经 `ShelfChapterContentReader` 读取完整正文，最后重新锁定范围，复核目录身份、全部实际摘要、执行 fence、取消/删除 epoch 和截止时间；一次事务写入 4/5 个片段与封存头。只有封存完全一致的重放能返回原快照，不覆盖、不修补半份快照。
- P3 封存事务显式使用 READ_COMMITTED，并拒绝加入未知隔离级别的外层事务，避免 MySQL 在等待行锁后继续使用加锁前一致性读快照。该隔离意图已落入代码；仍需 P7 在真实 MySQL 精确版本上验证锁行为，H2 测试不能替代。
- P3 目标原文不截断；前章尾部/后章头部各最多 4000 个 Unicode code points，截断不切开代理对。优化另存通过校验的父版本正文作为 BASE_INPUT；重新改编不包含父输出。派生版本必须与根原文、邻章摘录和目录窗口一致；邻章约束发生变化时拒绝沿旧根继续派生，要求从当前章节 INITIAL 重新开始。
- P3 已封存上下文读取重新计算全部片段摘要、码点长度和 manifest，并验证归属身份。封存后书源失效或目录改变不会篡改既有快照。最初 P3 测试用夹具建立 claim 前置状态；最新 P4 已增加受权 HTTP 领取及准备入口和真实 claim → seal 测试，`AdaptationExecutionFence` 仍只由内部验证后构造，不能从 HTTP body 绑定。
- P3 新增只读 `/api/v1/reader-state/chapter-adaptations/{id}/comparison`：只比较冻结 TARGET_ORIGINAL 与通过校验的采用正文，不重取当前来源，不返回邻章。段落差异按原始换行保留，最多 500 段、250000 个 LCS cells、40ms 计算预算；超限返回无重复 hunks 的双栏视图，最终序列化 JSON 上限 2 MiB。
- P4 新增 Reader V33：持久派发中止原因及到期扫描索引；新增 `AdaptationDispatchRepository`/worker，复用 adaptation 行的派发队列、epoch、租约和下次执行时间。短事务按 shelf → binding → chapter → adaptation 锁定，锁外调用 Scheduler，再按领取身份回写。租约统一到 TIMESTAMP(6) 微秒精度；接管、失联重试不创建新业务版本或新调度键。
- P4 `SUBMIT` 最多 5 次（可配置）；已绑定任务转为 `OBSERVE`，取消/截止/删除/提交耗尽转为 `CANCEL`。提交响应丢失只重试同一 adaptation ID。耗尽先写 `dispatch_abort_error_code`，context 守卫立即拒绝继续取文；必须拿到持久取消屏障、Scheduler 终结且没有 REGISTERED/SEND_STARTED attempt 才收敛 FAILED/CANCELLED。当前授权执行和无授权独立后台扫描均已具备超期结算，详见设计 11.11；真实多服务恢复联合门禁仍须完成。
- P4 新增 Scheduler V145 `reader_adaptation_dispatch_guard`。Reader 专用提交、查询、取消接口只接受路径中的 adaptation ID；生成的任务参数只有 `adaptationId`，固定 `reader_adapt_novel_chapter`、业务类型、优先级及 `reader.adaptation=enabled` 标签。要求 `task.security.required=true`、独立 Reader business-client 配置和过滤链认证身份；关闭认证或共享 legacy token 不是功能降级路径。
- P4 取消与创建锁同一持久屏障，无任务时也能拒绝迟到创建。查询用单条 LEFT JOIN 同时读取屏障和任务状态，避免把旧的“无任务”和新的“已取消”拼接成错误证明。通用 Scheduler 创建/详情/步骤结果/取消路由同样受保护，包括任务名/保留键大小写变体；非改编任务沿用原调用方式。
- P4 私网 Scheduler 客户端禁用重定向、禁止事务内调用、无请求正文，响应流上限 64 KiB、完整请求超时默认 8 秒；严格拒绝重复/多余字段、非规范 UUID、未知状态、压缩或错误媒体类型。异常链、凭据、正文与地址不写入日志。正常同键幂等重放不依赖任务定义仍启用；创建唯一键竞争后也重新核对输入。
- P4 单独配置 `reader.adaptation-dispatch.enabled=false`，新建开关与恢复开关分开。默认租约 30 秒、观察间隔 5 秒、轮询 1500ms；暂停新建不能同时关闭已有任务恢复。当前没有发布改编任务定义或 immutable 执行包，Scheduler 测试使用空步骤定义夹具；不要通过开启此开关把夹具当作可用的模型执行链。
- 修正设计 12.3：旧领取回写失败不一律取消。若新领取已正常接管同一任务，旧回复只能忽略；确实停止的业务才补偿取消。Scheduler SUCCEEDED 也不能把未通过 Reader 采用事务的版本标为 COMPLETED。
- P3 补强尚未发布的 V32 可见候选 CHECK：输出码点长度必须非空且为 1–120000，避免 SQL CHECK 的 NULL 三值逻辑放过不完整候选。
- P4 新增未发布 Scheduler V146 `task_execution_authorization`：执行/task/fence 复合外键、三类 audience/resource/package CHECK、当前及最多 5 秒在途 previous generation、原节点 instance/叶证书指纹、租约/断言到期与撤销状态。数据库只保存 `jti` 的 SHA-256，不保存 token 或原始 jti。
- P4 Scheduler 领取事务先核对原生 TLS 客户端证书、节点名称到 URI SAN 的可信配置映射和不可变包契约，再签发并写授权行；签发失败使领取一同回滚。heartbeat 递增 generation、重新签发并与租约更新同事务提交。取消、完成、过期恢复和新 fence 撤销旧执行，取消后的 heartbeat 不延长租约。授权与完成/恢复按 node → task → execution 锁定，避免与领取发生反向锁顺序。
- P4 `WorkloadAssertionSigner` 固定 JOSE `alg=Ed25519`、`typ=mytools-workload+jwt`，字段严格白名单；owner/provider/binding/bucket 不进入 claims。owner-only、非符号链接、有限大小的文件密钥环保留 1 个当前签发 key 和最多 2 个旧公钥；JWKS 只发布公钥。`task.workload-authorization.enabled` 默认 false，Executor 映射格式为 `nodeName: spiffe://...`，Reader identity 是单独 allowlist。
- P4 Scheduler 新增仅 Reader 原生 mTLS 身份可调用的 `/api/internal/v1/task-execution-authorizations/introspect` 与 `/jwks`。introspection 不缓存，从授权、任务、执行、最新 fence、节点 instance 和绝对 deadline 联查，已取消、过期、撤销、换证书/节点实例均不能继续 active；转发证书 Header 无效。当前 Scheduler 测试通过原生证书属性夹具验证服务逻辑，不是 Scheduler Tomcat 实际 TLS connector 的集成证据。
- P4 Scheduler 的 claim/heartbeat 通过专用 HTTP wrapper 输出授权；持久化 DTO 忽略授权字段。普通任务 HTTP wrapper 不输出新增空字段，保持旧 Executor 协议兼容。Executor DTO 使用只反序列化字段，诊断 `toString()` 遮蔽授权、lease token 和参数；真实 SQLite claim/step/completion WAL 夹具确认没有授权字段或 token。
- P4 Executor 新增默认关闭的 `executor.workload-tls.*`：从 owner-only PKCS12、独立密码文件及信任库加载宿主客户端身份，检查单一 URI SAN 和 clientAuth EKU、启用服务端证书/主机名验证、禁用重定向与系统代理；启用时 Scheduler 必须是无凭据/查询/片段的 HTTPS 根地址。密码字节及可变数组使用后清零，错误不携带路径、证书或异常链。没有配置 mTLS 的旧客户端拒绝领取这三类受保护任务，不能降级成普通任务执行。
- P4 Executor `WorkloadAuthorizationRegistry` 已接入领取、续期、终态、取消及本地失联停止。宿主基于可信 Scheduler TLS 传输核对 task/resource/package/fence/参数摘要、证书绑定及期限，当前 token 仅保存在内存；续期不能覆盖更新的 generation，关闭后迟到 heartbeat 不重新登记；短 token 过期后不能取出，但有效租约的后续续期仍能恢复内存授权。它不是 Reader 验签/在线撤销的替代品，当前 token 夹具的签名只是占位符。
- P4 旧 claim 响应丢失时继续使用同一 key；只有 Scheduler 明确返回 `409 EXECUTION_LEASE_LOST` 才清除旧领取 key，避免任务已被恢复后执行器仍永久重试过期领取。未知失败和 503 不改 key。领取/续期的 JSON 解析诊断不包含响应正文；非白名单错误 code 回退 HTTP 分类。
- P4 Reader 新增 `reader.workload-authorization.*`，默认关闭。Reader 使用独立 PKCS12 客户端证书向固定 Scheduler HTTPS 地址请求 JWKS 与 introspection；没有把 Executor 证书、Provider key 或原始 assertion 转发给 Scheduler。信任库、证书身份、文件权限、禁用重定向/系统代理与完整请求时间/字节上限均由独立路径控制，不影响既有 Reader 服务令牌接口。
- P4 `ReaderWorkloadAuthorizer` 已实现原生 TLS 客户端证书唯一 SAN/clientAuth/有效期校验、`cnf` 绑定、严格十五字段 claims、固定 Ed25519 验签、三类 resource/package/audience 隔离、路径资源参数 SHA 和 execution 匹配。只有验签成功后才在线查询 jti；active 结论不缓存，权威服务异常映射 `503 READER_059`，错误签名/范围/撤销映射 `409 READER_044`，不保留原始解析异常链。
- P4 `HttpReaderWorkloadAuthority` 只缓存完整验证的公钥集（默认 300 秒，最多 3 个 key）；未知 kid 最多每 5 秒触发一次合并刷新。过期缓存不降级使用，未知 kid 冷却窗口拒绝执行；公钥轮换需要在签发前预发布新公钥并保留旧公钥到旧断言到期。JWKS 响应限制 8 KiB，内省响应限制 1 KiB；重复字段、尾随 JSON、私钥字段、错误算法、压缩、重定向、异常媒体类型及超时全部失败关闭。
- P4 Reader 新增 `/api/internal/v1/chapter-adaptations/{id}/executions/{executionId}/claim`、`/prepare-context` 和 `/status`。路径 UUID 必须规范，三条接口均先授权且不接受 owner、正文或查询参数；成功与错误均 `no-store, private`。只有 claim 能写当前 execution/fence；未回绑返回 `READER_050`，低 fence/同 fence 异 execution/异 task 拒绝，同执行重放不制造新进度。
- P4 `AdaptationExecutionRepository` 在独立 READ_COMMITTED 短事务内，先由已认证资源反查 owner，再按 shelf → binding → chapter → adaptation 加锁；复核取消、删除 epoch、派发中止和绝对期限。SEALED 快照在领取事务内完整验真后才推进 ANALYZING，坏快照不能更新执行身份；BUILDING 只进入 CONTEXT_FREEZING。更高 fence 不覆盖快照或重置预算，存在 REGISTERED/SEND_STARTED 时返回 `RECOVER_ATTEMPTS`，不宣称未决调用恢复已实现。
- P4 `AdaptationExecutionService` 已把当前执行解析接到既有可信取文及一次性 context seal。准备结果返回前再次核对当前 fence、取消及期限；准备完成后再次 claim 才进入 `ANALYZING/PLAN_PENDING`。执行 DTO 分离 `state/nextAction/inputs/context`，不返回 owner、locator 或 Secret；输入/正文诊断字符串均脱敏。慢取文不能延长已获得的授权，超出授权期限会被拒绝；长耗时上下文准备与 heartbeat 轮换的联合场景仍须在 broker/长任务门禁中验证并完善。
- 当前未实现 UDS broker、子进程凭据文件隔离、周期结算恢复和完整任务入口，因此不能开启真实改编。加密 relay 已在后续批次实现。PKCS12 是可读取的文件凭据，不宣称具备硬件不可导出能力；同 UID 的脚本还需要独立文件系统/进程隔离，owner-only 权限本身不能替代它。已实现宿主 TLS 与 Reader verifier 不表示三服务真实部署 mTLS 链路已验收。
- P4 新增 Reader V34：attempt 预留元数据（原证书 cnf、命令摘要、预占毫秒、预留到期、发送许可及结算重建材料）和共享 Provider 熔断桶。未发布 V32 同步修正：REGISTERED 的结算 SHA/期限为空，SEND_STARTED 要求两者齐全；Scheduler 重试序号允许为空，因为当前已验签协议没有该字段，不能把 fence 冒充重试次数。原 task/execution/fence 始终保留，不改已有部署的迁移 checksum。
- P4 `AdaptationAttemptRepository` 复用当前执行的 READ_COMMITTED 锁顺序，预留与父行预算扣减在同事务提交。同一 providerAttemptId/命令摘要重放不再扣减，字段冲突或跨执行重用拒绝；有未决、失败或仅留档前序调用时不能新建后续调用。固定顺序 PLAN → GENERATE → CRITIC → REPAIR → CRITIC，最多 5 次、一次修复。时间上界分别为 90/180/105/180/105 秒，预占后不退款，总计至多 660 秒；按剩余预算与 Reader deadline 再裁剪，接管不会重置。
- P4 `send-started` 在同事务复核 disclosure/consent、发布登记是否启用、原证书、当前 fence、取消及 deadline，并锁定 AUTH/AVAILABILITY 两个共享 CLOSED 桶。只有首次返回 `maySend=true`，要求两秒内开始发送并遵守持久 callDeadline；相同请求重放只能取回相同结算令牌，`maySend=false`，不得再次调用模型。当前 Provider 幂等未经验证，因此没有开放额外 transmission，retry budget 仍保留但不会消耗或重置。
- P4 `AttemptSettlementSigner` 使用独立 owner-only 非符号链接密钥文件，最多三个 32 字节随机 HMAC-SHA256 key。token 为 `settle-v1.kid.nonce.mac`，MAC 绑定固定 audience 及数据库中的完整 adaptation/attempt/providerAttempt/request SHA/deployment/task/execution/fence/delete epoch/cnf/exp 范围，不是通用 JWT。数据库只保存 token SHA、key ID 和无权限的 nonce 重建材料。有效期为 callDeadline 后 120 秒，签发总长度不超过 300 秒；不续期、不换 key 重签旧回执。`reader.attempt-settlement.keyring-file` 默认为空，缺少独立 key 时发送事务失败关闭。
- P4 内部接口已增加 `POST attempts`、`POST attempts/{providerAttemptId}/send-started`、`PUT attempts/{providerAttemptId}`、`POST attempts/recover`。预留先完整验签和在线内省再读取至多 1 KiB 白名单 JSON；结算先验证原生 TLS 和 `X-Reader-Attempt-Settlement`，再读取至多 2 MiB 严格 UTF-8 JSON。无效/撤销 assertion 或内省失联只能走独立窄能力留档，不允许新调用或读取正文；当前 assertion 仍有效且原执行仍为当前时才能保存非 archived 结果。请求体不能提供 owner、发布配置或令牌。结算 token Header 必须纳入后续代理/Executor 日志脱敏配置。
- P4 终态写入以 Reader 规范化的完整载荷 SHA 幂等，重复相同结果不改写，不同结果冲突；取消、超期或换 fence 强制 `archived_only`，tombstone/epoch 变化返回 410。写入失败返回 `503 READER_061` 且不携带 SQL/参数异常链，允许使用同 token/载荷仅重试回写。保存 attempt 不自动推进业务状态或创建 validation/selection。PLAN/CRITIC 结构化对象递归排序、限制深度/大小/重复键，GENERATE/REPAIR 正文限制 120000 code points；这些只是结构校验，不等于剧情一致性或平台内容校验。
- P4 当前有效执行可以调用 recover：未发送预留到期、取消或换代后结算为 FAILED；已发送调用必须保留到结算窗口到期，之后记为 CALL_OUTCOME_UNKNOWN，不重发、不退预算。Scheduler 已撤销全部执行时，11.11 的独立扫描仍可按绝对期限结算。两种恢复均原子写入停止信号；最终状态仍等待派发取消屏障，不能将本地夹具等同于真实多服务崩溃恢复验收。
- P5 共享桶按 deployment + generation（AUTH）及 deployment + exact model（AVAILABILITY）隔离。未登记桶拒绝发送；归一鉴权失败打开认证桶，普通 403/READER_055 不打开认证桶；60 秒内三个可用性失败打开可用性桶，成功重放不会重复记失败。冷却后只记录 next_probe_at，尚未实现公版 half-open 调度和重新关闭，因此不能依赖自动恢复。发布登记仍存在单 deployment 行固定 model/generation 的限制；正式实现轮换前需改成保留不可变发布快照、同 endpoint 身份的多代登记，不能用新 deployment ID 偷偷清空原可用性桶。
- P5 `AdaptationStoryEngine` 实现 `story-constraints-v1`：从冻结原章提取数字/部分中文数字单位、书名号/标题引号专名和保守英文专名，再与模型原文证据绑定的事实、实体、事件和结尾锚点合并。PLAN 独立 schema 为 `adaptation-plan-v1`，拒绝未知字段、伪造摘要、重复事实 ID、范围外引文、不匹配码点位置、不允许的扩写类型，以及引用错误类别/范围的事件和结尾状态；意图 CONFLICT 在封存时返回 READER_033，不进入成文。
- P5 长度规则默认原章的 700–2500 千分比，可通过 `reader.story-constraints.*` 配置新版本的范围；取值在封存时固定，恢复不套用新的环境值。数字、专名集合各有 256 项上限，单标记最多 128 UTF-16 units，超限明确返回 READER_042。现有规则是有界的字面检查和启发式专名提取，不是完整中文 NER 或全书事实证明；文字改写与锚点字面保护冲突时可能需要模型保留关键短语，真实小说误报/漏检率仍待测量。
- P5 `adaptation-critic-v1` 绑定实际 candidate SHA 与已封存 constraint SHA，要求 FACTS/ENTITIES/NUMBERS/EVENT_ORDER/ENTRY_STATE/ENDING_STATE/POINT_OF_VIEW/WORLD_RULES/INTENT/SAFETY 十个唯一维度、受限 verdict、与实际候选匹配的码点证据和可解释的失败项。自相矛盾的 PASS、错误候选、缺项/重复项、未知字段和未解释失败均拒绝。确定性失败不能被 critic PASS 覆盖；第一次可修复失败进入 REPAIRING，第二次失败或 BLOCKED 进入 FAILED。
- P3/P4 `AdaptationStoryRepository` 与 service/controller 已接通 `POST constraints/progress/validations/complete/fail`。每个入口先原生 mTLS、签名和在线内省，再消费最多 2 KiB 身份命令；不接受正文、事实、最终 PASS、owner 或模型配置。constraints 只读取已成功 PLAN；progress 仅开放 GENERATING/REPAIRING → VALIDATING 的固定边；validation 由 Reader 对已存候选及对应 critic 重新聚合，原子进入 REPAIRING/PERSISTING/FAILED；complete 再次验真全部证据后同事务写 selection 与 COMPLETED。三处插入后父状态更新失败均回滚，不以第二次 Provider 调用补偿写入失败。
- P4 采用路径复核原 task、Provider 发布配置、原 chapter delete epoch、fence 上界、非 archived 完整终态 SHA、输出/计划 SHA、封存规则重算结果及 validation 全部内容。当前新执行可复用此前在有效授权下已完成且非 archived 的证据，旧执行不能回写；这避免把恢复定义成重发已经完成的模型调用。取消、删除、超期和正文篡改均拒绝采用。当前还缺少 Executor 恢复时按需取得已完成 attempt/规则/校验的完整只读工作状态接口。
- P3 新增 V35 `validation.content_policy_outcome`，默认 UNKNOWN。公共正文查询现在必须存在 PASS 内容评审且不存在 BLOCKED/UNKNOWN；仅 `viewable_candidate=true`、甚至已有 selection，都不能绕过。剧情失败且内容评审 PASS 的候选通过不可变 validation 投影为 REJECTED_BY_CONSTRAINTS，不回写候选终态。原只读 SQL 夹具已补明确评审状态，不把默认 UNKNOWN 自动改为通过。
- 当前 `content_policy_outcome` 来自受限 critic 的 SAFETY 维度，不是独立平台审核的准确率/合规证明；尚未接入可信内容审核适配器，也未实现被阻断原文的独立加密隔离/清除，原候选仍可能保存在内部 attempt 表。PLAN/CRITIC 的独立语义 schema 目前在约束封存/校验时应用，前序通用账本仍可先保存结构合法但不符合该独立 schema 的 JSON；P5 还需把独立 schema 前移到输出归一与落库入口，避免保存未知模型推理字段。真实改编开关继续关闭。

- P5 新增 Executor 宿主 `NovelProviderClient/Models/Credential/ResponseParser/Json`。生产端点固定为指定 HTTPS 地址，部署与 Reader 的 model/generation/ID 精确匹配；凭据只从 owner-only 实际文件读入，拒绝符号链接及非法 Header/prefix/内容。暂不装配 Spring 或 TaskExecutionWorker，不打开真实流量。每客户端并发 2，构造不访问网络；请求字节上限和 max_tokens 不是已验证模型/tokenizer 容量。
- P5 `prepare` 只接收版本化 Prepared Prompt，生成精确请求 SHA；`execute` 绑定同一次 Reader Permit、两秒 sendBy、阶段 deadline 和当前授权并单次消费 Ticket，BodyPublisher 拒绝第二次订阅。HTTP 使用 identity，无代理/重定向/工具，读取有 1 MiB 复制前上限及首字节/整体 deadline；发送后取消或不确定失败不重发。Permit 是宿主 DTO，UDS broker 仍需把它绑定真实 Reader 回复，不能接受子进程自报许可。
- P5 响应支持受限 JSON 与可选 SSE（最多 2048 帧、明确 stop/DONE），拒绝多个 choice、截断、工具调用、异常编码和尾随内容。错误正文和 reasoning 字段不保存；成功结果回显当前凭据也会拦截。401 与普通 403 分开分类，408/5xx/读失败按结果未知。JDK Header 解析后的 8 KiB 检查不是分配前限制，底层 Header/egress 防护仍属部署门禁。
- P5 `NovelAdaptationPrompts` 实现 novel-adaptation-v1 的四阶段合同，优化底稿单独存在，重建不携带旧候选；repair 只接受绑定候选/规则的第一轮可修复且内容 PASS 报告。公开客户端返回前强制通过 `NovelStageOutput` 嵌套字段白名单，避免把未知模型推理字段带到 Reader。Reader 账本自身的独立 schema 前置门禁和实际语义复核不能被 Executor 结构门禁替代。
- 前一批完成可被后续宿主 broker 调用的模型端组件，没有装配任务包/结算 relay/恢复编排，也没有调用目标 Provider 或保存、使用用户提供的 Key。Reader HTTP client 已在后续批次补上，模型端细节见设计 11.3。
- P4 新增 `GET /workflow`：复用当前执行 READ_COMMITTED 锁顺序，一次读取受限状态、最多五个有序调用、已封存约束及最多两轮校验；非留档成功载荷复算完整摘要，约束/validation 重新恢复和聚合。读取不增加调用、不扣预算；更高 fence 先 claim 后可以复用旧合法证据，旧执行不能读。取消、终态、中止或 deadline 后不再返回候选/规则/报告；删除和 epoch 变化直接拒绝。
- P4 Executor 新增 `ReaderAdaptationClient/ReaderResponseBody/ReaderAdaptationException`，按已领取 adaptation/execution/fence 固定 HTTPS 路由，每次普通请求取内存中的当前 assertion，等待和返回前再检查撤销；不接受任意路径、owner、模型配置或令牌。预留由模型 Ticket 的阶段/摘要产生，Reader 回复构造的 SendCapability 只允许原调用结算，相同载荷可重放，冲突载荷本地拒绝；撤销后仅该 PUT 可以无 assertion 留档。
- Reader 客户端响应检查资源、fence、阶段和预算，使用复制前 4 MiB 成功/8 KiB 错误上限、严格 UTF-8/JSON、identity 和无重定向/自动重试；普通请求最多 10 秒，prepare-context 最多 90 秒，另受 task deadline 限制。长取文与 Reader 单个短 assertion 的期限仍需完整 broker 续期验证。最初只有内存 capability，后续已补 11.6 的加密 relay；客户端细节见设计 11.4。
- P5 新增 `NovelAdaptationWorkflow.advance()`，连接现有两个固定客户端及真实 Prompt。每次普通推进重读 Reader workflow，根据实际持久状态执行 PLAN → GENERATE → CRITIC → 可选 REPAIR → CRITIC → Reader 采用；已保存结果只引用 ID，不重复生成。核验调用预算/顺序/身份、封存角色、约束摘要及匹配 review；取消、冲突意图和阻断结果不进入后续模型阶段。
- P4 编排保留原 Ticket、预留、许可及归一 Terminal；预留丢回复复用同 ID，发送许可重放无发送权时结算 UNKNOWN，结算失败只重放同一载荷，撤销后仍只能窄能力回写；过期能力不再 PUT。生产构造现已强制要求 relay，发送前同步保存能力、Reader 结算前同步保存终态；关闭 workflow 释放未确认记录给恢复消费者。任务入口与宿主退避循环后续已由 11.7 补上，编排细节见设计 11.5。
- P4 新增 `ReaderSettlementRelay`：0700 根目录、0600 SQLite/进程锁、独立 AES-256 密钥、随机 nonce/GCM、绑定 Provider attempt ID/期限的 AAD、FULL 同步、16 个有界槽，不进入通用 report WAL。终态不可变，写故障不越过边界；硬 JVM 退出后可恢复已提交结果，只有能力时只落库 UNKNOWN。确认/到期后逻辑删除，不宣称物理介质或备份即时擦除；首版单密钥要求排空后轮换。
- P4 新增 `ReaderSettlementClient/recoverOne()`：匹配配置 Reader origin 和原证书后，只能无普通 assertion 地 PUT 原调用；活动 Lease 排除后台，HTTP 锁外一次一条，失败保留退避，期限不可延长。本机真实 mTLS + 加密日志验证清除 registry/重启后恢复，不是完整 Reader MAC/三服务端到端。后续 11.7 已补 Spring 生命周期、周期恢复和任务入口，存储/传输细节见设计 11.6。
- P4/P5 新增 Host/Run/Broker/Sandbox 并接入 Worker：固定根任务/单步/参数白名单在写通用 context/lease 之前分流；脚本只获得一次性 UDS，不获得正文和凭据。运行循环复用同一 workflow、退避且响应取消/deadline；恢复开关独立于新建，关闭顺序保留未结算记录。专属节点拒绝通用任务，Linux 最小 rootfs 路径强制 kernel probe；当前 macOS 未绕过这些门禁。
- P5 新增脚本包 `reader_adapt_novel_chapter/1.0.0` 与默认关闭的 Scheduler V147：固定 900 秒、仅一试、空步骤输出，只接收 adaptationId。真实组装器完成本地 128 包发布索引，并由 Java 校验器验证新包及篡改拒绝；没有部署至生产或修改既有发布。细节及未通过的 Linux/联合门禁见设计 11.7。
- P6 新增 ChapterAdaptation Gateway 配置/模型/控制器/客户端/响应过滤器：主体来自现有登录验证及 owner allowlist，固定单书/单章/版本路径与字段，32 KiB 输入、严格 UTF-8/JSON、2 MiB 成功/8 KiB 错误上限；认证失败亦禁止缓存。读取与创建默认关闭、独立控制；关闭新建不影响已开启的历史和取消。
- P6 新增 App API/模型/纯策略/回复归一/ArkUI Panel，并在 Index 的书架详情目录旁与阅读器目录头部接入图标。原目录没有 server chapterId，因此使用独立权威改编目录，按已同步本人书架的精确 metadata.bookId 解析 shelf UUID；绝不按标题或 index 猜测章节。必填意图、202 收据、轮询、历史分页、旧版本查看、候选标记及右上角派生按钮已接线，改编正文不写回原阅读数据。
- P6 新增操作 revision 与取消信号，切章/退后台/关闭/登出使迟到回复失效；未确认提交在页面内存复用同一输入和 key，网络错误不伪装为后台失败。初批 STALE/UNKNOWN 限制已由下一条短期来源核验补上；说明文字仍不是第三方处理同意收据。细节见设计 11.8。
- P3/P6 新增 V36、SourceCheck Repository/Service/独立 Worker 与 Gateway/App `source-check` GET/POST。空正文 POST 202 只排队核验，不建业务版本；全局 32、每 owner 2、队列 900 秒、默认领取 210 秒、一台 Reader 单独一线程且无内存队列。重取目标/邻章，复用 context seal 规则，重新比对快照和最新摘要后投影最长 60 秒 CURRENT；过期、删除、损坏和旧领取不能继续使用。真正派生后台仍独立取文冻结。App 使用原章核验 SHA/修订提交，过期保留输入且可再次核验；不确定提交不换 key。实际来源/设备门禁仍待，详见设计 11.9。
- P3/P6 新增 V37 授权修订及只追加事件、业务版本 consent_revision；明确同意、撤销全部告知、关闭能力后的状态读取/撤销经账户路由接入。创建和发送共用已审核 schema/摘要/合约身份及当前授权修订门禁，撤销再同意不能复活旧任务发送资格；无审计的旧手写 grant 不再通过。App 增加真实告知展示、两个默认不勾选声明、确认与撤销，状态不确定时需要刷新及再次明确操作。未发布真实告知、未自动同意，真实开关不变；详见设计 11.10，无执行器恢复后续进展见 11.11。
- P4 新增独立恢复 Worker/调度开关及 V38：每批 16 个到期身份、逐范围短事务、稳定游标轮转、SKIP LOCKED 和零内存队列；授权失效、删除或换代仍可结算空载荷。终态归档、共享熔断及父行停止信号原子提交，保留发送结算窗口与取消屏障；不重发、不退预算、不直接伪造业务终态。当前执行器 recover 同时补齐原子停止信号。真实恢复开关默认关闭，未部署；详见设计 11.11。

### 已执行验证

| 命令/检查 | 当前结果 |
|---|---|
| `mvn -f service/pom.xml -pl reader-service -am compile -Dstyle.color=never` | 通过 |
| `mvn -f service/pom.xml -pl reader-service -am test -Dstyle.color=never` | 前一批 Reader 189 项通过，scheduler-client 2 项通过；最新完整证据见下方包含测试的 package 命令 |
| `mvn -f service/pom.xml -pl reader-service -am package -DskipTests -Dstyle.color=never` | 通过；测试证据来自上一条独立完整回归 |
| `mvn -f service/pom.xml -pl reader-service -am package -Dstyle.color=never` | 2026-09-10 20:40 完整回归及打包通过：Reader 360 项，scheduler-client 2 项，均无失败、错误或跳过；本批新增 17 项后台恢复测试 |
| 独立未决恢复及取消协作 | 新增 11 项真实仓储/派发协作测试与 6 项 Worker 测试通过：精确期限、撤销后结算、发送窗口、取消屏障、旧派发回复、删除/epoch、窄结算先提交、多扫描器、SKIP LOCKED、父行失败全回滚、逐范围故障隔离及公平轮转。17 个旧终态父行的批次上限测试仅以 SQL 插入遗留未决调用，不弱化正常入口同章互斥；不是实际 Provider 或真实 MySQL 证据 |
| 全量 Flyway V1–V38 + 绑定迁移约束夹具 | H2 通过，涵盖 source、remote projection、改编相关表、owner/版本、RESTRICT、CHECK、派发中止、调用预留/熔断、UNKNOWN 默认的内容评审、短期来源核验、授权修订/审计及到期扫描；不是 MySQL 验证 |
| 本机 HTTP 运行时夹具 | 7 项通过；没有使用真实小说、书源或 Provider |
| source 准备事务集成夹具 | 11 项通过：此前 10 项及真实 Reader 目录准备/可信取文 → 版本创建 → context seal 集成夹具；仅外部书源响应及调度 claim 前置状态由夹具提供 |
| locator 安全及 Reader HTTP 契约 | 26 + 4 项通过：GCM 关联身份、轮换、文件权限、游标签名、地址分类、严格请求字段和错误码 |
| 改编事务与私网 HTTP 契约 | 已通过 23 + 7 项；并发收据/同章互斥、业务谱系、租户隔离、跨章节外键、删除收据重放、签名历史分页、正文隔离、未决调用取消及请求白名单；成功候选由 SQL 夹具建立，不代表模型链已实现 |
| context seal 与冻结比较定向回归 | 已通过 context 24 项、比较算法 6 项、比较 HTTP 1 项；新增持久派发中止后不再取文；包括封存头写入失败后全部片段回滚；随机差异重建用固定种子覆盖 100 组重复段落序列 |
| Reader 派发事务与本机 Scheduler HTTP 夹具 | 12 + 7 项通过：领取互斥、失联重试、过期接管、旧回复补偿判断、取消先于提交、提交耗尽、截止、删除、未决调用等待、微秒租约、媒体/字节/超时/凭据边界 |
| Scheduler 定向回归及 package | 最新 85 项通过（2026-09-10 13:08）：`TaskInstanceServiceTest,SchedulerRestartRecoveryTest,TaskRuntimeMonitorTest,ReaderAdaptationDispatchServiceTest,InternalTokenFilterTest,TaskExecutionAuthorizationServiceTest,WorkloadAssertionSignerTest`，额外指定 `-Dspring.flyway.target=140`；定向夹具另执行真实 V145/V146。不是 Scheduler 全量迁移/全量回归证据 |
| Scheduler 授权与密码学夹具 | 12 + 6 项通过：真实 Ed25519 签名/验签、公钥轮换、文件权限、领取签发失败回滚、generation/5 秒 previous 窗口、取消/完成撤销、失租/新 fence/换节点实例、无 TLS 拒绝与受控时钟推进 900 秒边界；没有真实 Provider 调用 |
| Executor 定向回归 | 62 项通过（2026-09-10 13:25）：`ExecutorWorkloadTlsTest,WorkloadAuthorizationRegistryTest,SchedulerNodeClientTest,ExecutionReportJournalTest,TaskExecutionWorkerTest`；本批新增 16 项 |
| `mvn -f service/task-executor-service/pom.xml clean package -Dstyle.color=never` | 最新干净构建、完整回归及打包通过（2026-09-10 18:52）：共 222 项，218 项通过、4 项由已有 Linux 条件在 macOS 跳过，无失败或错误；本批新增 30 项全部通过。错误码统一归入 common/ErrorCode.java。跳过范围为进程组、资源上限、cgroup v2 和网络隔离，必须在 Linux 门禁补验；没有新增编译警告 |
| Executor 本机真实 TLS 夹具 | 8 项通过：随机临时证书的握手、缺失/不受信客户端证书、不受信服务端证书、主机名不符、错误 SAN/EKU、文件权限/符号链接、禁止重定向、真实 HTTPS claim/heartbeat 传输、旧 key 恢复及脱敏。服务器是本机 HTTPS 协议夹具，不是完整 Scheduler 应用 |
| Executor 授权/持久化夹具 | 8 项通过：HTTP 输入可接收但 JSON/SQLite claim 与 report 不保存 token；迟到 heartbeat、取消、过期、范围错误和 900 秒受控时钟续期；不是 900 秒墙钟压测或 Reader 授权验收 |
| Reader 验签/在线授权/claim 定向回归 | 46 项通过：`ReaderWorkloadAuthorizerTest` 8 项、`HttpReaderWorkloadAuthorityTest` 6 项、`AdaptationContextRepositoryTest` 32 项（新增真实 claim/HTTP/封存/接管 8 项）。没有把既有 claim SQL 夹具当作新入口实现证据 |
| Reader 密码学及 HTTPS 夹具 | 本地真实 Ed25519 签名/验签、三类资源隔离、错误证书属性/SAN/EKU/算法/期限/字段拒绝、每请求内省；随机临时 PKCS12 的实际 Reader 客户端 TLS 握手、缺少/不受信客户端证书、主机名错误、JWKS 轮换/冷却/过期、响应上限及超时。入站 Reader 控制器仍用原生证书属性夹具，出站 Scheduler 为本机 HTTPS 协议夹具；不是完整三服务 mTLS 端到端证据 |
| Reader 真实 claim → context seal | 通过：首次领取返回 PREPARE_CONTEXT，取文锁外执行，封存后再领取进入 ANALYZE；相同领取无新进度，换 fence 保持快照/预算，取消后不继续取文、旧执行不能访问、写入故障回滚、坏封存不推进、未决调用要求恢复、HTTP 拒绝 owner/正文且 503 时不取文 |
| Reader attempt 事务与 HTTP | 新增 19 项通过（与原 context 32 项合计 51）：并发幂等、预算原子回滚、发送前同意与熔断、只授予一次发送权、仅留档/取消/接管、同载荷回写、过期与删除、REGISTERED 回收、SEND_STARTED 结果未知、真实 Ed25519 + H2 的内部 HTTP 路径与窄能力隔离。五阶段预算使用真实账本，但约束/validation/阶段转换仍是 SQL 夹具，不是模型执行证据 |
| Reader 结算 MAC 与归一载荷 | 4 + 4 项通过：随机专用密钥、签名范围/证书绑定、旧 key 重建和过期、文件权限/符号链接/重复键拒绝；结构化字段排序摘要、Unicode、大小/深度/尾随 JSON、失败正文隔离及元数据摘要变化。没有使用真实 Provider 或用户密钥 |
| Reader 约束与采用事务/HTTP | 新增 13 项通过（与旧 context/attempt 合计 64）：真实 claim/context/attempt → constraints → progress → validation → selection；不再用 SQL 伪造这些状态。覆盖首次成功、真实采用结果作为优化底稿/重建仍用原章、一次修复、二次失败、内容阻断、意图冲突、三处状态写入失败全回滚、取消/旧 fence/篡改拒绝、新 fence 复用已完成证据、内部 HTTP 白名单/窄令牌隔离。模型响应是固定夹具，非真实生成效果验收 |
| Reader 规则边界与公共评审门禁 | 新增 7 + 1 项通过：原文码点证据、数字/专名/事件/结尾、中文数字和补充平面字符、配置变化仍按封存范围、姓名前缀、超长数字和错误结尾事实、critic 绑定/缺项/自相矛盾/未知安全判定；公共输出在 UNKNOWN/BLOCKED 时被隐藏，即使已有 selection |
| Executor 模型 HTTP/解析/Prompt/阶段归一 | 新增 15 + 14 + 5 + 5 项通过：真实本机 HTTP 请求摘要/发布身份、单次发送、无重定向/重试、错误分类/脱敏、密钥回显、首字节/完整读取超时、撤销、响应字节/编码/媒体限制、BodyPublisher 二次订阅拒绝及凭据挂载；严格 JSON/SSE/Unicode、三种业务输入、修复报告绑定、阶段嵌套白名单。HTTP 返回固定文本，不是实际模型效果，未接 Reader 账本或三服务端到端链路 |
| Reader 恢复视图 | 新增 6 项通过，原 context/attempt/story 64 项与新测试合计 70 项定向通过；覆盖无副作用读取、新 fence 复用、终态/取消/安全失败不返回正文、未决/仅留档元数据、候选/报告篡改拒绝、原生证书属性和真实 Ed25519 的 HTTP 授权/在线不可用/无正文白名单。模型输出仍为固定夹具 |
| Executor Reader 客户端 | 新增 7 项通过：真实本机 mTLS、心跳后换 assertion、固定资源/路由、预留→发送许可→同载荷结算、撤销后省略 assertion 仅留档、不同载荷冲突、阶段命令、无重试/重定向、错误脱敏、严格响应/上限及在途撤销。服务端是 HTTPS 夹具，不是完整 Reader；JWT 签名与 settlement MAC 在该传输夹具中为占位材料，真正的 Reader 验证由独立测试覆盖，不能合称端到端证明 |
| Executor 四阶段编排 | 新增 16 项通过：3 次调用成功、5 次调用含一次修复、三种业务输入、新实例复用已结算计划/候选、预留/发送许可/结算/采用回复丢失、撤销后原载荷回写、未知调用只回收、能力过期、冲突意图和二次阻断、上下文/预算/约束摘要/review 错配、并发推进与异常脱敏。Reader/Provider 是 mock 协作夹具，Prompt 为真实实现；尚无持久 relay 或完整三服务端到端证据 |
| Executor 加密结算恢复 | 新增 13 项真实 SQLite/加密/文件权限/容量/并发/故障测试、3 项本机 mTLS 恢复传输及 3 项编排持久顺序测试通过。包括独立子进程提交后 Runtime.halt 硬退出再恢复、错误密钥/AAD/密文篡改失败关闭、活动 Lease 不被后台抢占、恢复仅窄 PUT。原编排测试当时的内存局限已由本批补强；仍不是物理断电、实际 Reader MAC 或三服务端到端证明 |
| Executor 宿主入口与包 | 新增 Run 8、真实本机 UDS 6、Host 默认配置/契约 7、Worker 分流 8、真实组装器/发布摘要 1 项通过；Broker 异常先记录结果再断连，保护单次调用，Host 未在 macOS 绕过 Linux 检查构造可生成实例。分流使用 mock Host，不是隔离执行端到端 |
| `python3 -m unittest discover -s service/reader-service/packages/reader_adapt_novel_chapter/1.0.0/tests -v` | 5 项通过；本机临时 UDS 验证一次请求、固定结果、异常/重复字段/大小/尾随内容拒绝，不访问 Provider |
| Scheduler 新包种子/派发/授权定向 package | 31 项通过（2026-09-10 18:51）：`ReaderAdaptationTaskDefinitionTest,ReaderAdaptationDispatchServiceTest,TaskExecutionAuthorizationServiceTest,WorkloadAssertionSignerTest`，新增 4 项；`-Dspring.flyway.target=140`，定向夹具单独执行 V145–V147。默认集群/定义关闭，不能把此次打包当作 MySQL 全量迁移或生产发布 |
| 真实任务包本地组装 | 128 包索引成功（全目录摘要 `30f4fc2668cf020a0c4a75674ca1ae8499adb33a0f585f7f4c5cf0687c06579f`，包含其他工作区包，不是新包单独摘要）；新包为 manifest/脚本/schema，tests/cache 排除。Java 回归再独立组装并验证，不依赖该临时目录 |
| `mvn -f service/mytools-gateway/pom.xml clean package -Dstyle.color=never` | 2026-09-10 19:24 通过：113 项、0 失败/错误/跳过。新增 9 项覆盖真实过滤器/控制器/客户端与 mock Reader 传输，包含登录/灰度/owner 注入、202、历史/派生/取消、输入与响应边界、错误脱敏、no-store；不是完整 Reader 服务端联合证明 |
| `mvn -f service/mytools-gateway/pom.xml package -Dstyle.color=never` | 2026-09-10 20:14 通过：118 项、0 失败/错误/跳过；本批新增 3 项账户授权 GET/POST/DELETE、始终认证、关闭后撤销、字段与修订冲突契约 |
| Reader 来源核验队列与 Worker | 新增 12 + 2 项通过：同键合并、单次领取、锁外取文、目标/邻章变化、不可用、删除、过期/旧领取、微秒精度、缓存后续变化、损坏证据、三种谱系及真实 Reader HTTP；Worker 不阻塞 tick、不积压内存任务、关闭拒绝新任务。正文和已采用候选由固定夹具提供，不是实际站点/模型验收 |
| Reader 版本化明确同意 | 新增 10 项仓储/Controller 与 1 项 attempt 发送门禁通过：只读不授权、精确版本/摘要/两个确认、旧修订冲突、撤销先于首次同意、再次同意不解禁旧任务、跨用户、发布变化、旧手写同意拒绝、并发、审计写失败全回滚；都是本地虚构告知，没有真实留存承诺 |
| `bash app/scripts/test-chapter-adaptation-policy.sh` | 最新 16 项通过：权威身份、意图、请求/回复、来源收据、授权两项确认与创建门禁、操作 revision 与不确定提交；页面接线检查仍为静态证据，不能代替设备异步场景验收。因新增创建同意检查而更新原来源接线断言的精确顺序，未移除原来源守卫 |
| App 既有相关脚本 | authorized-api-policy、reader-account-scope-policy、reader-shelf-isolation-policy、reader-cross-chapter-policy、reader-source-flow-regression-policy、shelf-sync-response-normalizer 六个脚本全部通过 |
| DevEco `assembleHap --no-daemon` | 2026-09-10 20:12 构建成功，包含授权告知/勾选/撤销页面、来源核验的 ArkTS 编译、资源、打包及现有本地签名；未安装设备。此前 pencil 符号已更换为 ai_edit。Gateway 是独立 Maven 工程 |
| `git diff --check` | 通过 |

首次完整回归在沙箱中被 Java agent 挂载和本机端口限制阻止；获得工具许可后在本机执行同一回归成功，没有修改 Mockito 或已有业务测试规避问题。现有 Flyway/H2 版本兼容提示尚在；未升级项目依赖。Docker daemon 未运行，MySQL 精确版本验证未执行。

本批 Executor 回归仍有既有 JDK 动态 agent/CDS 提示，以及故障注入夹具预期的日志；没有把它们描述为编译成功之外的生产故障，也没有用禁用测试来隐藏它们。真实 TLS 测试只在 JUnit 临时目录随机生成证书及密码，没有复用用户提供的 Provider key。

本批首次 Scheduler 定向测试在无关 V141 下载任务种子更新处失败：H2 不支持 MySQL `JSON_SET`。没有改写该迁移或用伪函数模拟它；测试固定 V1–V140，改编/授权测试单独执行真实 V145/V146，明确不覆盖 V141–V144 的 MySQL 执行。完整 Scheduler/MySQL 门禁仍待完成。

### 已明确的实现细节

- 请求指纹使用带版本的逐字段 UTF-8 长度前缀编码（`adaptation-command-framed-v1`），而非设计中的规范 JSON。当前内部命令字段顺序固定，明确区分 null、空串和分隔符；未来 API 路由解析必须形成同一命令，任何语义字段扩展必须升级编码版本并保留旧收据处理。
- 幂等键实际接受 ASCII `0x21`–`0x7e`、1–128 字节，不接受空格；保持原始大小写。公共 DTO 文档及 App 校验需同步此精确范围。
- locator 唯一键和复合 FK 额外包含 binding revision/source version，加密 AAD 同步包含这些字段。仅 source version 不足以区分两个不同书源的同号版本；重绑后不能复用旧密文。
- binding 记录 `bound_shelf_version`，确保书架元数据在准备或取文中途变化时不能把旧结果发布为当前版本。
- preparation 唯一键扩展为 `(binding_id, binding_revision, phase, preparation_round)`。单纯目录刷新不属于重新绑定，必须有新的可审计准备轮次，才能保留未变化章节 ID；业务绑定实际变更才递增 binding revision。

### 上一实施批次安排（历史快照，已部分完成）

独立账户入口、冻结比较公共路由、中文阶段及跨进程 pending-key 恢复已补齐；2026-09-11 又补齐 Reader 独立 schema 落库前检查并对齐 Executor 边界，不重复重写。下一步集中做 Reader/Gateway/App 闭环联合验证；可信审核和阻断原文隔离仍待实施。完整计划还保留 remote media 投影、删除/导出、公版探测及 Linux 隔离发布工具。MySQL、真实 Provider、服务 TLS/续期/长任务和设备运行属于仍缺证据的 P7 门禁。最新差距文档记录本批 Reader 420 项全量加定向复验、Executor 64 项定向验证及未完成边界，不把这些证据升级为真实端到端成功。

当前详情 DTO 以 `version` 包含 HistoryItem、`version.lineage` 包含 child/root/parent/trigger、`result` 包含采用正文，Gateway/App 已按该嵌套结构接入；不能直接把此前扁平示例当作当前实现。`sourceRelation` 现支持有实际核验收据的短期 CURRENT；App 还读取独立 source-check 的有效期与原章 SHA，旧 CURRENT 字段本身不能开启派生。CURRENT 不是未来书源不变的承诺或模型发送许可。

真实运行时 egress、MySQL 精确版本门禁、生产容量验证及第三方合约/留存资料核实尚未执行，不能仅修改开关来替代验证。Provider 登记和告知目前只有测试夹具，没有发布真实配置；同意 UI 已实现并构建。App 改编入口尚未通过完整设备/联合验收，也未连接真实 Provider，整体目标保持进行中。

### 2026-09-11 最新收尾状态

以上历史安排不再作为当前阻塞清单。真实 MySQL 迁移、隔离 Linux / mTLS / Provider 链路，以及初次改编、优化、重新改编、比较、历史、幂等和 Reader 重启留存已取得后端证据；三份正式采用合成样章的有限范围质量复核通过。原文编号规划及插入式增补保留原章，未放宽质量门禁。详见 `docs/runbooks/2026-09-11-chapter-adaptation-vm-acceptance.md` 的最终验收章节。

APP 的 40 项改编专项测试和模拟器签名、安装、冷启动检查已通过；尚未完成改编页面端到端操作。生产仍运行 v2 且改编开关关闭，后续修复包仅在隔离环境验证，不能说生产已经具备完整改编能力。下一步集中处理开放账户范围、真实 Provider 告知、生产兼容包与专用安全配置，以及一次完整 APP 页面验收；不重跑已通过的后端样章。生产容量、第三方留存资料及此前未完成的扩展能力仍不在现有通过证据覆盖范围内。
