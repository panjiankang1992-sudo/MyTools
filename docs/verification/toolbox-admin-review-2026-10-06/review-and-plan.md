# 管理员模式与教材录入：独立验收、差异与实施计划

日期：2026-10-06（Asia/Shanghai）。结论：**未通过设计验收，不能认定“33 项全部通过”。**

本次只做审查、构建、测试和隔离探针，未修改应用源码、未部署、未使用真实管理员凭据、未在生产新增教材或账号、未发送实体打印。已有功能可保留，但管理编辑和发布有影响数据正确性的缺陷，应先修复再交付日常教材维护。

## 1. 验收依据与边界

- 依据：[当前设计文档](/Users/pankang/mycode/MyTools/docs/design/2026-10-05-toolbox-admin-textbooks/design-and-implementation.md)。使用其中的页面、接口、状态、数据与 A01—D05 要求；不以测试数量代替这些要求。
- 当前文档 C04 已附“方案 C”范围调整：公开 API 只提供当前版本，旧版本在数据库保留。本次按该最新文档口径验收，**不把公开旧版本 404 列为缺陷**。这不取消管理员版本记录、原字节保留和回退数据完整性。
- 设计第 233—235 行要求“令牌仍有效时提前刷新”。第 237 行禁止对任意 401 盲目刷新，不能解释为所有刷新能力都排除。旧报告对 A08 的解释不成立。
- 当前本地代码是主要审查对象。线上入口资源文件名与本次本地构建相同，线上启用管理入口、公开 5 册共 2635 条；资源文件名一致并不等同于核实全部线上后端二进制。
- 线上只完成公开流程、匿名管理拦截和只读接口检查。已登录的真实生产管理员流程、MySQL 并发事务、故障恢复、回退和实体打印未在本次验证。

## 2. 本次实际执行的验证

| 检查 | 本次结果 | 证据 |
| --- | --- | --- |
| 前端 typecheck | 通过 | [日志](frontend-typecheck.log) |
| 前端 Node 测试 | 649/649 通过 | [日志](frontend-tests.log) |
| 前端组件测试 | 7/7 通过；只覆盖两个测试文件 | [日志](frontend-components.log) |
| 后端 Maven 测试 | 320/320 通过 | [日志](backend-tests-unsandboxed.log) |
| 已收录教材文件校验 | 通过；5 册 | [日志](curriculum-validation.log) |
| production 构建 | 成功，有 admin-auth 同时静态／动态导入的构建警告，未达到“零告警”要求 | [日志](frontend-build.log) |
| 线上公开 Chrome E2E | 56/56 通过；不含管理员登录后编辑发布 | [日志](production-public-e2e.log) |
| 线上只读状态 | 管理启用、匿名未登录、公开 5 册；入口资源名一致 | [记录](online-readonly-observations.json) |
| 前端反例探针 | 6 个缺陷均复现 | [源码](frontend-probes.ts)、[结果](frontend-probes.json) |
| 教材映射／校验探针 | 未核对、非法拼音、跨单元课文、重复顺序误判可发布；前插产生重复正式 ID | [源码](CurriculumReviewProbe.java)、[结果](curriculum-probe-output.txt) |
| 持久化探针 | 导入后空管理草稿、草稿筛选消失、非法草稿落库、回执失败后无法同键重试 | [源码](CurriculumPersistenceProbe.java)、[结果](curriculum-persistence-probe-output.txt) |
| 真实 HTTP 鉴权探针 | 模拟刷新后读 200／写 403；无 CSRF 的同源退出仍执行 | [源码](AdminAuthReviewProbe.java)、[结果](admin-auth-probe-output.json) |

说明：首次 Maven 运行被沙箱禁止绑定随机端口，日志保留在 backend-tests.log；之后使用隔离 H2 在允许本机监听的环境重跑通过。该环境限制不计为产品缺陷。浏览器工具超时后使用仓库现有独立 Chrome 验收脚本完成公开测试。

探针没有复刻整套产品：前端调用实际纯函数，部分直接提取当前 Vue 处理函数执行，但不等同于真实挂载页面；鉴权探针启动实际 Spring HTTP 服务，身份是注入式假实现；持久化探针使用独立内存 H2，直接实例化仓储，其结果不替代 Spring 事务代理或 MySQL 并发验证。

## 3. 已有实现应保留

