# 实施附录 A：数据、接口与状态契约

> 2026-10-03：字帖输入、快照、校验与纸面参数已由[新版字帖契约](../2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md)修订；新契约保留全局模式，替换固定列数和默认田字格。本文的公开 API、统计和 PDF 隔离契约继续适用。

本文件是 2026-10-02 制定的**待实现契约**。它不表示这些 `/public/v1` 接口、数据表或完整教材已存在。执行顺序与验收分别见 [主文档](implementation-handoff.md) 和 [验收矩阵](acceptance-matrix.md)。

## 1. 通用约定

- 前端访问同域 `/public/v1`，不使用 `/api/app/v1/pdf`。仅新公开门面接收匿名浏览器请求。
- ID 默认不透明 UUID 字符串；教材/工具可用稳定英文业务 ID；数据库 BIGINT owner 不返回浏览器，不放 JS number。
- 时间使用带 `Z` 的 ISO 8601 UTC，统计自然日为 `Asia/Shanghai`。字节为 integer byte，纸面为 mm，PDF 为 pt；页范围从 1 开始，产物和内部缩略图下标从 0 开始，转换位置百分比在 API 使用 0.05—0.95。
- JSON 响应采用一种信封；不能混用原站 `code=0000`、HTTP 200 内错误和新站错误。
- 服务端限制为准，前端仅做同规则的提前提示。拒绝未知字段，尤其 ownerId、任意 URL、磁盘路径、taskName 和 storageUri。

成功示例：

```json
{"code":"OK","data":{"id":"resource-uuid"},"requestId":"request-uuid"}
```

错误示例：

```json
{
  "code":"TOOLBOX_005",
  "messageKey":"task.limitExceeded",
  "details":{"limitName":"maxInputBytes","limit":31457280,"actual":31457281},
  "requestId":"request-uuid"
}
```

`details` 只含安全的字段路径、限额和索引；不得带文档、题目答案、SQL、堆栈、Cookie 或内部地址。中文提示由 locale key 解析。

| 错误码 | HTTP | 场景 | 前端处理 |
| --- | --- | --- | --- |
| TOOLBOX_001 | 422 | 字段、范围、类型无效 | 定位字段，保留输入。 |
| TOOLBOX_002 | 401 | 匿名 cookie 缺失/过期 | 说明本次会话已过期；用户可建立新会话，不跳登录页，不自动重传旧文件。 |
| TOOLBOX_003 | 404 | 不存在或不属于当前会话 | 相同响应，不泄漏其他会话资源。 |
| TOOLBOX_004 | 409 | 同幂等键不同请求、状态或 offset 冲突 | 显示可恢复状态；offset 冲突返回当前会话的 confirmedOffset。 |
| TOOLBOX_005 | 413 | 输入字节/文件/页/像素等硬限制 | 显示实际与上限。 |
| TOOLBOX_006 | 429 | 频率、并发、队列或额度限制 | 提供 Retry-After，不重新分配 operationId。 |
| TOOLBOX_007 | 410 | 本会话资源已到期 | 重新选择/处理；其他会话不能借 410 探测资源。 |
| TOOLBOX_008 | 503 | 依赖未启用/不可用 | 保留配置，手动重试或查询原任务。 |
| TOOLBOX_009 | 422 | 加密、表单等不支持输入 | 显示 reasonKey，禁止伪装成功。 |
| TOOLBOX_010 | 403 | 来源/CSRF 不通过 | 不执行变更；记录 requestId。 |
| TOOLBOX_011 | 422 | 缺字、题目超大、内容待校对 | 返回相关本地错误定位；不上传字帖内容来报错。 |
| TOOLBOX_012 | 503 | 上游结果校验或输出失败 | 显示失败，可查任务；不泄漏上游原始响应。 |

PDF 原始 `PDF_001...PDF_016` 必须按现有 `ErrorCode.java` 编写显式映射测试，不能统一包装成成功信封。无文字层、密码/签名/批注等使用稳定 reasonKey 保留可理解差异。

## 2. 工具注册表

```ts
type ToolCategory = 'pdf' | 'copybook' | 'words';
interface ToolDefinition {
  id: string;
  category: ToolCategory;
  route: string;
  labelKey: string;
  descriptionKey: string;
  icon: string;
  enabled: boolean;
  order: number;
}
```

| toolId | 分类 | 服务端 operation / 字帖模式 |
| --- | --- | --- |
| pdf.images | pdf | IMAGES_TO_PDF |
| pdf.text | pdf | TEXT_TO_PDF |
| pdf.markdown | pdf | MARKDOWN_TO_PDF |
| pdf.merge | pdf | PDF_MERGE |
| pdf.split | pdf | PDF_SPLIT |
| pdf.crop | pdf | PDF_PAGE_CUT |
| pdf.render | pdf | PDF_TO_IMAGES |
| pdf.extract | pdf | PDF_TO_TEXT |
| worksheet.chinese-dictation | copybook | chinese-dictation |
| worksheet.chinese-pinyin | copybook | chinese-pinyin |
| worksheet.english-dictation | words | english-dictation |
| worksheet.english-meaning | words | english-meaning |

一级目录点击记录 `entry_click`，category 必填、toolId 可空；进入明确子工具时可记录 toolId，但同一次用户点击只能发一个入口事件。success/print_request 必须指定 toolId。直达 URL 可以记录单独的 `tool_view`，但首期公开 KPI 不计为“点击”。

## 3. 教材数据契约

### 3.1 内容包结构

