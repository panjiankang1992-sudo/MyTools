# MyTools 全书有声书生成系统设计

## 1. 目标与边界

### 1.1 业务目标

将 MyTools 中已导入、可读取的电子书加工为可长期播放和重复使用的有声书：

1. 解析整本书，形成稳定的元数据、卷/章节和正文结构。
2. 自动梳理人物数量、别名、称谓、性别/类型、年龄段、身份、性格特点、外貌线索、登场位置和人物关系。
3. 自动识别旁白与直接引语，推断说话人并标记置信度。
4. 将人物画像匹配到音色库，支持人工审核、锁定与替换。
5. 按章节生成音频，登记为持久资产；生成完毕后 App 可反复在线播放、下载或导出整书。
6. 文本、人物、音色或发音词典变更后，只重跑受影响章节，不覆盖历史可用版本。

### 1.2 交互目标

```text
电子书详情/阅读器
  -> 点击“生成有声书”
  -> 确认来源权利、默认旁白和生成范围
  -> 后端异步处理整书并展示阶段进度
  -> 人物与音色审核（可选，达到门槛后）
  -> 章节音频陆续可播放
  -> 全书完成后以有声书播放器连续播放、缓存和导出
```

首次点击不要求即时听到音频。系统优先保证整书一致性、可审计性和可重复播放；章节完成后即可提前试听，不必等待全书完成。

### 1.3 首版范围

- 输入：MyTools Reader 已支持且可投影正文的 EPUB、TXT、Markdown、文字层 PDF、MOBI/AZW3。
- 输出：按章节 MP3/AAC、播放清单、章节时长、句级/段级时间映射、封面和元数据。
- 语言：先支持简体中文小说与通用中文文本；英文夹杂、古文、方言和多语种进入评测覆盖。
- 音色：旁白 + 预设角色音色，人物允许锁定固定音色。
- 人工审核：人物、别名、关系、音色、读音词典和低置信度对话可修正。

### 1.4 非目标

- 首版不处理扫描 PDF OCR、漫画、受 DRM 内容或绕过来源限制。
- 首版不默认克隆真人音色，不把姓名作为性别的唯一依据。
- 首版不引入 Neo4j、独立向量数据库或独立 FastAPI 产品壳。
- 实时边生成边播放属于后续增强，不阻塞整书生产流程。

## 2. 现有基础与设计决策

MyTools 已有 HarmonyOS Reader、Reader Service、电子书导入与目录、Task Scheduler/Executor、Storage Gateway、Asset Registry 和任务检查点能力。本方案以这些能力为基础：

| 已有能力 | 新能力如何复用 |
|---|---|
| Reader Service 的电子书资产、目录、章节缓存 | 作为正文投影和书籍版本权威 |
| Task Scheduler/Executor | 编排解析、分析、匹配、合成、封装和重试 |
| Storage Gateway | 保存原书中间产物、章节音频和整书导出 |
| Asset Registry | 对可复用音频资产登记、摘要校验和生命周期管理 |
| MySQL Reader schema | 保存业务状态、人物、关系、分段和生成记录 |
| HarmonyOS 阅读器与远程播放器 | 展示进度、审核入口和音频播放 |

**核心决策：**以章节为最小音频交付单元、以整书为分析和音色一致性边界、以任务版本为可重放边界。