- 公开工具免登录、管理员登录入口和条件导航、管理路由基础守卫。
- 使用 MyTools 账号及服务端实时 ADMIN 校验；独立 HttpOnly 会话、上游令牌加密、登录一次性 nonce。
- 教材列表、新增／编辑基础页、单元／课文／附录、改名／排序／撤销、双列输入基础。
- 草稿 revision CAS、已发布版本 INSERT 保留原字节、文件导入同哈希幂等。
- 公开目录 AtomicReference 主体、PDF 生成与打印准备核心。
- 英语实际是左中文、右英语；760/777 条未超过当前组件 2000 条上限，不能误报为“必然截断”。

## 4. 阻断验收的具体差异

P1 表示日常教材维护前必须修复；P2 表示首期功能或交付要求缺失。不是已发生生产损坏的断言。

### F01 · P1 · 刷新后会话恢复不完整，无法保存

**复现：** 登录取得 CSRF → 刷新／新开标签只保留 Cookie → GET session 显示 authenticated=true、教材 GET 200 → 写请求没有 CSRF，403 `TOOLBOX_ADMIN_003`。本次 HTTP 探针已复现。

**根因：** [admin-api.ts:49](/Users/pankang/mycode/MyTools/toolbox-web/src/services/admin-api.ts:49) 只在模块内存保存 CSRF；[admin-auth.ts:82](/Users/pankang/mycode/MyTools/toolbox-web/src/stores/admin-auth.ts:82) 恢复身份未恢复它；[AdminAuthController.java:165](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/controller/admin/auth/AdminAuthController.java:165) session 响应不提供恢复机制。

**修复及验收：** 增加经过同源与会话验证的 CSRF 恢复契约；避免一个标签页恢复令牌导致其他标签页立即失效。真实登录→刷新→保存成功；两个标签页分别恢复后都可写，跨站不可获取或滥用该能力。

### F02 · P1 · 管理编辑实例未随退出、换账号、撤权清理

[App.vue:156](/Users/pankang/mycode/MyTools/toolbox-web/src/App.vue:156) 仍将全部路由放进 KeepAlive；退出只清身份 store，正文保存在编辑页 refs。[TextbookEditorView.vue:515](/Users/pankang/mycode/MyTools/toolbox-web/src/views/admin/TextbookEditorView.vue:515) 只显示错误，未接入 auth.canWrite、撤权清理、取消请求。账号 A 的未保存内容可留到 B 会话。

**修复及验收：** 管理路由移出公共 KeepAlive；缓冲绑定 userId+editionId；主动／跨标签退出、撤权、切账号清空编辑实例与异步任务。明确期限到期才保留同账号缓冲，重新登录比对 revision；503 冻结写入。挂载真实 App+Editor 测 A 修改→退出→B 登录，以及旧翻译／保存回包迟到，B 不得看到或提交 A 的未保存缓冲，公共字帖不受影响。

### F03 · P1 · 已有教材导入后没有可编辑正文，修订映射不完整

[CurriculumFileImporter.java:54](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/migration/CurriculumFileImporter.java:54) 只建 `{}` 草稿并导入公开版本；没有显式开始编辑的 PublishedBook→AdminDraft 转换。探针读到 `subject=null, grade=0, entries=null, base=null`。

[AdminTextbookDraft.java:25](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/validation/AdminTextbookDraft.java:25) 缺少原教材字段；[DraftToBookMapper.java:139](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/DraftToBookMapper.java:139) 将义项、上下文、答案、来源置空；[AdminTextbookService.java:222](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/AdminTextbookService.java:222) 丢地区、ISBN、年份等。直接补“把正文复制过去”仍不能无损修订。

**修复及验收：** 实现 POST 开始编辑、无损双向映射，保留所有 ID、来源及未暴露编辑的字段；原完整行 initialized=true，明确 baseContentVersion。用仓库真实 760、777 条及英语 140 条版本迁移→编辑→保存→发布，逐字段对账；只允许新版本号、发布时间等明确派生字段变化，不改变旧字节。

### F04 · P1 · 双列输入丢行，新行身份和归属错误

[admin-textbook-draft.ts:123](/Users/pankang/mycode/MyTools/toolbox-web/src/lib/admin-textbook-draft.ts:123) 以主正文是否非空过滤，英语只填中文会变成空数组；新行缺稳定 tokenId，父页面未回填且未赋当前节点归属，[TextbookEditorView.vue:395](/Users/pankang/mycode/MyTools/toolbox-web/src/views/admin/TextbookEditorView.vue:395)。