```text
content/catalog/requirements.json
  → 20 个必需学科/年级/册次槽位，不含伪造教材正文
content/catalog/published.json
  → 已发布 editionId/contentVersion/hash 的 manifest
content/textbooks/<editionId>/<contentVersion>/book.json
  → Edition + Unit + Lesson + Entry
content/sources/<sourceId>.json
  → 来源与核验记录，内部字段不直接对外分发
```

```ts
type Subject = 'chinese' | 'english';
type Semester = 'first' | 'second';
type ReviewStatus = 'draft' | 'reviewed' | 'published';
type CoverageStatus = 'missing' | 'partial' | 'complete';
type EntryForm = 'character' | 'word' | 'phrase';
type ListType = 'recognition' | 'writing' | 'vocabulary' | 'expression';

interface TextbookEdition {
  editionId: string;
  subject: Subject;
  grade: number;
  semester: Semester;
  regionCodes: string[];
  publisher: string;
  series: string;
  editionLabel: string;
  curriculumYear: number | null;
  isbn: string | null;
  sourceIds: string[];
  schemaVersion: 1;
  contentVersion: string;
  contentSha256: string;
  reviewStatus: ReviewStatus;
  coverageStatus: CoverageStatus;
  verifiedAt: string;
  publishedAt: string | null;
  availableEntryCount: number;
  expectedEntryCount: number | null;
  missingScopes: string[];
}
interface CurriculumUnit {
  unitId: string;
  editionId: string;
  order: number;
  title: string;
}
interface CurriculumLesson {
  lessonId: string;
  unitId: string;
  order: number;
  title: string;
}
interface EntrySourceRef {
  sourceId: string;
  locator: string;
  page: number | null;
}
interface PinyinSegment {
  grapheme: string;
  syllable: string;
}
interface CurriculumEntry {
  entryId: string;
  entryVersion: string;
  editionId: string;
  unitId: string;
  lessonId: string | null;
  order: number;
  form: EntryForm;
  listTypes: ListType[];
  text: string;
  pinyinDisplay: string | null;
  pinyinSegments: PinyinSegment[];
  meaning: string | null;
  partOfSpeech: string | null;
  senseId: string;
  context: string | null;
  promptContext: string | null;
  acceptableAnswers: string[];
  sourceRefs: EntrySourceRef[];
  reviewStatus: ReviewStatus;
  blockingIssues: string[];
}
```

这些字符串是数据，不是 Java 标识符。仓库若严格执行“代码非注释无中文”，JSON/TS 持久化内容可序列化为 Unicode 转义；网页运行时仍正常显示。不得为满足编码风格把教材中文改成英文。

### 3.2 校验和发布规则

1. 语文 grade 只能 1—6，英语只能 3—6；同一系列每 grade 必须分别标记 first/second，不能复用同一正文冒充两册。
2. `editionId` 是书的版本身份，`contentVersion` 是校对后发布版本；同 edition 的校正也发布新版本。所有来源引用和旧快照可追溯。
3. 单元/课/条目必须属于同一本书，按 order + 稳定 ID 排序；不能依赖 API 返回顺序或对象遍历顺序。
4. `form` 表示字/词/短语形态，`listTypes` 表示原教材清单类别。识字表不能自动归为写字表。
5. 汉字使用 Unicode 字素/码点的明确规则，不按 UTF-16 `length` 切格；拼音 segment 显式对齐。儿化/标点等需要明确 source 标注策略，不能简单按空格硬拆。
6. 正式内容没有 reviewed 记录、缺来源或存在 blockingIssues 时禁止发布；人工审核记录不得由脚本虚构。partial 可发布其已审核部分，但 manifest 清楚列缺口。
7. 内部 sources 记录 source URL/文献、accessBasis、版次核验、reviewer、reviewedAt 和差异报告；公开响应仅展示适合用户核对的来源摘要，不暴露私人联系方式或内部文件路径。
8. `context` 可能包含答案，只用于校对；student 投影仅允许审核过的 `promptContext`。检查提示语境是否泄露当前待填写答案。
9. 自定义条目使用 `origin='custom'` 和浏览器本地 ID；对正式条目的手工修改保存为本地 override，标记“已自定义”，不改公共包。
10. `verify-curriculum-coverage` 应失败于缺失/未核验的必需册次。允许 `--allow-partial` 输出开发报告，但发布完成门禁不得使用该参数。

### 3.3 教材 API

| 方法与路径 | 参数 | 返回 |
| --- | --- | --- |
| GET `/textbooks` | subject、grade、semester、region 可选 | editions、20 槽位 coverage、catalogVersion；只含可公开 metadata。 |
| GET `/textbooks/{editionId}/versions/{version}` | 精确不可变 version | edition、units、lessons、listTypes、counts、sha256。 |
| GET `/textbooks/{editionId}/versions/{version}/entries` | unitIds、lessonIds、listTypes、cursor、limit≤500 | entries、nextCursor、total、contentVersion。 |

规则：不同维度取交集，同一维度多值取并集；unitIds/lessonIds 均为该版合法 ID，否则 422，不静默忽略。cursor 必须绑定版本和过滤条件；不接受客户端拼出的任意磁盘路径。metadata 声明 availableEntryCount，与全分页取得数量可核对。客户端导出“全册”前必须加载完整当前范围。

不可变版本支持 ETag 和长期缓存；catalog 短缓存。未提供版本返回 404，网络失败返回真实失败；均不返回 sampleEntries。

## 4. 题单与排版契约

### 4.1 确定性选题