原因是长文本 TTS 在业界通常以异步任务处理。例如，火山精品长文本 TTS 面向小说等批量文本、单次支持十万字符且异步返回；MiniMax 的异步长文本接口面向整书、支持文本文件输入、音频元信息与句级时间戳。[火山引擎精品长文本语音合成](https://www.volcengine.com/docs/6561/79817?lang=zh) [MiniMax Async Long TTS](https://platform.minimax.io/docs/guides/speech-t2a-async)

## 3. 总体架构

```text
HarmonyOS App
  |- 电子书详情：受管媒体导入、生成、进度、人物/音色审核
  |- 有声书播放器：章节列表、播放、下载、断点续播
  `- Reader Gateway --所有权校验--> Media Library
             |
             v
Reader Service（业务权威）
  |- ManagedEbookImportService（冻结媒体身份并登记 Reader ebook_asset）
  |- AudiobookGenerationService
  |- BookTextProjectionService
  |- CharacterGraphService
  |- VoiceMatchingService
  |- AudiobookCatalogService
  `- AudiobookTaskPublisher
             |
             v
Task Scheduler -> Task Executor
  |- reader_extract_audiobook_text
  |- reader_analyze_audiobook_book
  |- reader_match_audiobook_voices
  |- reader_synthesize_audiobook（按冻结旁白/角色音色分段）
  `- reader_export_audiobook（首版：章节 MP3 ZIP）
             |
             +-> LLM Provider Adapter
             +-> TTS Provider Adapter
             +-> ffmpeg Audio Processor
             +-> Storage Gateway
             `-> Asset Registry
```

### 3.1 服务职责

| 服务/组件 | 责任 | 不负责 |
|---|---|---|
| Reader Service | 书籍版本、生成请求、状态机、人物/关系/音色权威数据、用户权限 | 调用供应商 SDK、保存大二进制音频 |
| Task Scheduler | 任务幂等、优先级、租约、重试、取消、检查点、执行记录 | 解释正文、保存人物结果 |
| Executor 任务包 | 受限范围内读取正文、调用 LLM/TTS、校验结果、回写内部接口 | 直接向用户暴露 API 或持有长期用户令牌 |
| LLM Adapter | 结构化人物、关系、说话人、发音建议 | 决定业务状态或覆盖人工修正 |
| TTS Adapter | 音色列表、长文本/分段合成、查询、取消、时间对齐 | 暴露供应商密钥给 App |
| Storage Gateway | 原子发布音频、短期下载 URL、删除和摘要校验 | 根据书籍正文自行生成资产 |
| Asset Registry | 资产谱系、引用、归属、保留期 | 维护播放器业务状态 |

### 3.3 Media Library 到 Reader 的可信桥接

书架的 `mediaItemId` 不是 Reader 的 `ebookAssetId`，因此 App 不得把本地路径、媒体 URL 或 Asset Registry 存储 URI 作为生成请求传入。当前首版流程如下：

同一受管媒体条目以 `ownerId + mediaItemId` 维护 Reader 内部的 `bookLineageKey`，它不同于每次导入产生的 `ebookAssetId`。App 将媒体版本纳入导入与生成幂等键；内容更新后会创建新的冻结资产和 `INCREMENTAL` generation。正文冻结阶段按该谱系查询上一完成版本，再按章节正文摘要复用旧音频；非受管历史资产仅以自身资产标识作为谱系，避免跨书复用。

```text
App: mediaItemId + idempotencyKey + rightsConfirmed
  -> Gateway：按当前登录主体读取 Media Library 条目
  -> 仅 READY + text/plain 通过；Gateway 冻结 mediaItemId、assetId、title、size、sha256
  -> Reader：登记 owner 专属虚拟书源和导入请求
  -> reader_import_managed_ebook：使用内部 Media Library 令牌读取原文件
  -> 再校验 size + SHA-256 + UTF-8，原子发布至 Storage Gateway
  -> 既有 metadata/catalog 步骤登记 Reader ebook_asset
  -> App 轮询导入；取得 ebookAssetId 后才可创建 audiobook_generation
```

浏览器/App、Gateway 对外响应和 Reader 的公开播放清单均不返回 Storage URI、内部令牌或供应商凭据。导入任务只接受受管 `text/plain`（UTF-8、512 MiB）或 `application/epub+zip`（512 MiB）；EPUB 在发布前必须通过 ZIP 路径、符号链接、加密、重复条目、展开大小和压缩率校验，并解析有效 OPF spine。M1 正文投影对 TXT 使用冻结字节偏移，对 EPUB 只读取已验证 spine XHTML 的可见文本。PDF、MOBI/AZW3 仍须在各自专用投影器验证后再放开。

### 3.2 为什么不新建独立系统

附件中的 FastAPI + Celery + Redis + PostgreSQL + Neo4j + MinIO 适合从零建设。MyTools 已有 Java Reader Service、调度和资产服务，重复引入一套网关、队列和对象存储会增加身份、任务和数据同步风险。首版只需在 Reader Service 增加领域模型、API 和任务包；当 TTS/LLM 消耗达到独立扩展门槛时，再把 Provider Adapter 迁为独立服务。

## 4. 数据模型

### 4.1 书籍与生成运行

```text
audiobook_generation
  id, owner_id, ebook_asset_id, book_content_hash, request_key,
  generation_version, mode(FULL|CHAPTERS|REPAIR), status,
  parent_generation_id, revision_role_key, revision_provider, revision_voice_type,
  analysis_version, voice_policy_version, pronunciation_version,
  requested_chapter_count, completed_chapter_count, failed_chapter_count,
  current_stage, error_code, error_detail_ref,
  created_at, started_at, finished_at, updated_at

audiobook_generation_chapter
  generation_id, chapter_index, chapter_title, chapter_content_hash,
  status, analysis_status, synthesis_status,
  audio_asset_id, audio_duration_ms, timing_asset_id,
  voice_plan_hash, retry_count, error_code, updated_at
```

- `book_content_hash` 由归一化书籍内容得出；源 URL 和本地物理路径不参与业务身份。
- 同一 `owner + book_content_hash + generation_version + voice_policy_version` 的重复请求返回已有运行，不重复计费。
- `FULL` 处理整书，`CHAPTERS` 只处理指定章节，`REPAIR` 只重跑受人物、音色或读音变更影响的章节。
- 音色替换从已完成 generation 派生 `REPAIR` 子版本。子版本复制冻结文本、人物图谱、归因与未受影响音频引用；`parent_generation_id + revision_role_key + revision_provider + revision_voice_type` 保留血缘和人工选择证据，旧版不被更新或删除。

### 4.2 人物、关系与说话人

```text
audiobook_character
  id, generation_id, canonical_name, display_name, presentation_tags_json,
  age_group, identity_text, traits_json, appearance_text,
  first_chapter_index, occurrence_count, confidence, locked, version

audiobook_character_alias
  id, character_id, alias, alias_type(NAME|TITLE|NICKNAME), confidence,
  evidence_chapter_index, evidence_locator

audiobook_relationship
  id, generation_id, from_character_id, to_character_id,
  relation_type, direction, strength, confidence, evidence_json, locked

audiobook_speech_segment
  id, generation_id, chapter_index, sequence_no,
  start_locator, end_locator, normalized_text_hash,
  segment_type(NARRATION|DIALOGUE|OTHER), character_id,
  speaker_confidence, emotion_tags_json, voice_binding_id, status
```

`presentation_tags_json` 记录“女性呈现、少年、长者、非人类、未知”等带证据的朗读画像，不把模型推测当作关于角色身份的绝对事实。关系必须保存证据位置；没有可验证证据的关系只能保留为候选，不进入默认音色决策。

### 4.3 音色、读音与产物

```text
voice_catalog
  id, provider, provider_voice_id, locale, presentation_tags_json,
  style_tags_json, age_tags_json, capabilities_json, sample_asset_id,
  enabled, provider_version

character_voice_binding
  id, generation_id, character_id, voice_catalog_id,
  rationale_json, confidence, locked, version

pronunciation_dictionary
  id, owner_id, scope(BOOK|GLOBAL), term, pronunciation, phoneme_or_ssml,
  status, version

audiobook_audio_asset
  id, generation_id, chapter_index, kind(CHAPTER|BOOK|TIMING),
  asset_id, content_hash, format, sample_rate, bitrate,
  duration_ms, loudness_lufs, status, created_at
```

音频二进制只在 Storage Gateway；数据库只保存稳定的 `asset_id`、摘要、技术元数据和引用关系。

## 5. 处理流水线

```text
REQUESTED
  -> TEXT_EXTRACTING
  -> TEXT_READY
  -> ANALYZING
  -> ANALYZED
  -> VOICE_MATCHING
  -> REVIEW_PENDING（可选）
  -> SYNTHESIZING
  -> CHAPTERS_READY
  -> PACKAGING（可选）
  -> COMPLETED

任一阶段 -> FAILED -> RETRYING -> 原阶段
取消 -> CANCELLED
```

### 5.1 Step 1：文本投影与章节冻结

1. 从 `ebook_asset` 和目录读取正文，不接受 App 传入的全文或文件路径。
2. 按章节生成 UTF-8 规范化文本，保留 `chapterIndex + blockIndex + utf16Offset` 定位点。
3. 清理控制字符、页眉页脚、重复空行和不可读节点；保留段落、标题、引号和脚注语义。
4. 记录每章 `chapter_content_hash`，作为后续复用和增量重跑依据。
5. 无法稳定投影的 PDF 或漫画直接标记 `UNSUPPORTED_CONTENT`，不伪造文本。

### 5.2 Step 2：整书人物与关系分析

分析必须采用“分章抽取、分卷聚合、全书归并、证据回写”的四层流程，不能把整书原文一次提交给模型：

```text
Chapter chunks
  -> NER/规则候选
  -> chapter facts（人物、引语、关系、证据）
  -> volume profile（人物状态卡）
  -> book merge（别名、角色、关系图）
  -> reviewable graph
```

**候选生成：**中文 NER、书名号/引号、说话动词、称谓词典和章节结构先产生候选，降低 LLM 漏召回和成本。后续章节可接收前序冻结正文中已通过范围校验的规范名和别名候选，但最多 200 名、总计 4,096 个 Unicode 码点；它们不携带正文，只是模型核对规范名的提示，不能绕过当前窗口的证据校验或自行合并人物。

**LLM 输出：**每次调用强制 JSON Schema，返回人物、别名、属性、关系、原文证据、说话人、置信度和版本。服务端校验所有引用范围必须落在输入窗口内；越界证据和未知字段一律拒绝。执行器同时冻结实际配置的模型标识和候选、归并、范围校验规则版本；两者进入结果摘要指纹、generation 持久化记录和审核页，并被不可变修订版本继承。低置信度说话人审核卡的可读摘录不由模型提供：执行器只在范围校验通过后，从冻结章节窗口截取固定上限的上下文、归一化空白并拒绝控制字符，再随受所有者隔离的分析结果返回。

**归并原则：**

- 明确同名可直接归并；跨章节名称变化只在“一个规范名恰为另一角色的唯一、带原文范围的别名证据”时自动合并，并将关系和说话人引用重映射。共享称谓、多个角色共用的别名和无法唯一定位的共指保留为独立候选，交给人工审核。
- 姓名、性别、年龄和性格均允许 `UNKNOWN`，不以姓名词典单独断言。
- 文学引语说话人归因由人物识别、共指、引语识别和归因共同决定；低置信度回退旁白。
- 人工锁定后的角色、别名、关系和音色不能被自动任务覆盖。

小说说话人归因本身是复合问题，相关研究将其拆为人物识别、共指、引语识别和说话人归因；中文引语语料也表明需考虑说话人、听话人、说话方式和语言线索。[Quotation Attribution Research](https://arxiv.org/abs/2307.03734) [Chinese Quotation Corpus](https://arxiv.org/abs/2408.09452)

### 5.3 Step 3：音色匹配与审核

```text
人物画像
  -> 硬过滤：语言、可用性、呈现标签、年龄段
  -> 规则评分：旁白/角色、风格、音域、叙事类型
  -> LLM 精排：只在候选 Top-K 内排序并生成理由
  -> character_voice_binding
  -> 人工审核/锁定
```

旁白固定使用书籍默认音色；同一锁定人物全书固定一个 `voice_catalog_id`。当角色置信度、音色匹配或说话人置信度低于阈值时，使用旁白而非猜测性换声。

用户可以在审核界面：

- 合并/拆分人物，编辑别名和呈现标签。
- 选择或锁定音色，试听同一段的候选音色。
- 更正读音、停顿、语速和章节排除范围。
- 只对受影响章节点击“重新生成”。

自动化结果达到配置质量门槛时可跳过人工审核；否则进入 `REVIEW_PENDING`，默认不开始多角色合成。执行器还要求同时设置 `AUDIOBOOK_MULTI_CHARACTER_ENABLED=true` 与 `AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED=true`；后者是黄金集、NER 和跨章节人工复核均已留存验收记录的部署级凭据，不能只靠功能开关绕过。用户也可以选择“仅旁白直接生成”。

### 5.4 Step 4：章节分段与 TTS 合成

每章先转换为 `SpeechSegment[]`：标题、旁白、直接引语、注释和不可朗读节点分离。角色变化、章节变化、读音词典变化必须切分；普通文本按句末优先合并为供应商安全长度。

首个火山在线适配器以 **UTF-8 字节**（当前每段最多 900 字节）而不是字符数切分并二次校验请求体。这样中文、英文和混排文本都不会因“字符数看似未超限”而越过供应商的请求边界。TTS 配置中的上限属于 Provider Adapter，不得泄漏到 Reader 领域模型。

```text
SpeechSegment
  -> render SSML/pronunciation policy
  -> TTS request or long-text job
  -> download/stream result into temp storage
  -> decode validation
  -> loudness normalization
  -> chapter concat + timing merge
  -> atomic publish chapter asset
```

- 对单章内的短文本分段，用供应商同步/流式接口并行生成，然后按 `sequence_no` 拼接。
- 对整章或整卷的长文本，优先使用供应商异步长文本接口，仍要求回写章节边界和时间映射。
- 统一输出 AAC 或 MP3；格式、采样率、声道、响度、编码器和静音策略在每次生成开始时冻结。
- 使用 ffmpeg 进行解码验证、采样率统一、响度归一、静音裁剪、淡入淡出和章节拼接。
- 每个章节先完成校验、计算摘要、发布到 Storage Gateway，最后在一个事务内写入 `audiobook_audio_asset`。

火山在线合成单请求文本长度较小，适合分段；其精品长文本服务适合异步小说批处理。MiniMax 异步长文本接口则支持整书级输入、音频时长/大小和句级时间戳。Provider Adapter 必须支持两种路径，而不能将某一家厂商的限制写死在业务层。[火山语音合成说明](https://www.volcengine.com/docs/6561/79817?lang=zh) [MiniMax 异步长文本能力](https://platform.minimax.io/docs/api-reference/api-overview)

读音词典的 Provider Adapter 规则如下：只有供应商的非实时端点和所选音色已验证支持 SSML 时，才将经审核的中文词条渲染为单层 `<speak>` 内的 `<phoneme alphabet="py" ph="...">词条</phoneme>`；拼音必须是带 1–5 声调数字的受限词法，正文和词条文本一律 XML 转义。调用必须显式设置 `text_type=ssml`，不使用 SSML 时仍走 `plain`。`ssmlSupported` 是受控音色目录的必填布尔配置，写入并冻结在 `audiobook_voice_binding`；只要冻结绑定中有一项未明确支持，读音修订就会被拒绝，绝不静默忽略。词条变更由受限 `reader_prepare_audiobook_pronunciation_revision` 执行器逐章下载冻结正文、核验 SHA-256 后做精确词条匹配；只有它回传的 `chapterIndex` 才失效子版本的音频并重合成，其余章节继续复用父版本资产。模型输出、客户端输入和旧版本正文都不能直接进入 SSML 请求体，只有合成执行器能生成并校验该受限模板。

### 5.4.1 合成中断与发布故障恢复

章节在 Storage Gateway 原子发布、Asset Registry 幂等登记和 Reader 批量回写完成前，都不是可播放产物。任何下载、TTS、封装、发布或登记异常都会终止当前任务，绝不调用整书完成接口；已成功回写的先前章节保持 `READY`。调度重试时，Reader 的合成输入仅返回尚未 `READY` 的章节，因此不会再次调用 TTS；如果全部章节均已回写、但 Executor 恰在整书完成回写之前中断，输入允许为空并仅执行幂等的完成回写。TTS 对网络错误和 `408/429/500/502/503/504` 使用有限指数退避，并在各次尝试保持同一供应商请求标识。真实供应商的限流和并发故障演练必须在隔离环境执行，不能以生产正文或凭据作为测试数据。

### 5.5 Step 5：封装、播放与增量更新

- 所有章节成功后，首版异步生成 ZIP：逐个校验已冻结章节 MP3 的摘要与大小，归档只写章节文件与不含存储定位信息的 `metadata.json`。M4B/整书 MP3、书名、作者、封面和章节标记属于后续格式扩展，不能阻塞 ZIP 的可用性。
- App 查询生成运行和章节产物；章节 `READY` 即可播放，整书 `COMPLETED` 后启用连续播放与导出。
- App 的播放进度继续以 Reader 的文本定位和章节定位为主，不以音频文件 URL 作为同步身份。
- 更新规则：正文修改、删除或章节身份映射不完整时保守重分析全书；只有完整一对一映射且无正文修改/删除时，才复用旧事实并仅分析新增章节。人物/音色/读音变化只重跑使用该绑定的章节；全书分析版本变化可创建新 generation，不覆盖旧版本。

### 5.6 增量处理：电子书新增或修改章节

增量的判断单位是**规范化章节内容**，不是整个文件的大小、上传时间或章节序号。每次 Reader Service 重新导入或刷新一本书时，都建立新的 `book_revision`，并对目录逐章计算：

首版把受管媒体的 `ownerId + mediaItemId` 写为内部 `bookLineageKey`。App 只提交受管媒体条目与版本，Gateway/Reader 复核后创建新 `ebookAssetId`；同一谱系的 generation 版本连续，因此新资产仍能找到上一完成版本并复用章节音频。历史非受管资产以自身 `ebookAssetId` 建立谱系，不会错误跨书复用。

```text
chapterIdentity = SHA-256(bookStableId + chapterResourceRef)
chapterContentHash = SHA-256(normalizedChapterText)
```

服务端将新目录与上一已完成 revision 的章节按 `chapterIdentity`、标题相似度和内容摘要依次对齐，归类为：

| 变更类型 | 判定 | 处理动作 |
|---|---|---|
| `UNCHANGED` | 身份与正文摘要相同 | 总是复用章节音频；满足全书事实复用门禁时，继承已验证分析与音色绑定 |
| `APPENDED` | 新目录项未在旧 revision 出现 | 满足全书事实复用门禁时，只抽取、分析、合成新章节；否则与全书一起重新分析 |
| `MODIFIED` | 身份相同但正文摘要变化 | 重跑该章节并保守重分析全书人物档案；未变章节音频仍可复用 |
| `MOVED_OR_RENUMBERED` | 正文摘要相同但章节位置变化 | 复用资产并更新目录、顺序和播放清单；仅在全书章节映射完整时继承分析事实 |
| `REMOVED` | 旧目录项不再存在 | 新清单隐藏该章；旧资产保留至无引用和保留期结束 |
| `SPLIT_OR_MERGED` | 标题/资源变化且正文区间发生拆分或合并 | 标记受影响章节重跑，禁止直接拼接旧音频 |

例如连载小说从第 100 章增加到第 105 章时，前 100 章正文摘要不变，系统创建 `INCREMENTAL` generation，只调度第 101–105 章的文本投影、人物增量分析和音频合成；既有章节音频和播放进度不变。

#### 5.6.1 人物图谱的增量归并

新增章节不是独立分析。当前实现仅在上一完成版本的全部历史章节可一对一映射、且本次无正文修改和删除时启用事实复用：Reader 将上一版本人物、别名、关系和说话人区间按当前章节序号重映射，执行器重新校验所有受继承字段与证据位置，再以其规范名/别名作为非权威模型提示，仅调用模型分析新增章节。随后把继承事实与新增事实按当前规则版本归并，得到新的不可变全书档案。

同一安全门禁下，音色计划优先继承上一版本的旁白和已有角色绑定；继承音色必须仍存在于当前审核目录，目录撤销会让该 generation 明确失败而不会静默改用新音色。新人物才按当前规则匹配音色。正文修改、章节删除、来源身份变化或映射不完整时，任务不继承事实，而是重分析全书；旧完成版本与旧音频始终不被原地修改。

#### 5.6.2 依赖与重跑范围

每个章节生成时记录依赖快照：

```text
chapterContentHash + analysisVersion + characterProfileVersion +
voicePlanHash + pronunciationVersion + ttsModelVersion
```

重跑范围按依赖图计算：

- 新章节：只生成新章节；引用同一锁定人物时直接复用该人物音色。
- 新别名或关系：更新全书画像，但不自动重跑历史章节。
- 角色音色被用户替换：只重跑该角色实际出现的章节。
- 读音词典修改：受限执行器读取并校验冻结正文，精确找出命中词条的章节；只重跑该结果集。
- 正文修改：重跑改动章节；若该章修改了主角色事实，只创建“需要审核”的人物档案补丁。
- 解析器/LLM/TTS 模型大版本升级：新建独立 generation，允许用户比较后切换，不覆盖旧版本。

#### 5.6.3 并发与幂等

`INCREMENTAL` generation 创建时会冻结本次 `book_revision` 和待处理章节集合。若用户在运行中再次刷新同一本书：

1. 已运行任务继续处理其冻结 revision，不读取变化中的正文。
2. 新刷新创建或复用下一 revision 的 generation。
3. 相同 `book_revision + chapterContentHash + voicePlanHash` 的章节任务复用已有结果。
4. 只有最新 revision 被标记为默认播放清单；旧 revision 仅保留给正在播放的会话，避免播放中途跳到不同正文。

## 5.7 有声书连续播放与自动下一章

播放器不根据本地文件名推断下一章，而是请求 Reader Service 的版本化播放清单：

```json
{
  "generationId": "uuid",
  "bookRevision": "sha256",
  "manifestVersion": 27,
  "chapters": [
    {
      "chapterIndex": 12,
      "title": "第十二章",
      "status": "READY",
      "audioAssetId": "uuid",
      "durationMs": 1240380,
      "playUrl": "short-lived-url"
    }
  ],
  "nextReadyChapterIndex": 13,
  "overallStatus": "SYNTHESIZING"
}
```

App 在开始播放第 N 章时，预取第 N+1 章的清单、短期播放 URL 和播放器缓冲；当前章收到播放器 `completed` 事件时执行下列状态机：

```text
当前章完成
  -> 下一章 READY？
     -> 是：保存第 N 章完成进度，自动播放 N+1
     -> 否：下一章仍在生成？
        -> 是：显示“下一章生成中”，自动轮询，最长等待配置时长
        -> 否：下一章失败？显示原因、重试与跳过
        -> 否：没有下一章，标记整书播放完成
```

默认策略：

- 下一章在当前章还剩 20% 时开始预取，避免章节结束后重新请求。
- 下一章 `READY` 时无须用户确认，自动切换并从 0 秒播放。
- 下一章生成中时继续轮询；用户可选择“等待下一章”或“跳到下一已完成章节”。
- 等待期间不会播放未校验的临时音频；URL 过期后刷新播放清单，不重启生成任务。
- 当前 generation 的章节列表固定，增量 revision 产生的新章节只在当前阅读会话结束、或用户显式刷新时进入清单，避免听书过程顺序变化。
- 用户手动跳章、上一章或重播时，播放器取消不必要的预取，但不取消后台整书生成。

当前实现的服务端播放边界：App 先以登录态查询 `/api/app/v1/reader/audiobook-generations/{generationId}/playback-manifest`，再为目标 `READY` 章节申请播放票据。票据流地址不携带用户身份、存储 URI 或内部令牌；Gateway 将票据绑定到 `ownerId + generationId + chapterIndex`，Reader 再按所有权定位已登记音频。读取链路保留单段 HTTP `Range`，因此系统播放器可以跳转和恢复播放而不下载整章。

当前火山引擎适配器以 V3 HTTP Chunked 为新部署默认：请求只发送去标识化 UID、冻结音色、V3 API Key、Resource ID 和请求 ID，返回的连续 JSON 音频事件即使跨网络分片也会被逐个解析；只有收到完成码且 MP3 校验通过后才会发布章节。音色目录会先按 `apiVersions` 和 `resourceIds` 过滤，只有当前 Resource ID 兼容的旁白/角色才会被冻结；资源与音色不匹配会在音色规划阶段失败，而不是在整书合成中途静默换声。V1 HTTP 非流式仍作为旧资源兼容路径，使用独立的 `appid/access_token/cluster` 契约和最长 900 UTF-8 字节的片段。端点、鉴权和音色资源不能跨版本混用；V3 读音 SSML 尚未完成供应商 POC，目录中不得标记为 `ssmlSupported`，以免未验证标记进入生产正文。

当前 HarmonyOS 首版入口位于“多媒体 → 文本”：服务端目录只返回当前用户的受管条目，App 只对 `text/plain` 显示“生成有声书”。用户在面板中明确确认朗读权后，客户端以稳定幂等键先轮询受管导入、再轮询整书 generation；任务完成后才展示章节控制。它为有声书使用独立 `RemoteMediaPlayer`，开始播放会关闭普通媒体音频会话，避免两个后台音频会话竞争。播放器收到 `completed` 时重新读取固定 generation 的清单：紧邻章节为 `READY` 则自动播放；为 `PENDING` 或 `FAILED` 则显示状态且绝不跳过。应用持久化同账户、受管媒体版本和 generation 版本下的安全章节进度，恢复时重新读取清单及短期播放票据，不存储票据、URL 或存储地址。审核工作台已可查看人物、关系和冻结音色并创建最小范围音色修订；人物音色通过可筛选下拉目录选择，已审核样音则由短期票据交给独立播放器试听，试听前会保存正文进度并停止正文会话。已完成版本可在面板创建或续轮询 ZIP 导出，完成后用户点击“下载 ZIP”才即时申请票据并交给系统下载能力，票据和路径绝不落盘。下一章预取仍待 App 接入。

### 5.7.1 断点续播

进度保存为：

```text
generationId + bookRevision + chapterIndex + audioPositionMs + textLocator
```

恢复时优先使用同一 `generationId + bookRevision` 的章节音频。该版本因保留策略已删除时，App 用 `textLocator` 映射到当前默认 revision 的相应章节并提示用户；不使用同名章节的音频毫秒数直接跳转，避免内容修改后串位。

## 6. Provider Adapter 设计

```text
TtsProviderCapabilities
  provider, supportsSynchronous, supportsStreaming, supportsAsyncLongForm,
  supportsSsml, supportsPronunciationDictionary, supportsSentenceTiming,
  supportsVoiceClone, maxInputCharacters, maxConcurrentRequests,
  supportedFormats, supportedLocales

TtsProvider
  listVoices(locale)
  synthesizeSegment(request)
  submitLongText(request)
  queryLongText(jobRef)
  cancel(jobRef)
  health()

LlmProvider
  extractChapterFacts(schema, prompt, textWindow)
  mergeBookProfile(schema, bookState, newFacts)
  rerankVoices(schema, character, candidates)
```

Provider 请求结果至少包含：供应商请求 ID、模型与音色版本、字符数、计费单位、音频时长、格式、时间映射、重试分类和摘要。密钥只由 Executor 环境注入，绝不进入数据库参数、App、日志或任务结果。

首个可运行的 `reader_synthesize_audiobook` 执行器只实现了 `VOLCENGINE` 在线分段 MP3 路径。Reader 将冻结的 `provider + voiceType` 传递到旁白和每个说话人片段；执行器逐段验证 provider，不匹配时明确失败而不把其他厂商的音色 ID 误送到火山引擎。新增 Provider 必须增加独立适配器和契约测试，之后才可在同一音色目录中启用。

首选部署策略：

| 能力 | 首选 | 备选 | 选择理由 |
|---|---|---|---|
| 整书/长文本 TTS | 豆包精品长文本或 MiniMax Async | 云厂商批量 TTS | 原生异步、适配书籍处理 |
| 短分段/角色 TTS | 豆包大模型 TTS | MiniMax HTTP TTS | 中文韵律、音色与参数能力 |
| LLM 结构化分析 | Qwen/DeepSeek 兼容服务 | GPT/Claude | 中文文本理解与 JSON Schema |
| NER/候选 | HanLP/LTP + 规则 | 纯 LLM | 控制成本、可解释、提高召回 |
| 关系存储 | MySQL 关系表 | Neo4j（规模验证后） | 首版查询和审核足够 |
| 音色检索 | 标签规则 + Top-K | pgvector | 音色库规模较小时无需向量库 |
| 音频后处理 | ffmpeg | — | 编码、校验和拼接稳定 |

供应商额度、可用音色、价格和地域随时变化；POC 当天必须重新核验。以 AWS Polly 为例，同步/长文本/生成式引擎的吞吐和并发约束不同，说明业务层必须实现并发上限、退避与预算，而不是无限并发重试。[Amazon Polly Quotas](https://docs.aws.amazon.com/polly/latest/dg/limits.html)

## 7. API 设计

外部 Gateway 从已验证会话注入 `ownerId`；Reader Service 内部接口需 `READER_INTERNAL_TOKEN`。客户端不能传正文、音频路径、供应商密钥或任务参数快照。人工替换仅可从服务端返回的已启用音色目录中选择 `provider + voiceType`，Reader 会再次校验目录状态，不能提交任意供应商音色。

```http
POST /api/app/v1/reader/managed-ebook-imports
GET  /api/app/v1/reader/ebook-imports/{importId}
POST /api/app/v1/reader/audiobook-generations
GET  /api/app/v1/reader/audiobook-generations/{generationId}
GET  /api/app/v1/reader/audiobook-generations/{generationId}/book-analysis
GET  /api/app/v1/reader/audiobook-generations/{generationId}/voice-plan
GET  /api/app/v1/reader/audiobook-generations/{generationId}/voice-catalog
POST /api/app/v1/reader/audiobook-generations/{generationId}/voice-revisions
POST /api/app/v1/reader/audiobook-generations/{generationId}/speaker-revisions
POST /api/app/v1/reader/audiobook-generations/{generationId}/cancel
POST /api/app/v1/reader/audiobook-generations/{generationId}/retry
GET  /api/app/v1/reader/audiobook-generations/{generationId}/chapters
GET  /api/app/v1/reader/audiobook-generations/{generationId}/characters
PATCH /api/app/v1/reader/audiobook-generations/{generationId}/characters/{characterId}
PATCH /api/app/v1/reader/audiobook-generations/{generationId}/relationships/{relationshipId}
PATCH /api/app/v1/reader/audiobook-generations/{generationId}/voice-bindings/{bindingId}
PUT  /api/app/v1/reader/audiobook-generations/{generationId}/pronunciations/{term}
POST /api/app/v1/reader/audiobook-generations/{generationId}/chapters/{chapterIndex}/regenerate
GET  /api/app/v1/reader/audiobook-generations/{generationId}/playback-manifest
POST /api/app/v1/reader/audiobook-generations/{generationId}/chapters/{chapterIndex}/play-ticket
POST /api/app/v1/reader/audiobook-generations/{generationId}/exports
GET  /api/app/v1/reader/audiobook-generations/{generationId}/exports/{exportId}
POST /api/app/v1/reader/audiobook-generations/{generationId}/exports/{exportId}/download-ticket
GET  /api/app/v1/audiobook-export/tickets/{ticket}
```

`POST .../voice-revisions` 请求体只包含 `idempotencyKey`、`roleKey`（`NARRATOR` 或 `CHARACTER:<canonicalName>`）、`provider` 和 `voiceType`。源版本必须已完成；相同幂等键仅可重放完全相同的源版本、角色与目标音色。系统把角色实际归因出现的章节置为待合成，其他章节继续引用已校验资产；旁白变更会使全章节待合成。

`POST .../speaker-revisions` 请求体为 `idempotencyKey`、`chapterIndex`、`sequenceNumber`、`speakerKind` 和仅在 `CHARACTER` 时提供的 `speakerCanonicalName`。源版本必须完成，目标片段必须存在且置信度低于 `0.80`；角色名称必须属于该源版本，不能创建角色、变更原文区间或提交正文。页面显示的受限原文摘录只来自该版本冻结正文，服务端不会接受客户端或模型上传的摘录。系统复制父版本的冻结正文、人物、关系、音色绑定和未受影响音频，把修订片段标为 `MANUAL` 并只重合成其所属章节。相同幂等键只能重放相同的源版本、片段和人工选择。

`POST .../exports` 请求体为 `{ "idempotencyKey": "...", "format": "ZIP" }`，仅已完成的 generation 可创建。`GET` 只返回导出状态、章节数和完成后的大小，不返回章节 URI、归档 URI、存储根或下载令牌。只有 `COMPLETED` 导出可申请下载票据；票据绑定 `ownerId + generationId + exportId`，Gateway 代理归档流，因而 App 和系统下载器均不直接接触受管存储地址。

### 7.1 创建请求

```json
{
  "idempotencyKey": "client-generated-uuid",
  "mode": "FULL",
  "chapterIndexes": [],
  "narratorVoiceId": "catalog-id-or-default",
  "reviewMode": "AUTO_WHEN_CONFIDENT",
  "allowLongTermCache": true,
  "rightsConfirmed": true
}
```

响应返回 `generationId`、当前阶段、章节总数、审核要求和轮询地址；不返回 Scheduler ID。`rightsConfirmed` 只是用户声明，服务端仍按来源策略决定是否允许持久缓存或导出。

### 7.2 状态语义

| 状态 | App 表现 | 可操作 |
|---|---|---|
| `QUEUED/TEXT_EXTRACTING/ANALYZING` | 显示进度与取消 | 取消 |
| `REVIEW_PENDING` | 打开人物/音色审核 | 审核、仅旁白继续、取消 |
| `SYNTHESIZING` | 显示章节完成数 | 播放 READY 章节、取消剩余、重试失败章 |
| `CHAPTERS_READY` | 有声书播放器可用 | 连续播放、重生成单章、导出待封装 |
| `COMPLETED` | 全书可用 | 播放、下载、创建增量运行 |
| `PARTIAL_FAILED` | 标出失败章节和原因 | 重试失败章、下载已完成章节 |
| `CANCELLED/FAILED` | 显示恢复操作 | 重试或重新创建 |

## 8. 任务包与幂等

| 任务包 | 输入 | 输出 | 幂等键 |
|---|---|---|---|
| `reader_import_managed_ebook` | 已冻结媒体 ID、资产 ID、大小、摘要和 MIME | 已校验的受管 TXT/EPUB 与既有导入结果 | import request + media identity |
| `reader_extract_audiobook_text` | generationId | 章节文本摘要与定位映射 | generation + book hash + extraction version |
| `reader_analyze_audiobook_book` | generationId, chapter range | 人物、别名、关系、分段、证据 | generation + analysis version + chapter hash set |
| `reader_match_audiobook_voices` | generationId | 角色音色绑定与理由 | generation + voice policy version |
| `reader_synthesize_audiobook` | generationId | 当前 generation 中所有待合成章节的音频、timing、元数据 | generation + chapter hash set + voice plan hash + pronunciation version |
| `reader_export_audiobook` | exportId | 已校验章节 MP3 的 ZIP 导出资产 | exportId + 完成 generation 的章节摘要集合 |
| `reader_cleanup_audiobook_assets` | retention window | 删除候选和执行结果 | policy version + cutoff |

每个任务只携带 `generationId` 或有限章节索引，由 Reader Service 查询权威快照。执行器应采用“下载到临时目录 → 解码校验 → Storage 原子发布 → 内部回写”的顺序；无效和中断文件不得出现在可播放清单中。

## 9. 质量、可靠性、成本与合规

### 9.1 质量门槛

| 指标 | 首版阈值 |
|---|---:|
| EPUB/TXT 章节结构正确率 | ≥ 95% |
| 主要人物召回率（人工评测集） | ≥ 90% |
| 显式引语说话人准确率 | ≥ 90% |
| 全部对话说话人准确率 | ≥ 80%，低置信度回退不计为正确 |
| 锁定角色跨章音色一致率 | 100% |
| 章节音频解码与摘要校验通过率 | 100% |
| 单章失败重试后成功率 | ≥ 95% |
| 全书处理失败可恢复率 | ≥ 99% |

### 9.2 可观测性

记录：书籍摘要、generation ID、章节索引、任务阶段、字符数、token 数、音频时长、模型/音色版本、成本、重试类别和错误码。不得记录正文、供应商密钥、Cookie、Authorization、用户私有源 URL 或未脱敏角色文本。

仪表盘至少包含：排队时间、阶段耗时、章节完成率、LLM/TTS 错误率、供应商限流、缓存命中、每书成本、未播放资产比例、审核修正率和错误音色率。

### 9.3 成本控制

- 章节文本、分析结果和音频用 `contentHash + modelVersion + voicePlanHash` 去重。
- 限制每用户并发整书任务、每日字符配额和导出次数。首版以 `audiobook_quota_usage` 的 `(owner_id, usage_date)` 行锁原子累加，并以 `(generation_id, reservation_type)` 记录幂等预留；只为尚未就绪、实际需要 TTS 的冻结章节预留，复用章节和失败请求不计入。日预算耗尽返回 `READER_028`，同日不绕过、跨自然日才可重试。
- 优先生成旁白与主要角色；低频配角默认复用分类音色。
- 对重复章节、版本未变章节和已有效资产直接复用。
- 高成本模型只用于别名归并、模糊人物关系和低置信度说话人，不全量调用。

### 9.4 数据权利与安全

- 用户必须确认拥有生成、缓存和导出音频的权利。
- 网络书源默认只能会话/短期缓存；长期保存与导出要求来源策略明确允许。
- 受 DRM 内容拒绝处理，不尝试规避保护。
- 音色克隆必须验证授权并单独记录同意；首版关闭。
- 账户阅读数据删除需同时删除人物审核数据、读音词典、生成运行和无引用音频资产。

#### 9.4.1 资产保留、清理与账户删除

清理任务默认关闭，且不接受“按路径删除”或由 App 直接指定资产的请求。启用前必须由产品和合规负责人分别确认：已完成版本保留期、失败/取消版本保留期、ZIP 导出保留期、账户删除宽限期、法定保留/纠纷冻结规则。保留期未明确时只生成候选报告，不执行脱钩或物理删除。

```text
Reader 计算候选（已过保留期，且当前版本、修订版本、播放进度和导出均无引用）
  -> 记录 deletionId、策略版本、引用快照、资产摘要和 notBefore 时间
  -> 到期后在事务中再次核验引用，并把业务引用标为 DELETING
  -> Asset Registry 脱钩并证明全局引用数为 0
  -> Storage Gateway 以 deletionId 幂等删除受管对象
  -> Asset Registry 标记已销毁，Reader 写入完成审计；任一步失败保留审计并可重试
```

候选计算必须覆盖 `audiobook_generation_chapter`、`audiobook_audio_asset`、仍可下载的 `audiobook_export` 及其 Asset Registry artifact/bundle 引用；任意完成版本、正在播放会话、未到期导出或冻结标记存在时都不得删除。修订版本复用的章节资产须以全局引用而非单一 generation 判断，因此修改音色后删除旧修订记录也不能误删父版本仍在播放的 MP3。物理删除只允许 Storage Gateway 的受管对象删除接口执行，禁止 SQL 删除后遗留对象，也禁止执行器自行调用供应商或文件系统删除。

账户删除采用异步删除请求：立即停止新生成并撤销未使用播放/下载票据；宽限期结束后删除该账户的 generation、人物/关系/读音审核数据、播放进度和导出记录，再以同一删除审计流程回收无引用正文快照、音频与归档。删除报告只记录匿名 deletionId、策略版本、对象数量、摘要和各阶段时间，绝不记录正文、音频地址、票据或供应商凭据。被法定保留或纠纷冻结的数据返回可解释状态而不静默删除。

Google Play 自动旁白要求出版者拥有音频权利，且将生成、编辑、审核和发布作为独立步骤；MyTools 应采用同样的权利确认与审核边界。[Google Auto-Narrated Audiobooks Learning Center](https://support.google.com/books/partner/answer/14160094)

### 9.5 灰度与回退

- `AUDIOBOOK_GENERATION_ENABLED` 只控制会创建分析或合成工作的入口：整书生成、音色/说话人/读音修订以及失败恢复。关闭时返回稳定错误码 `READER_029`；已完成版本的介绍、播放清单、短期票据和导出仍可使用。
- `AUDIOBOOK_ALLOWED_OWNER_IDS` 为静态数值所有者白名单；空值表示在总开关开启时全量开放。先以验收账号灰度，观察成本、错误率、角色审核修正率和供应商限流，再逐批扩大。
- 首层回退是关闭 Reader 的新工作入口并按调度器策略停止新 Provider 调用，不删除冻结正文或已完成音频。按书源灰度、运行中任务的受控暂停和动态配置将作为下一阶段能力，不能用静态白名单冒充。

## 10. 测试与评测

### 10.1 测试集

- 10 本中文测试书：现代小说、古风小说、多人对话、非虚构、TXT、EPUB、复杂章节标题、英文夹杂各有覆盖。
- 人物评测集：至少 5 本、主角/配角/别名/称谓/同名角色均有标注。
- 引语评测集：至少 1,000 条，分显式姓名、代词、轮次交替、无提示四类。
- 读音词典回归集：人名、地名、数字、时间、外语、古诗词。

### 10.2 自动测试矩阵

| 类别 | 必测场景 |
|---|---|
| 解析 | EPUB/TXT/PDF 正文、乱码、标题、异常目录、重复页眉 |
| 任务 | 重复点击、租约失效、重试、取消、断点恢复、并发用户隔离 |
| 分析 | JSON 无效、越界证据、别名冲突、低置信度、人工锁定 |
| TTS | 限流、超时、下载中断、损坏音频、供应商任务轮询失败 |
| 音频 | 时长、编码、响度、章节顺序、播放清单摘要、封装元数据 |
| App | 进度恢复、章节先行播放、失败章节提示、权限与下载 URL 过期 |
| 安全 | 任意路径、越权 generation、密钥泄露、未授权导出、DRM 拒绝 |

## 11. 关键风险与决策门槛

| 风险 | 处理策略 | 继续条件 |
|---|---|---|
| TTS 长文本质量或成本不可接受 | 对比豆包与 MiniMax 的长文本和分段路径 | 选择满足音质、时长、成本和区域要求的主 Provider |
| 说话人归因误配 | 低置信度旁白回退、审核与锁定 | 错误角色音色率低于 3% 才默认启用自动多角色 |
| 别名/共指错误合并 | 章节局部角色、证据化全书归并、禁止无证据合并 | 人工审核可修正且重跑范围正确 |
| 全书任务耗时长 | 章节并行、检查点、阶段产物复用 | 失败只重跑缺失章节，不重算成功章节 |
| 音频不连续或音量不一致 | 统一编码、ffmpeg 验证与响度归一 | 章节试听通过后发布 |
| 来源和版权风险 | 权利确认、来源策略、DRM 拒绝 | 无权利确认禁止长期缓存与导出 |