探针复现：同一行连续输入 ID 从 row-1 变 row-2；空单元内三个输入阶段生成三条未分单元内容。学科切换仅改 subject，下次映射还会把原行过滤掉。

**修复及验收：** 添加行时即产生稳定 rowId 和当前归属；任一侧有内容都保留，缺失只标问题。编辑模型为唯一数据源，派生 rows 不自行分配身份。逐字输入、清空一侧、重排、切节点、学科切换确认、保存重开均不增行、不丢行、不换 ID。

### F05 · P1 · 草稿保存和网络恢复没有形成完整流程

[TextbookEditorView.vue:495](/Users/pankang/mycode/MyTools/toolbox-web/src/views/admin/TextbookEditorView.vue:495) 只有手动保存；没有 2 秒自动保存、dirty/edit sequence、队列、离开保护、冲突差异恢复。保存期间继续输入，旧响应仍显示“已保存”。[admin-api.ts:131](/Users/pankang/mycode/MyTools/toolbox-web/src/services/admin-api.ts:131) 每次调用新建幂等键，页面没有持有失败操作的冻结键和 payload。

**修复及验收：** 独立草稿 store/composable，串行冻结 `{payload,revision,key,editSequence,identity}`；成功只更新基线、revision 和仍存在节点映射，后续改动继续 dirty；超时同键同体重试，409 显示差异／读取最新。首次服务端已提交但客户端断网，重试必须取回同一结果；输入和删行不得被旧响应覆盖。

### F06 · P1 · 一次补全未正确接入，迟到结果和非空拼音会被覆盖

管理员页未启用组件自动补全，只有手工按钮；使用另一套弱化逻辑和公开 Guest 翻译接口。[TextbookEditorView.vue:190](/Users/pankang/mycode/MyTools/toolbox-web/src/views/admin/TextbookEditorView.vue:190) 不先持久化 initialized；[第 210 行](/Users/pankang/mycode/MyTools/toolbox-web/src/views/admin/TextbookEditorView.vue:210) 不查目标空白就替换拼音；[admin-textbook-draft.ts:326](/Users/pankang/mycode/MyTools/toolbox-web/src/lib/admin-textbook-draft.ts:326) 合并不核对来源快照与请求代次。

探针：cat 发出后改 dog，回包得到 dog／猫；长／zhǎng 被按钮改为长／cháng。

**修复及验收：** 复用已有可靠补全核心，增加管理员持久化闸门和管理员翻译接口／独立额度；保存新行与标记成功后才首次请求。手工按钮只补空白。原文／目标编辑、删行、切账号、切页面取消请求；回包校验 rowId、来源、目标仍空和代次。失败／取消／刷新不自动再次请求，不覆盖已有人工作答。

### F07 · P1 · 发布不绑定确认过的草稿修订，事务和幂等有缺口

[AdminTextbookController.java:215](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/controller/admin/curriculum/AdminTextbookController.java:215) 不接收 draftRevision/baseContentVersion/changeNote；发布读取此刻草稿。[AdminTextbookService.java:182](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/AdminTextbookService.java:182) 锁外分配版本，保存映射 CAS 结果被忽略；[CurriculumPublisher.java:97](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/CurriculumPublisher.java:97) 再分配一次。

[AdminIdempotencyService.java:72](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/AdminIdempotencyService.java:72) 业务先提交、回执另写。探针注入回执写失败：客户端失败，revision 已从 3 变 4，回执不存在，同键重试 409。

**修复及验收：** 请求必须绑定 revision/base/key；一套事务覆盖锁内分配版本、正式映射、最终校验、版本/current/epoch、草稿干净基线、审计及回执。事务成功后原子换快照。测试同册冲突、不同册同时发布、同键并发、业务提交／回执／快照之间故障；不得产生重复版本或发布未确认的新修改。

### F08 · P1 · 校验与人工核对未构成发布门槛

[AdminTextbookService.java:103](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/AdminTextbookService.java:103) 算了草稿校验结果却未阻断保存；[CurriculumValidator.java:78](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/validation/CurriculumValidator.java:78) 把 partial 视为可跳过核对。探针中未核对行、山／123、课文所属 u2 但条目属于 u1、同父重复课文顺序全部 `publishable=true`。这是前置门禁缺陷，不表示这些样本已在线上公开。