固定流程：过滤 → 可用性校验 → NFC/空白规范化 → 按策略去重并合并来源 → 稳定排序 → 抽取 → 题目排序 → 重复展开。

去重键包含规范化 text、读音/义项、senseId/语境；不无条件 lowerCase，不丢来源。随机算法命名如 `seeded-shuffle-v1`，与 seed、候选集版本一起固定。

均衡抽取采用确定性配额再无放回抽样：按 unit.order 排 k 个非空组，先各分 floor(N/k)，余数顺序分配；不足组释放配额，顺序分给仍有余量的组。主 seed 与 unitId 派生组 seed。容量 2/10/10，请求 9，预期分配 2/4/3；候选仅 5 项、请求 20，返回 5 项并提示不足。

### 4.2 快照

```ts
type WorksheetEntry = Omit<CurriculumEntry, 'editionId' | 'unitId' | 'reviewStatus'> & {
  origin: 'textbook' | 'custom' | 'override';
  editionId: string | null;
  unitId: string | null;
  reviewStatus: ReviewStatus | 'local-validated';
  sourceEntryRef: {
    editionId: string; contentVersion: string; entryId: string; entryVersion: string;
  } | null;
};
interface WorksheetSnapshot {
  worksheetId: string;
  operationId: string;
  revision: number;
  subject: Subject;
  mode: 'chinese-dictation' | 'chinese-pinyin' |
        'english-dictation' | 'english-meaning';
  contentRefs: Array<{ editionId: string; contentVersion: string; sha256: string }>;
  orderedEntries: ReadonlyArray<WorksheetEntry>;
  seed: string | null;
  selectionAlgorithm: string;
  ordering: 'textbook' | 'random' | 'manual';
  repetitions: number;
  title: string;
  layout: {
    paper: 'A4'; orientation: 'portrait'; marginMm: 15;
    gridMm: 10 | 12 | 14;
    chineseGrid: 'tian' | 'square';
    pinyinArea: 'four-lines' | 'underline';
    englishLineGapMm: 3;
    showNameBlank: boolean; showDateBlank: boolean; showClassBlank: boolean;
  };
  fontVersion: string;
  rendererVersion: string;
  contentHash: string;
  renderHash: string;
}
interface PdfArtifact {
  kind: 'student' | 'answer';
  worksheetId: string;
  revision: number;
  bytes: Uint8Array;
  sha256: string;
  pageCount: number;
  layoutHash: string;
  filename: string;
}
```

正式条目复制为 origin=textbook，保留 sourceEntryRef。自定义条目使用 origin=custom、空 editionId/unitId/lessonId/sourceEntryRef 和空 sourceRefs；本地校验后标 local-validated，不伪造教材审核。手工改正式条目为 origin=override，保留原 sourceEntryRef 与修改后的内容，界面明确已自定义。只有无 blockingIssues 的本地副本可进入快照。当前原型 `WordEntry` 可通过 adapter 兼容，逐步迁移引擎，不要求一次破坏所有函数签名。

`contentHash` 包含题目身份、顺序、模式和重复，不含标题/字号；`renderHash` 包含所有纸面参数和字体/渲染版本。hash 仅在浏览器用于一致性，不上报统计。操作身份是随机 UUID，不把题目内容 hash 当作公开统计标识。

同一内容在一次草稿/恢复会话内的反复预览、下载和重试沿用 operationId；明确“新建练习”或实际改题后有效生成才形成新 operation。不能用全局内容 hash 永久合并不同人的同一张练习。纯版式修改只增加 revision，不增加成功使用。

重新生成 PDF 可能含不同时间元信息，不能要求跨次生成二进制恒等；固定快照/算法应有一致布局语义。同一 PdfArtifact 用于预览和下载时，落盘字节 SHA256 必须一致。

### 4.3 模式投影与尺寸

| 模式 | student 可见 | student 必须删除 | 书写区域 |
| --- | --- | --- | --- |
| chinese-dictation | 拼音、必要安全语境 | 对应答案汉字、含答案 context | 每字空田字格/方格 |
| chinese-pinyin | 汉字/词 | 答案拼音 | 逐字空四线或空横线 |
| english-dictation | 中文义项/必要词性语境 | 英文答案/首字母/音标 | 四条线，间隔各 3 mm |
| english-meaning | 英文单词/短语 | 中文答案 | 空汉义横线 |

固定 A4 210×297 mm，`pt = mm × 72 / 25.4`，默认 15 mm 边距。学生与答案题号对应，页数允许不同。格子是矢量线；手机缩放不重排。

现有 200 条选择、每项 1—5 次、展开最多 500 题继续保留为首期上限；单字 4 列、词语 2 列、长题整行。标题上限 40 个 Unicode 码点，在输入时明确校验，不允许当前引擎静默截断；不得把错误藏在导出阶段。

首次/未指定格距时 grade 1—2 默认 14 mm、其余默认 12 mm；用户手动指定后设置 flag，不随其他变化覆盖。

## 5. 匿名会话与 PDF API

### 5.1 会话和持久化模型