确认仍直接接收 confirmed/digest；摘要不含来源／上下文；发布人被当核对人，[AdminTextbookService.java:197](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/AdminTextbookService.java:197)。

**修复及验收：** partial 仅指收录不全，不能指核对不全。结构非法保存拒绝、可暂缺字段保留；新增改动条目必须人工确认。客户端提交确认意图，服务器自行算摘要并记确认人／时间，覆盖影响含义的字段。改来源、释义、上下文或答案撤销旧确认。错误返回 rowId+字段路径，发布前完成所有校验，不能提交后才由目录重载发现坏内容。

### F09 · P1 · 正式 ID 按位置生成，前插碰撞

[DraftToBookMapper.java:87](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/DraftToBookMapper.java:87) 和[第 124 行](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/DraftToBookMapper.java:124) 按数组位置产生 ID，再与旧映射复用混合。探针已有 e0001 前插新条目后得到两个 e0001；单元／课文同类风险。

**修复及验收：** 首次保存时事务内生成唯一正式 ID，服务端维护映射；禁止客户端任意改映射、跨教材引用或重复临时 ID。前插／中插／删除后再加／重排以及同键重试，原 ID 不变、新 ID 唯一；返回三类映射。

### F10 · P1 · 草稿预览的学科、题型、内容范围错误

[TextbookEditorView.vue:121](/Users/pankang/mycode/MyTools/toolbox-web/src/views/admin/TextbookEditorView.vue:121) 固定 chinese-pinyin/chinese；英语也走语文链路。预览读取服务端整册，忽略本地新编辑和当前选择范围；没有四题型选择。[useAdminDraftPreview.ts:65](/Users/pankang/mycode/MyTools/toolbox-web/src/composables/useAdminDraftPreview.ts:65)。

**修复及验收：** 显式学科／题型／范围，使用冻结当前编辑快照或等待当前修订保存后读取同一 revision；缺必要字段定位，不能预览时重译。语文两型、英语两型分别对照内容、页数、PDF 下载和打印预览；至少三页样本，后台来源页也正常准备。沿用现有 print intent 修复。

### F11 · P1/P2 · 目录与词典的发布一致性仍有缺口

- [CurriculumController.java:79](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/controller/curriculum/CurriculumController.java:79) 目录仍 max-age=300，没有设计要求的 no-cache+epoch ETag。
- [TranslationDictionary.java:112](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/translation/TranslationDictionary.java:112) 清空再填可变 Map，读端不与重建互斥；多次换代后存在接受混代结果的分支。
- [CurriculumPublisher.java:146](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/CurriculumPublisher.java:146) 设 ready=false，但公开读取未消费这个状态；失败时可能继续服务旧快照。
- files 模式只在启动决定，发布器却重载数据库目录；未完成全量迁移时存在切掉文件教材的风险。此为代码路径分析，未对线上注入故障。

**修复及验收：** 本代 epoch、目录、索引、词典一起组成不可变快照；局部构建成功后交换，不清共享 Map；翻译缓存隔离代次。files 模式禁止管理发布，或显式完整切库后才开放。失败关闭教材就绪且可恢复；同一请求只取一个快照。验证新释义而不只检查 apple 非空。

### F12 · P2 · 录入页与列表首期功能缺失

- 图片识别入口及双列候选确认未接入；TSV 当一条正文，剥标点、跳空行和默认去重，破坏配对和原文，见 [admin-bulk-paste.ts:27](/Users/pankang/mycode/MyTools/toolbox-web/src/lib/admin-bulk-paste.ts:27)。
- 缺类别多选／筛选、每行来源／义项、逐项核对和问题定位；“确认内容”对整个节点处理。
- 要求手填内部教材 ID，缺地区／ISBN／年份等；已发布身份字段无清晰锁定；新增默认语文／上册而非未选。
- 缺发布前差异摘要与确认、“在字帖中查看”预定位、版本记录 UI 及管理历史接口。
- 列表只用已发布元数据筛选：[AdminTextbookRepository.java:240](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/mapper/admin/AdminTextbookRepository.java:240)。探针新建语文草稿总列表 1 条，筛语文变 0 条；草稿可能显示内部 ID，缺“已发布·有修改”和部分收录状态。

**验收：** 按设计 3.3—3.6 补齐，保留简单主界面，更多字段放折叠区。新草稿可按真实名称／学科／年级搜索；两列不等长保留位置，短语、感叹号与同词不同义完整保留；OCR、原文、建议和人工确认状态可区分。

### F13 · P2 · 管理会话其它设计差异

- 完全没有提前 refresh／单飞／CAS，实现不能按 A08 全过；refresh_revision 固定 0。
- 同源 Cookie 无 CSRF 退出仍 200 并实际撤销，HTTP 探针已证实。已有 SameSite／Origin 防护，**不将其夸大为任意跨站可注销**；仍须补本地 CSRF 校验且不依赖上游可用性。
- 登录成功后 validate 异常／本地建会话失败缺完整上游清理，见 [AdminAuthService.java:40](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/session/AdminAuthService.java:40)。
- session 查询会 touch 空闲期限；活动更新为 find 后 upsert，应改带状态／版本条件的 UPDATE，避免与注销并发时旧请求重建被删除会话。

**验收：** 短 TTL 提前刷新、普通请求与刷新交错、撤销后不复活、状态轮询不续空闲、同源缺 CSRF 退出拒绝、上游故障本地合法退出成功；建会话失败不残留新上游会话。

### F14 · P2 · 迁移与回退不完整，旧验收证据不足

迁移改变原 catalogVersion、未保留完整来源快照；export-manifest 只有 hash／长度等清单，没有可落盘原始 bytes／来源／current 包和校验恢复流程。管理历史 API 缺失，不能依靠公开旧版接口下载（方案 C 已排除）。[AdminTextbookService.java:300](/Users/pankang/mycode/MyTools/service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/AdminTextbookService.java:300)。

**验收：** 独立迁移先校验再写入，保持旧正文 hash 与原 catalogVersion；完整导出包含当前与历史原字节、来源及索引；隔离环境从该包恢复，验证 current、历史存储、草稿和审计，不操作生产回退来证明本地逻辑。

### F15 · P2 · 移动端、可访问性与验收记录未闭环

编辑器只把目录堆到上方，没有手机目录抽屉／平板折叠；操作栏不是固定底部，tiny 目录按钮缺 44px 保障；管理员菜单是 hover 触发。公开页面 390px 截图不能证明管理页 320/375/768/1280/1440px 合格。

旧记录把 store 身份清空测试当编辑缓存清空证据、把公开字帖打印当管理四题型证据；`admin-account-switch.test.ts` mock 的 `postAdminLogout` 也不是实际导出的 `adminLogout`。部分测试把违反设计的行为作为通过预期，例如 partial 未核对可发布、剥离标点。

**验收：** 真正挂载 App/Editor 的组合测试、五个尺寸管理页截图、深色与键盘路径；修正 mock 和错误预期，消除当前构建警告。旧报告保留为历史，新报告只按当前可重跑证据判定。

## 5. A01—D05 本次重新判定

“部分”表示基础子项有证据，但完整场景仍未满足；“未通过”表示有确定反例或明确缺失的必需实现；“未完成验证”不表示已经复现生产故障。范围受限项不计为产品已通过。