```text
toolbox_guest
  owner_id BIGINT auto_increment PK（仅公开命名空间）
  guest_id UUID UNIQUE, token_hash BINARY(32) UNIQUE
  created_at, expires_at, revoked_at
toolbox_upload
  id UUID PK, owner_id FK, upstream_input_id
  status, declared_bytes, confirmed_offset, media_type, display_name
  sha256, page_count, idempotency_key, request_digest
  created_at, expires_at
  UNIQUE(owner_id, idempotency_key)
toolbox_task
  id UUID PK, owner_id FK, tool_id, status, phase, client_revision
  canonical_request_json, request_digest, idempotency_key
  upstream_plan_id, upstream_job_id
  queue_deadline, dispatched_at, execution_deadline, publish_deadline
  created_at, completed_at, expires_at, error_code
  UNIQUE(owner_id, idempotency_key)
toolbox_task_input
  task_id FK, upload_id FK, role(input|attachment), ordinal
  UNIQUE(task_id, role, ordinal)
toolbox_outbox
  id, aggregate_type, aggregate_id, action, dedupe_key UNIQUE
  state, attempts, next_attempt_at, lease_until, fencing_version
toolbox_cleanup
  id, managed_object_id, state, attempts, not_before, last_error_code
toolbox_execution_slot
  slot_id PK, task_id UNIQUE, lease_until, fencing_version
```

`owner_id` 的 FK 必须指公开 guest；新接口不提供修改归属的方法。upload 与 task 关联前做 owner 相等校验；清理、取消和生成重试使用行锁/乐观版本防并发。存储 URI 只在内部受控映射里，不能把它作为客户端提供的可删除路径。

敏感的 task 请求正文仅用于当前短期处理，日志不打印；过期清理后移除正文及用户文件元信息，保留最小状态/统计去重记录。

### 5.2 路由

| 方法 / 路径 | 请求 | 响应/规则 |
| --- | --- | --- |
| POST `/session` | 无正文或空对象 | 签发/返回当前有效 cookie，data={expiresAt,csrfToken}；不返回 cookie token/owner。 |
| GET `/tools` | 无会话要求 | 工具注册信息、execution=server、capabilities、limits、policyVersion。 |
| POST `/pdf/uploads` | Idempotency-Key；name,bytes,mediaType | 201 新建/200 重放；uploadId,status,confirmedOffset=0,chunkBytes,expiresAt。 |
| GET `/pdf/uploads/{id}` | cookie | 状态、confirmedOffset、已核验 MIME/页数/sha256、expiresAt。 |
| PUT `/pdf/uploads/{id}/content` | octet-stream；X-Upload-Offset | <=1 MiB 分块；返回 confirmedOffset。 |
| POST `/pdf/uploads/{id}/complete` | cookie+CSRF | 封存后执行检查；202/200 幂等；轮询至 READY/FAILED。 |
| POST `/pdf/tasks` | Idempotency-Key；下述任务 DTO | 202 新建/200 重放；taskId,status,phase,expiresAt。 |
| GET `/pdf/tasks/{id}` | cookie | 冻结任务状态、clientRevision、结果摘要、错误与过期时间。 |
| GET `/pdf/tasks` | cursor、limit≤20 | 仅当前会话任务；不提供其他用户历史。 |
| POST `/pdf/tasks/{id}/cancel` | cookie+CSRF | 202 取消请求/200 已终态；明确当前状态。 |
| GET `/pdf/tasks/{id}/artifacts/{index}/content` | cookie | 授权流式文件，Content-Disposition、nosniff、no-store。 |
| GET `/pdf/tasks/{id}/artifacts.zip` | cookie | 授权有界 ZIP；完成态多结果任务可用。 |

这不是透明转发器；任何没有列出的内部路径、Worker claim/complete、任意通配 URL 均不开放。`/session` 自身校验 Origin/Fetch-Metadata 并限速，避免跨站滥建；后续变更要求会话绑定 CSRF header。

### 5.3 任务 DTO 与内部映射

合并请求示例（UUID 用实际上传结果替换）：

```json
{
  "toolId":"pdf.merge",
  "inputIds":["upload-uuid-a","upload-uuid-b"],
  "options":{},
  "outputName":"merged.pdf",
  "clientRevision":3
}
```

文本请求以 `content:{format:'TEXT',text:'...'}` 替代 inputIds；Markdown 使用 format=MARKDOWN，可另有 attachmentIds。内联 content 与文本文件 inputIds 互斥；输入字符串以 UTF-8 字节判断 262144 上限。

`attachmentIds` 仅允许用于 Markdown；其他工具即使传空数组也返回 422，内部请求必须省略该字段。现有 PdfRules 会按字段是否存在进行拒绝，不能由通用 DTO 自动补 `attachmentIds: []`。加入非 Markdown 空数组和合法 Markdown 附件的契约测试。

门面解析 toolId → operation，public uploadId → 已归属验证的 upstream inputId，outputName → `{output:{name}}`。统一补齐明确默认参数，再生成 canonical JSON/digest；除白名单外不接受其他字段。

| operation | 允许 options | 默认与注意事项 |
| --- | --- | --- |
| IMAGES_TO_PDF | paperSize,orientation,fit,marginMm | A4、PORTRAIT、FIT、15；A4/A5；AUTO/PORTRAIT/LANDSCAPE；FIT/FILL。 |
| TEXT_TO_PDF | paperSize,pageNumbers,fontPreset,marginMm | A4、true、CJK_SANS、15；现有服务端没有 orientation 字段。 |
| MARKDOWN_TO_PDF | 同文本 | 现有表格/代码/受管图片语义；外部资源和原始 HTML 不放行。 |
| PDF_MERGE | 空对象 | inputIds 顺序即合并顺序，至少 2 个。 |
| PDF_SPLIT | ranges,pagesPerFile 或 groups | groups 与 ranges/pagesPerFile 互斥；默认 pagesPerFile=1。 |
| PDF_PAGE_CUT | axis,position,reverse,ranges | VERTICAL、0.5、false；HORIZONTAL 表示上下分割；未选页保持一次。 |
| PDF_TO_IMAGES | ranges,dpi,format | 全部、144、PNG；format 为 PNG/JPG；72—300 DPI。 |
| PDF_TO_TEXT | ranges | 默认全部；无文字层与读取权限问题有明确错误。 |