| 项 | 本次状态 | 判定依据 |
| --- | --- | --- |
| A01 | 通过（线上公开范围） | 公开浏览器检查通过，匿名隐藏管理菜单。 |
| A02 | 通过（本次覆盖范围） | 线上匿名 GET/直达拦截，后端匿名写拒绝测试；伪造 Cookie 的真实服务端测试与浏览器受限头设置要区分。 |
| A03 | 部分 | 注入 USER 的服务端测试通过；本次未用真实线上 USER。 |
| A04 | 未通过 | 恢复登录后无 CSRF，写入失败 F01。 |
| A05 | 部分 | 后端实时拒绝有测试；前端状态和缓冲处理不完整 F02。 |
| A06 | 部分 | 后端不信任前端身份；组件只是 Probe，未覆盖实际编辑页全组合。 |
| A07 | 未通过 | 缺 CSRF 的同源退出仍执行 F13。 |
| A08 | 未通过 | 提前刷新及交错处理缺失 F13。 |
| A09 | 未通过 | 管理 KeepAlive 与缓冲未清理 F02。 |
| B01 | 部分 | 新建手动保存基础存在；自动保存／刷新写能力缺失。 |
| B02 | 未通过 | 目录存在，但新行不归当前节点 F04。 |
| B03 | 未通过 | 撤销基础存在，ID 前插碰撞 F09。 |
| B04 | 未通过 | TSV、单侧／空行与标点反例 F04/F12。 |
| B05 | 部分 | 跨课基础保留；同范围重复义项／多类别 UI 不全。 |
| B06 | 未通过 | 真正首次自动补全和持久化闸门缺失 F06。 |
| B07 | 未通过 | 迟到译文配到新原文，非空拼音可覆盖 F06。 |
| B08 | 部分 | 仓储 CAS 正常；真实编辑冲突恢复与发布修订边界缺失。 |
| B09 | 未通过 | 保存回执失败不可可靠重试 F05/F07。 |
| B10 | 未通过 | 新造大数组测试不能代替实际旧教材导入编辑；迁移后空草稿 F03。 |
| B11 | 未通过 | 核对意图／记录／来源摘要不完整 F08。 |
| C01 | 未通过 | 校验探针有四个反例 F08/F09。 |
| C02 | 部分 | 基本发布与目录读取存在；同代与缓存保证不完整。 |
| C03 | 部分 | 草稿与公开版本分离；指定修订发布及无损编辑不完整。 |
| C04 | 部分（方案 C） | 原字节保留有证据；公开旧版404符合例外；真实旧练习延续未单独复验。 |
| C05 | 未通过 | 事务／回执故障与发布意图缺失 F07。 |
| C06 | 未通过 | 词典混代风险、公开 Guest 翻译及旧结果覆盖 F06/F11。 |
| C07 | 未通过 | partial 错当可跳核对，完整范围证据不足 F08/F12。 |
| C08 | 未完成验证（实现有一致性缺口） | 发布锁、epoch与内容读取未统一；已有单次正常测试不能证明两册最新内容及故障交错始终同代。未动态复现两册更新丢失，见F07/F11。 |
| D01 | 部分 | 原字节相同；来源／目录版本／草稿映射不完整 F03/F14。 |
| D02 | 未通过 | 没有可恢复完整导出包和闭环演练 F14。 |
| D03 | 通过（本次 HTTP 与测试范围） | 公共路径／健康／基础体积与匿名合同测试通过。 |
| D04 | 未通过 | 管理端响应式交互缺失且五尺寸实测未完成 F15。 |
| D05 | 未通过 | 管理预览题型硬编码且非当前范围 F10。 |

不计算“通过百分比”：线上公开检查、隔离身份测试、静态代码和待实机项目不是同一证据层级。

## 6. 可执行修正计划

以下是给实现 Agent 的工作计划，本次未执行源码修复。先修数据和权限，再补页面，不需要重新搭站。

| 阶段 | 责任模块／具体任务 | 依赖 | 完成门槛 |
| --- | --- | --- | --- |
| S0 基线与反例 | 保留当前设计及方案 C；记录工作区改动；把本目录反例转为真正回归测试，修正错误测试预期 | 无 | 旧实现上关键反例测试真实失败；不删除原公共测试 |
| S1 会话闭环 | auth controller/service/store、admin-api、App/router；CSRF 恢复、管理缓存隔离、集中失效、提前刷新、注销与活动 CAS | S0；可与 S2 并行 | F01/F02/F13 场景通过；公共草稿不丢；真实 Editor 换账号隔离 |
| S2 无损草稿与校验 | AdminDraft DTO、repository、双向 mapper、importer、validator；导入开启编辑、完整来源与字段、唯一映射、确认意图 | S0 | 真正三册 fixture 无损往返；非法结构保存拒绝；所有新增改动待核对；前插 ID 不重复 |
| S3 编辑保存状态机 | 新 admin draft store/composable、PairedEditor 适配、API DTO；稳定行、单侧保留、归属、dirty/队列、冻结 key、冲突恢复、离开提醒 | S2 契约先确定；实现可并行 | F04/F05；慢保存期间继续输入/删除不误报已保存，丢响应同键恢复 |
| S4 原子发布与快照 | publisher/idempotency/repository/catalog/dictionary；revision/base、固定锁序、一事务、快照就绪、缓存及 files 模式边界 | S2 | 指定修订、同键并发、双册同时发布、故障注入全部通过；坏 current 不提交 |
| S5 录入与一次补全 | 管理翻译接口独立额度、可靠补全核心、持久化初始化、TSV/OCR、类别／来源／核对 UI | S1/S2/S3 | F06/F12 输入反例全部修复；无非空覆盖，取消／刷新不二次自动请求 |
| S6 产品功能补齐 | 教材摘要列表、表单字段、范围／四题型当前快照预览、PDF下载、发布摘要、管理版本抽屉、手机目录与底部动作 | S2/S3/S4 | 列表可筛草稿；编辑身份字段按设计锁定；四题型三页样张及五尺寸可用 |
| S7 数据交付与全流程 | 完整迁移导出包、恢复演练、MySQL 集成、真实浏览器场景、证据与范围报告 | S1—S6 | 重做 A01—D05，证据能重跑；零构建告警；未执行项明确标记 |

### S2/S3 必须先固定的接口

1. `GET /admin/v1/session`：安全身份、会话状态原因与写入凭据恢复策略。不给浏览器上游 token。
2. `POST /admin/v1/textbooks/:id/draft`：显式由 current 版本创建可编辑草稿；已有草稿返回当前，GET 不偷偷写入。
3. `PUT .../draft`：If-Match、稳定 Idempotency-Key、完整 AdminDraft、确认意图；响应 revision/ETag/映射/校验定位。不要返回旧整包要求前端覆盖。
4. `POST .../validate`：绑定 draftRevision；`POST .../publish`：绑定 draftRevision+baseContentVersion+changeNote，幂等摘要包含完整意图。
5. `POST /admin/v1/translations`：管理员主体、独立额度、限批量；不再调用公开 Guest 翻译补后台教材。
6. 管理历史列表／单版详情／可校验导出；保持 public current-only。

### 必须新增的组合验收

- 登录→刷新→编辑旧教材→保存；多标签写入；退出/换账号时正在翻译或保存。
- 打开真实 760/777/140 条导入教材，改一行，其余字段/ID/来源无损；前插／中插不碰撞。
- 空单元新增一行逐字输入，两个栏位任意先填，切换节点/保存/刷新条数不变。
- 1000ms 延迟翻译期间改原文、手工填目标、清空目标、删行、切节点、换账号；所有旧结果作废。
- 1000ms 延迟保存期间继续输入/删除；服务端已提交但客户端丢响应，同键重试；409 保留本地并可比较。
- 甲校验 revision N，乙保存 N+1，甲发布 N 必须409；不同教材并发均保留；同键并发仅一笔。
- 版本写入、审计、回执、提交与快照交换故障注入；重启恢复 current/epoch/回执一致。
- partial 未核对、非法拼音、跨单元课文、重复顺序均发布失败；修正并人工确认后通过。
- 四种管理草稿题型分别三页样张；预览用最新编辑和当前范围，不发起自动重译；打印窗口全部准备后可打印。
- 320/375/768/1280/1440px、深色、键盘；目录/登录/退出/保存/问题定位可达，不依赖 hover。

实现时可分三条并行工作线：S1 身份、S2/S4 数据、S3/S5/S6 页面。先由一个负责人固定 DTO、错误和状态契约；各 Agent 明确文件所有权，不互相覆盖，再由集成人跑组合验收。

## 7. 交给实现 Agent

复制使用：[完整实现提示词](agent-prompt.md)。提示词要求实际修复并用反例验收，禁止仅修改旧报告或降低设计要求来得到“全部通过”。

## 8. 证据复跑

基础命令：在 toolbox-web 执行 `npm run typecheck`、`npm test`、`npm run test:component`、`npm run test:curriculum`、`npm run build:production`；仓库根执行 `mvn -o -f service/toolbox-service/pom.xml test`。`-o` 只适用于本地依赖齐备，本轮已满足。

前端反例：在 toolbox-web 执行 `node --import tsx ../docs/verification/toolbox-admin-review-2026-10-06/frontend-probes.ts`。探针输出 `issueReproduced=true` 表示问题存在，不表示功能通过。

鉴权 HTTP：根目录执行 `python3 docs/verification/toolbox-admin-review-2026-10-06/run-admin-auth-review-probe.py`，需要允许本机监听，身份和数据库均为测试专用。其余 Java 探针见本目录 `probes-README.md`。

线上公开 E2E：toolbox-web 执行 `E2E_CDP_PORT=9946 npm run test:e2e`，使用独立浏览器，包含公开访问、生成及打印预览，不代表管理写入验收。执行前确认脚本当前版本及目标，不把生产发布／真实打印混入该命令。