`ranges` 为 `[[1,3],[5,5]]`；不以字符串原样交给后台。`groups` 如 `[[[1,2]],[[3,3],[5,5]]]`。现有 PdfWorker 单组选择使用 TreeSet：每组展开后去重并按原页码升序输出，不按 ranges 数组录入顺序输出。groups 按外层数组顺序产生文件，组间重复页保留。以对照 fixture 固定这些语义，不能分别在前后端猜规则。

现有 `render_text.py` 接受 `![label](attachment:<inputId>)`（兼容 `attachment://<inputId>`），其 ID 必须在 runner 的已冻结 attachment map 中；这不是可访问外网的 URL。公开编辑器统一插入 `![label](attachment:<publicUploadId>)`。

门面必须用具有源位置的 Markdown 解析器定位图片目标，将这些 publicUploadId 在校验归属及 attachmentIds 白名单后转换为 upstream inputId，按源位置从后往前替换，仅改变图片引用目标，其他 Markdown 字节内容保持原语义。代码块/行内代码中的相似字符串不能被全局替换；未知附件、未 READY 附件、http/file/data 等图片目标返回明确错误。映射后的内容计入冻结内部请求，幂等 digest 则基于原公开请求，避免上游 ID 干扰重放。

首期用户选择 `.md/.txt` 文件时由浏览器按 UTF-8 读取到文本编辑区，验证大小后仍使用内联 content 提交；因此无须修改已上传 Markdown 文件中的引用。若后续开放后台读取文本上传，则必须补等效引用转换，不能绕过上述校验。附加契约测试覆盖表格、围栏代码、行内代码、转义括号、重复引用、丢失附件及外链，渲染结果与既有引擎对照。

### 5.4 状态机和恢复

上传：`UPLOADING → INSPECTING → READY | FAILED`；任一可过期。declared bytes 不等于实际到达 bytes，不到完整长度不能 complete。

任务：

```text
QUEUED → PROCESSING → PUBLISHING → SUCCEEDED
   │          │            │
   └──────────┴────────────┴→ FAILED / TIMED_OUT
QUEUED / PROCESSING → CANCEL_REQUESTED → CANCELLED
任何资源超过有效期 → EXPIRED（终态后异步物理清理）
```

映射：INPUT READY 后才可受理任务；门面尚未获取执行槽位时为 QUEUED，未创建 PLAN。获取槽位后为 PROCESSING，并以 phase 区分 PLAN 尚在上游等待与正在运行；PLAN READY 对应 PUBLISHING；创建 JOB 后确认 SUCCEEDED+合法 artifacts 才标 public SUCCEEDED。不得收到 PLAN READY 就计一次、收到 JOB 再计一次。

期限初值与起算点固定如下，均持久化且重启不重新计时：

1. `queue_deadline = min(created_at + 2h, session/input expiresAt)`。队列上限 20 是受理上限，不承诺所有任务一定在等待期限内执行；超时退出并提示重试。
2. 门面按 FIFO 获取全局持久执行槽位（初期 1 个，跨门面实例共享），**临近派发才创建 PLAN**。排队 30 分钟以上不能耗尽现有 PLAN 的 30 分钟 TTL。槽位租约过期须对账上游在途状态，不能直接派发另一个任务覆盖仍在运行的工作。
3. `dispatched_at` 为首次占用槽位时间，`execution_deadline = min(dispatched_at + 15min, session/input expiresAt)`，包含上游排队和转换重试。公开 PLAN 创建起仍为 30 分钟 TTL；Scheduler 单次执行超时初值 300 秒，重试不得跨过 execution_deadline。
4. PLAN READY 后 `publish_deadline = min(plan_ready_at + 5min, plan/session/input expiresAt)`。在该期限内以同一幂等键恢复发布；不能靠新建 PLAN 延长时间。执行和发布预算总计不超过 20 分钟，低于 PLAN TTL。
5. 到期先停止新调度/发布、使成功回调不可再被采纳，再取消并确认上游工作终止，最后释放槽位/引用并清理。队列、执行、发布超时均为 TIMED_OUT，资源自身有效期先到则为 EXPIRED；error reason 区分阶段。进程崩溃或取消未确认时保留租约恢复责任。

INPUT 检查同样进入有界调度：建议全局等待检查最多 60 个，公开暂存字节预算 5 GiB；达到任一预算拒绝新的上传预留（429），不能让未生成 public task 的上传检查绕过全局资源限制。检查与 PLAN 使用专用 Executor 的公平队列，检查自 complete 起最长 15 分钟，超时取消并清理；这些值由能力接口公布并在容量测试后校准。

幂等规则：

- 对同 owner 的同 endpoint，Idempotency-Key 8—128 位英文安全字符；规范化相同请求重放原资源；不同 payload 返回 409。
- 客户端在用户一次显式开始时分配 key；HTTP 响应未知、临时 429/503、刷新恢复均沿用并查询原任务，不盲目新建。已确认 FAILED/TIMED_OUT/CANCELLED 后，用户明确点击“重新处理”视为新操作，即使参数相同也分配新 operationId/key；旧任务保留终态。修改内容后显式重新发起同样使用新 key。EXPIRED 需重新准备仍有效的输入，不能复活旧资源。
- 门面先持久化任务+outbox，reconciler 使用 `public:<publicId>:plan` 与 `public:<publicId>:publish` 等稳定上游 key；网络丢响应后仍只得到一个上游资源。
- 重传分块 offset 小于 confirmedOffset 时必须证明相同位置字节相同，才能幂等成功；不同字节 409。超过 offset 不能产生空洞。
- complete 重复调用返回原检查状态；取消重复调用无额外副作用。正在运行的取消需等确认，不提前显示已取消。
- 对 lease/fencing 失效的旧 worker 结果拒绝采纳；终态不因迟到回调复活。两份服务进程同时 reconcile 不应发布两个 JOB。
- 一次操作只创建一个 PLAN 和一个 JOB；同 PLAN 可在执行预算内使用新的有效 fencing 重试计算，仅一个有效结果被采纳和发布。这里保证业务资源/发布幂等，不承诺进程崩溃前后物理计算恰好一次；JOB 不再次执行转换。

### 5.5 公开配额与生命周期建议初值

下面是拟采用的可配置初值，不是现有上线配置。必须在 `/tools` 返回实际值，并通过后端执行，调低时只影响新操作。

| 项目 | 初值 |
| --- | --- |
| 单文件 / 同次所有输入 | 30 MiB / 50 MiB；附件也计入合计 |
| 同任务输入文件数 | 30，含 Markdown 附件；附件本身不超过 20 |
| 单输入 PDF / 合并输出 | 100 页 / 100 页 |
| 拆分、分割输出总页数 / 最大产物数 | 200 / 100 |
| 转图片最大页数 / 单页像素 / 长边 | 30 / 3000 万 / 16000 |
| 字符输入 | 262144 UTF-8 bytes |
| 每上传块 / 普通 JSON body | 1 MiB / 1 MiB，含无 Content-Length 的流 |
| 每会话同时运行任务 / 全局 worker 并发 | 1 / 1，压测通过后可提高 |
| 队列上限 | 20，满时 429，不接受无限待处理 |
| 等待 / 执行 / 发布预算 | 2 小时 / 15 分钟 / 5 分钟；起算点见 5.4 |
| 输入检查队列 / 公开暂存总量 | 60 / 5 GiB；检查最长 15 分钟 |
| 每会话成功受理任务额度 | 30 / 24 小时；重放不重复扣，处理失败是否退额度记录为固定策略 |
| IP 新会话/任务受理防刷 | 建议 30 会话/小时、10 任务/分钟；允许按共享网络观测调节 |
| 会话有效期 | 创建起 24 小时 |
| 未封存上传 | 最多 1 小时，且不超过会话有效期 |
| 已封存输入 / 最终结果 | 最多 24 小时，且不超过会话及上游 expiresAt |
| 清理周期 / 到期后删除目标 | 每 5 分钟扫描，非活动资源 15 分钟内清除；失败记录与重试 |

门面须预留上传声明总量防止并发超额，再核对实际字节。成功创建任务的额度与 INPUT/PLAN 内部记录配额分开，不能把 30 张图片误判成 30 个运行任务。原私有“活动记录 10 个”和 PLAN 30/天规则改为可配置时保留默认值，公开 profile 配套调整后再发布相应上限。

现有 PLAN/JOB 可能引用同一产物。物理清理按所有合法引用与活动任务判断：没有未过期引用才可删除。会话提前到期时，先取消/终止该会话活动任务，再通过受信任的内部生命周期方法同步撤销其底层 INPUT/PLAN/JOB 引用；不能只把门面标过期、留下底层 24 小时引用，然后声称 15 分钟删除已通过。该内部方法只作用于公开实例，并同时做 owner 与资源集合校验，不向浏览器开放通用删除或路径参数。

访问先按最早有效期关闭，物理删除作为独立确认。共享文件在最后有效引用撤销且无活动工作后才进入清理目标时间；活动任务未停止、删除失败必须显示清理待处理并告警，不写成已完成。不得声称固有的 24 小时物理删除保证已经存在。

**必须新增删除实现：** 现有 Storage Gateway 没有可直接复用的受管对象删除接口。扩展 `service/storage-gateway-service` 的内部对象服务，新增仅公开实例启用的受控删除能力：接受已登记 objectId，固定 public root，验证根内解析结果、无活动读写租约及上游引用撤销凭证，使用墓碑/删除租约防止检查后重新发布。幂等删除真实对象及索引并返回已不存在确认；删除失败保留持久重试。内部路由不得暴露给浏览器，不接受任意磁盘路径或 storageUri，私有实例默认关闭。对路径穿越、符号链接逃逸、共享 PLAN/JOB 引用、并发下载/写入、重复删除和中断恢复增加集成测试。

## 6. 统计契约

### 6.1 事件

```ts
interface PublicUsageEvent {
  eventId: string;
  eventType: 'entry_click' | 'worksheet_success' | 'print_request';
  category: ToolCategory;
  toolId: string | null;
  occurredAt: string;
  operationId: string | null;
  schemaVersion: 1;
}
```

`entry_click` operationId 可空；worksheet_success、print_request 必须有 operationId 和准确 toolId。`pdf_success` 不在公开可上传枚举中，由后端 task SUCCEEDED 产生。禁止附加 filename、text、entries、answer、studentName、IP、cookie、内容 hash 等字段。

服务端按第 2 节注册表校验组合：worksheet_success 只接受四个 `worksheet.*`，category 必须与 toolId 匹配；所有事件的非空 toolId 必须已启用且属于给定 category。拒绝 `worksheet_success + pdf.merge`、不存在的工具、错配分类等请求，不能只禁止一个名为 pdf_success 的枚举值。

`POST /events` 接受 `{events:[...]}`，每批最多 50、body≤32 KiB；使用 cookie+CSRF，独立限速。返回每项 accepted/duplicate/rejected 和稳定 reason，不因一条失败重放已成功的全部事件。

客户端 occurredAt 仅接受过去 24 小时至未来 5 分钟；超出明确拒绝或由用户创建新操作，不静默改写原事件时间。服务端 PDF 时间以真实完成时间为准。统计会受客户端环境影响，不宣称能防止全部伪造或提供精确访客人数。

去重：

1. 唯一 event key=`(siteId,guestId,eventId)`，用于所有重放。
2. 成功语义 key=`(siteId,source,guestId,toolId,operationId)`；服务端 source=server 时 operationId 使用 public taskId，guestId 取服务端值。
3. print_request 每次真实调用系统打印产生新 eventId；同一次请求重试保留 eventId，因此主动第二次打印可多计一次，网络重发不会多计。
4. 重新打开已有题单、页面刷新、纯排版变化不创建新的 worksheet_success；显式新建或改题成功才产生新 operation。不能跨用户按内容永久去重。

### 6.2 表、事务和聚合

```text
toolbox_event
  id, site_id, guest_id, event_id, tool_id, category
  event_type, source, operation_id, occurred_at, received_at
  UNIQUE(site_id, guest_id, event_id)
toolbox_success_dedupe
  site_id, source, guest_id, tool_id, operation_id
  UNIQUE(site_id, source, guest_id, tool_id, operation_id)
toolbox_stat_outbox
  event_pk UNIQUE, state, attempts, lease_until
toolbox_stat_applied
  event_pk UNIQUE
toolbox_daily_stat
  local_date, category, tool_id_key, event_type, count
  UNIQUE(local_date, category, tool_id_key, event_type)
toolbox_stat_cursor
  site_id PK, complete_through, updated_at
```

入口 category 无明确 toolId 时使用固定 `tool_id_key='__category__'`，避免 MySQL UNIQUE 对 NULL 的语义导致重复桶。

写事件、成功 dedupe 和 stat outbox 在同一数据库事务；聚合事务先插入 stat_applied，再增量更新 daily_stat 并提交。重复执行因为唯一键不再累加。所有任务重试可重入，不能先计数后标已处理而留下重启重复窗口。

明细默认保留 90 天，按已聚合水位清理；dedupe 至少保留允许重试/资源生命周期窗口且不短于明细保留期。日聚合长期保留支持累计，不能从最近 2000 条或最近 30 天计算“累计”。删除匿名身份时不破坏已公开的非个人聚合。

### 6.3 统计读取

`GET /stats?days=7&category=all`；days 只允许 7/30，category 为 all/pdf/copybook/words。建议服务端缓存 5 分钟，响应包含：

```json
{
  "code":"OK",
  "data":{
    "timezone":"Asia/Shanghai",
    "generatedAt":"2026-10-02T04:00:00Z",
    "completeThrough":"2026-10-02T03:55:00Z",
    "partial":false,
    "today":"2026-10-02",
    "totals":{"todayClicks":0,"todaySuccess":0,"allTimeSuccess":0,"todayPrintRequests":0},
    "series":[{"date":"2026-10-02","clicks":0,"success":0,"isToday":true,"complete":true}],
    "ranking":[]
  },
  "requestId":"request-uuid"
}
```

示例仅展示一个日期，实际 series 必须包含按日期连续排序的 days 项。完整时间桶没有事件可补 0；聚合故障/未完整的桶用 `complete=false` 和 null 值/partial 提示，不能伪造成真实零。排名是所选周期、分类的 success，不混用全历史或点击。所有总数使用非负整数，溢出按 BIGINT 序列化策略处理，不让 JS 安全整数溢出。

首页跨北京时间零点或重新可见时刷新日期/数据；统计 503 显示局部失败，保持工具入口可用。本机统计可以作为开发辅助，生产全站数据与其明确分离，不自动合并。

## 7. 现有后端适配改动落点

| 现有路径 | 必须处理的点 |
| --- | --- |
| `service/pdf-service/src/main/java/com/yuyutian/mytools/pdf/service/PdfUpstream.java` | `X-Task-Service-Id` 从固定 pdf-service 改为受控配置，默认不变。 |
| 同目录 `PdfService.java` | taskName/businessType/幂等前缀可配置，默认 pdf_process/PDF_TOOL/pdf: 不变；public profile 的配额一致性；不改变私有转换语义。 |
| `service/pdf-service/src/main/java/com/yuyutian/mytools/pdf/service/PdfStore.java` | 保留 id+owner+kind 检查；不能新增绕过 owned 的公开快捷读取。 |
| `service/pdf-service/packages/pdf_process/1.0.1/scripts/main.py`、`render_text.py` | 只作复用/对照源，不原地编辑已发布版本。公开包保留沙箱、fencing、校验及 Markdown 功能，移除共享资产注册。 |
| `service/pdf-service/build_package.py` | 现有包名/版本被写死，不可仅换 output 路径作为公开包构建；新增公开构建脚本。 |
| `service/scripts/assemble_executor_packages.py` | 已发现 service 下的 package manifest；新增包必须正确声明 name/version/所需文件。 |
| `service/task-scheduler-service` 的 `TaskInstanceService` 及访问守卫 | 为 public-pdf-service 增加任务域校验；创建、get、cancel、results 都覆盖。新迁移按当前最新编号创建。 |
| `service/storage-gateway-service` 配置、内部对象服务与测试 | 当前单一 token 不是 root ACL；新实例只配置 public root。按 5.5 新增受控幂等删除，默认关闭；不是假定已有通用删除能力。 |

公开配置建议值：serviceId=`public-pdf-service`，taskName=`toolbox_pdf_process`，businessType=`PUBLIC_PDF_TOOL`，幂等前缀=`public-pdf:`。配置只在服务器受控环境中读取，不能由前端修改。

新包建议 `service/toolbox-service/packages/toolbox_pdf_process/1.0.0/`，新构建脚本 `service/toolbox-service/build_public_pdf_package.py`。manifest、目录、版本和 SHA256 一致；已发布版本拒绝覆盖。发布产物含 Worker JAR 和固定依赖，任务不能自动下载任意代码。

专用 Executor 进程绑定 public endpoint/token，任务参数只含 workId；label=`toolbox.pdf.processing=enabled`，cluster=`toolbox-pdf-processing`，初期并发 1。共享 Scheduler 但不共享只有单套 PDF_SERVICE_URL 的 Executor 进程。

## 8. 合同测试与变更管理

- 前后端共同使用 `contracts/fixtures/` 的合法/非法请求样本，每次字段修改同时改 DTO、校验、fixture 和文档。
- 大小、页数、范围索引、期限、同键冲突均有服务端测试，不只 TypeScript 类型。
- API 响应对照测试明确断言没有 ownerId、storageUri、assetId、内部 URL/token。
- 版本升级先跑已有 25 项和新增回归；字帖字体库升级保留 CFF 修复用例，不能为了升级移除失败测试。
- 新服务与原站共享代码的默认行为回归必须通过；仅公开 profile 使用新参数。
- 改变本附录默认值时记录原因、能力接口变化和受影响用例。不得在实现中引入另一套隐含契约。

## 9. 环境配置与运行边界

实施时新增无凭据的 `.env.example` / YAML 样例和配置校验；下表名称是新门面的约定，复用服务的 Spring 属性由适配配置显式映射，不假定这些环境变量已被旧代码支持。

| 配置 | 放置位置 | 规则 |
| --- | --- | --- |
| VITE_APP_MODE | 前端构建 | development / production；正式构建删除默认演示开关。 |
| VITE_PDF_EXECUTION | 前端构建 | local / server；正式目标 server。 |
| TOOLBOX_PUBLIC_ORIGIN | 新门面 | 精确 https origin，用于同源/CSRF；不能 `*`。 |
| TOOLBOX_DATASOURCE_URL/USERNAME/PASSWORD | 新门面私有环境 | 独立 schema/最小权限；密码不入 Git，不输出完整连接串。 |
| TOOLBOX_CATALOG_PATH | 新门面 | 只读的已发布教材根；启动时校验 manifest/hash。 |
| PUBLIC_PDF_SERVICE_URL/TOKEN | 新门面 | 唯一受控内部基址及独立 token；不跟随重定向。 |
| PUBLIC_PDF_WORKER_TOKEN | 专用 Executor | 仅 public runner，离线计算子进程不继承。 |
| PUBLIC_STORAGE_URL/TOKEN/ROOT | 专用 runner/清理服务 | 独立公开实例；ROOT 是已配置枚举名，不是用户路径。 |
| PUBLIC_PDF_SCHEDULER_TOKEN | public PDF 实例 | 对应 public-pdf-service；不能操作私有任务域。 |
| TOOLBOX_PDF_ENABLED | 新门面 | 可独立关闭新任务，不停字帖及教材读取。 |
| TOOLBOX_STATS_ENABLED | 新门面 | 关闭时首页显示不可用，不回落演示数。 |

同一台机器多实例的端口、schema、上传目录和日志目录由部署配置指定并检查冲突。前端开发可使用 Vite `/public` 反代到本机新门面，保持 cookie/same-origin 行为；生产禁用开发代理与演示路由。

测试环境至少包括：真实 MySQL（验证事务/唯一键/行锁）、新门面、public PDF、具备 public 领域守卫的 Scheduler、专用 Executor、public Storage。单元测试可以用 H2/替身服务，但不能替代这个集成环境。测试所需服务和版本固定在新增的测试编排文件中，不使用生产连接地址。

性能与资源门禁先采用以下目标，并在验收记录填写设备、浏览器、网络和输入。它们是设计目标，不是已测量结果：

- 首页不预加载 16 MiB 字帖字体或 PDF worker；在桌面 20 Mbps/50 ms RTT、冷缓存下主要内容 3.5 秒内可交互。统计慢请求不阻塞入口。
- 20 条字帖、字体已缓存、参考桌面设备下修改至新预览目标 2 秒内；冷字体加载 100 ms 内出现明确加载状态，30 秒超时后有重试，不无限转圈。
- 手机 500 题压力测试记录峰值内存和耗时，必须不崩溃、可取消、其他导航保持可响应；如需分批渲染/Worker，应在 M04 完成。不可自动截成较少题目来满足性能。
- PDF 转换不规定所有文件统一秒数；展示真实阶段。固定测试集记录排队/处理 P50/P95，超时预算按操作与服务配置明确给出；达到预算必须终态处理和释放资源。
- 上传/下载采用固定大小缓冲，内存不随文件字节数线性增长；worker 设 OS 级 CPU/内存/临时磁盘上限，记录具体值与超限退出结果。不能仅在 UI 写限制而让服务无限分配。

若目标不满足，先提交测量数据与原因并优化；确需调整时更新契约、能力及验收记录，不能悄悄把失败标为通过。
