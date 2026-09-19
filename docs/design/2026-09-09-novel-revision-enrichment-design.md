# 小说受约束改写与“加料”方案设计

日期：2026-09-09

状态：背景调研；产品入口与实施范围已由[书架章节改编细化设计](./2026-09-09-shelf-chapter-adaptation-design.md)收敛，实施以新文档为准
目标系统：MyTools Reader Service

> 本文保留完整调研和未来演进候选。首版入口、接口、表结构、状态机、第三方 Provider 和验收标准均以细化设计为准，不应从本文直接拆实施任务。

## 1. 结论

该能力应被定义为“受约束的编辑提案器”，而不是一次自由生成：

> 在不改变既有人物、事实、事件顺序、因果、世界规则、知识边界、伏笔状态和剧情结果的前提下，按用户可选意图改善表达并补充低风险细节；证据不足时少改、拒绝或要求补充上下文。

推荐流水线为：

```text
冻结输入与上下文
  -> 提取有证据的剧情约束
  -> 生成 Edit Contract
  -> 规划局部编辑操作
  -> 生成一个或多个候选 Patch
  -> 确定性检查
  -> 多维语义一致性检查
  -> 有界局部修复
  -> 展示差异与证据
  -> 用户提交接受/手工编辑 decision
  -> 对组合结果完整重校验
  -> 发布不可变新版本并标记旧 Canon 失效
  -> 重建并确认 Canon 增量
```

四个不可妥协的原则：

1. 原文和已确认设定是证据源，模型摘要不是证据源。
2. 用户意图是软目标，不得覆盖硬剧情约束。
3. 生成结果是提案，绝不直接覆盖原文；只有用户接受的内容才能更新长期记忆。
4. 只输入孤立片段时只能承诺片段一致，提供前后文最多扩展到场景一致；只有完成项目级正文索引并具备已确认、未失效的故事记忆，才能承诺全书范围检查。

不建议直接复制某个开源项目。现有项目分别解决了规划、记忆、检索或检查问题，但没有一个完整满足“输入片段 + 可选意图 + 严格保留剧情 + 可审计加料”的生产要求。

## 2. 产品边界

### 2.1 输入

请求体中的 `source` 使用互斥结构：

- `LOCAL`：`text` 必填，可附 `contextBefore`、`contextAfter`；冻结时先创建私有 `novel_project` 和不可变源版本，再开始生成。
- `BOOK`：`projectId`、`documentVersionId`、`chapterIndex`、Unicode 码点选区和 `expectedChapterSha256` 必填；已有 `ebookAssetId` 必须先经幂等导入接口解析为项目及初始正文版本，改写请求不再直接引用可变资产。
- `intent`：大概意图，可选，例如“更紧张”“增加生活气”“加强两人的疏离感”。

可选上下文：

- 前后相邻段落或完整场景。
- 整本小说，或经项目导入接口转换的 MyTools `ebookAssetId`。
- 人物卡、世界规则、大纲、时间线、已知结局和未回收伏笔。
- 用户锁定的句子、事实、称谓、对白信息和风格样本。

### 2.2 来源与实际约束覆盖

| `source.type` | 输入方式 | 能力承诺 | 限制 |
|---|---|---|---|
| `LOCAL` | 直接粘贴 `text`，可附前后文 | 保留片段及邻接上下文中的明确事实和事件 | 不宣称全书一致；默认禁止新增持久事实 |
| `BOOK` | `projectId + documentVersionId + chapterIndex + codepoint range + expected hash` | 从项目的不可变正文版本安全选区；若项目级 Canon 已就绪，可同时检查书级时间线和伏笔 | `ebookAssetId` 先导入项目；首版只有场景覆盖，全书承诺必须等待整书索引和 Canon 就绪 |

服务端另行计算 `contextCoverage = FRAGMENT | SCENE | BOOK`，客户端必须显示该值。`source.type=BOOK` 只表示文本来自受管电子书，不自动等于 `contextCoverage=BOOK`；不得因模型支持超长上下文就标成全局安全，也不得静默截断上下文。

选区协议必须唯一且版本化：

- `chapterIndex` 为从 0 开始的当前 `documentVersionId` 目录序号。
- 正文投影使用 `NOVEL_TEXT_NFC_LF_V1`：解析器产出的 Unicode 文本去除开头 BOM，将 CRLF/CR 统一为 LF，执行 NFC 规范化，保留其余空白，再以无 BOM UTF-8 保存；解析器版本与规范化版本都进入版本摘要。
- `startCodepoint`/`endCodepoint` 是规范化后 Unicode 码点的从 0 开始、左闭右开区间 `[start, end)`，换行计一个码点；不得使用 JavaScript/Java 的 UTF-16 code unit 偏移。
- `expectedChapterSha256` 是规范化章节 UTF-8 精确字节的 SHA-256 小写十六进制。服务端验证 `0 <= start < end <= codePointCount` 并重算摘要；任一不符返回版本冲突，不做猜测或自动纠偏。
- Reader 的章节读取 API 同时返回 `documentVersionId`、目录版本、规范化版本、码点总数和摘要，客户端必须基于这一响应产生选区。

### 2.3 操作模式

请求先区分顶层操作 `REWRITE` 与 `ENRICH`，再选择细化模式。`REWRITE` 允许在锚点内做受限替换；`ENRICH` 以既有剧情节拍之间的插入为主，只允许为自然衔接做事实不变的局部替换。二者都不能默认改剧情。

合法组合如下，服务端必须按此校验 DTO、计划和 Patch：

| `operation` | 合法 `mode` |
|---|---|
| `REWRITE` | `POLISH`、`ACTION_PSYCHOLOGY`、`DIALOGUE`、`STYLE_ALIGN` |
| `ENRICH` | `ATMOSPHERE`、`ACTION_PSYCHOLOGY`、`DIALOGUE`、`EXPAND_SCENE` |

| 模式 | 允许变化 | 默认禁止变化 |
|---|---|---|
| `POLISH` | 措辞、节奏、句式、病句 | 新事实、新动作结果、新动机 |
| `ATMOSPHERE` | 感官、环境、光影、声音、停顿 | 新人物、关键物件、线索和世界规则 |
| `ACTION_PSYCHOLOGY` | 微动作、即时感受、符合既有人设的内心活动 | 新目标、新知识、新关系和新决定 |
| `DIALOGUE` | 语气、潜台词、说话动作和节奏 | 改变发言者、信息量、承诺或对话结果 |
| `EXPAND_SCENE` | 依据明确场景目标补充过渡和小节拍 | 未授权的新剧情节点；仅在上下文充足时启用 |
| `STYLE_ALIGN` | 向用户确认的风格样本靠拢 | 改变事件、事实和人物立场 |

普通模式永远不隐式支持“改剧情”。若以后增加剧情重构，应成为独立高级功能，明确列出将被废止或改写的旧事实并要求再次确认。

模式必须经过资格检查，不能只因用户点选就启用：

| 模式 | 最低证据 | 不满足时 |
|---|---|---|
| `POLISH` / `ATMOSPHERE` | 目标片段及清晰的编辑边界 | 边界不完整时阻断；其余可保守执行 |
| `ACTION_PSYCHOLOGY` | 当前聚焦人物可识别，且有其性格/目标或当前状态证据 | 降级为微动作，禁止补充确定性动机 |
| `DIALOGUE` | 说话者及原对白信息边界可识别 | 只调整标点和节奏，无法归属时阻断内容改写 |
| `EXPAND_SCENE` | 场景入口、出口状态和下一剧情锚点明确 | 降级为 `ATMOSPHERE`，并提示补充前后文 |
| `STYLE_ALIGN` | 用户确认的风格样本或书内足量同视角样本 | 阻断并提示选择样本，不模仿未授权作者身份 |

### 2.4 改动风险等级

- `L0`：换词、句式、节奏。
- `L1`：感官、环境、微动作等瞬时纹理。
- `L2`：内心活动、潜台词、短对白，但不改变人物状态。
- `L3`：动作链或因果过渡，可能影响场景状态，必须重点审阅。
- `L4`：新人、新物、新能力、新关系、新线索、新知识、新结果，普通改写禁止。

例如，原文“张宁推门进去”，意图为“更紧张”，可以增加门把的触感和呼吸停顿；不能自行增加“屋内躺着一具尸体”。

## 3. 调研结论

### 3.1 论文与评测

| 来源 | 有效启示 | 不直接照搬的原因 |
|---|---|---|
| [Re3](https://aclanthology.org/2022.emnlp-main.296/) / [代码](https://github.com/yangkevin2/emnlp22-re3-story-generation) | 先生成结构化计划，再反复注入计划和故事状态，候选重排后做事实修订；人工评价中的整体连贯性和主题相关性优于单次生成 | 面向从头生成长故事，技术栈和模型较旧，不提供生产级版本、权限和审计 |
| [DOC](https://aclanthology.org/2023.acl-long.190/) / [V2 代码](https://github.com/facebookresearch/doc-storygen-v2) | 分层详细大纲比模糊 premise 更能约束后续正文；支持“先规划、再写作” | 重点仍是故事生成，不是对已有文本做最小 Patch；控制过强也会损失创意 |
| [DOME](https://aclanthology.org/2025.naacl-long.63/) | 用带章节位置的实体、动作和对象记录检索历史，适合构建时序事件账本 | 需要扩展人物知识、伏笔、叙述顺序和事实模态，不能只用普通三元组 |
| [ConStory-Bench](https://aclanthology.org/2026.findings-acl.410/) / [代码](https://github.com/Picrew/ConStory-Bench) | 将一致性拆为人物、事实细节、叙事风格、时间线与情节、世界观五类，共 19 个子类；检查结果绑定精确位置，适用时给出矛盾证据对 | 论文中 o4-mini 在 1,000 个英语合成注错样本上的精确率为 0.884、召回率为 0.550；只能说明该检查范式会漏检，不能外推为中文局部改写的生产漏检率 |
| [Narrative World Model](https://arxiv.org/abs/2607.05577) | 叙事类型化的时态状态图、证据区间和查询相关的混合检索，能处理“谁在何时知道什么”“事件发生顺序与揭示顺序”“伏笔建立与回收”等多跳问题 | 截至本设计日期仍是预印本；论文验证的是记忆问答，明确把受其约束的生成留作后续工作 |
| [WebNovelBench](https://aclanthology.org/2026.findings-eacl.94/) | 提供基于 4,000 多部中文网文的 synopsis-to-story 多维评测范式，支持本项目对中文小说能力单独校准的工程判断 | 不直接衡量最小改写和原文保真，也没有证明通用榜单必然失效 |
| [LongWriter](https://github.com/THUDM/LongWriter) | 可作为长输出模型和 plan-then-write 的候选基线 | “能写一万字”不等于“不会破坏既有剧情”，不能替代 Canon 和校验器 |

### 3.2 GitHub 与产品实现

| 来源 | 借鉴内容 | 风险或局限 |
|---|---|---|
| [Novel Studio AI](https://github.com/YfengJ/novel-studio-ai) | `Plan -> Context Pack -> Draft -> Continuity Check -> Accept -> Memory`；草稿不更新 Canon，只有接受内容才写人物状态、关系、时间线和检索记忆 | 新项目、规模小，适合作为交互与数据流参考，不宜作为生产依赖 |
| [Bookwright](https://github.com/jmorenobl/bookwright) | 区分已确定事实和待定事实；确定性规则与 LLM 审校分离 | 偏 CLI 和文件工作流，缺少成熟检索与服务化能力 |
| [Graphiti](https://github.com/getzep/graphiti) | 事实具有有效时间、失效时间和来源 episode，适合关系、位置和状态的历史查询 | 通用时态图不是小说 Canon；引入图数据库会增加首版复杂度 |
| [SillyTavern World Info](https://docs.sillytavern.app/usage/core-concepts/worldinfo/) | 分作用域、按关键词或相似度激活设定，并管理上下文预算 | 官方也不保证模型遵守 World Info；只适合做上下文装配参考 |
| [LightRAG](https://github.com/HKUDS/LightRAG) | 关键词、向量、图与 rerank 的混合召回及来源追踪 | 通用实体合并可能错误折叠人物在不同时期的状态；MVP 不必引入完整系统 |
| [NovelAI Lorebook](https://docs.novelai.net/en/text/lorebook/) | 只激活当前人物、地点和规则相关的条目，并为上下文条目分配 Token 预算 | 召回只是提示，不是约束；仍需服务端硬校验 |
| [Sudowrite Rewrite](https://docs.sudowrite.com/using-sudowrite/1ow1qkGqof9rtcyGnrWUBS/rewrite/9hkeezeUsCiUCG4dRdEqjS) 与 [Story Bible](https://docs.sudowrite.com/using-sudowrite/1ow1qkGqof9rtcyGnrWUBS/what-is-story-bible/jmWepHcQdJetNrE991fjJC) | 把 Rephrase、Describe、Show-not-tell、Inner Conflict 等模式分开，提供多个候选并高亮改动 | 官方文档显示长段 Rewrite 不一定使用 Synopsis，说明“有 Story Bible”不代表每次编辑都真正受其约束 |

综合结论：规划、检索、生成、校验、版本和 Canon 提交必须是独立阶段；不能用一个大提示词或一个自动总分代替。

## 4. 约束模型

### 4.1 优先级

从高到低：

1. 安全、法律、内容与隐私规则。
2. 本次编辑范围、锁定文本和用户明确的“不可改变”项。
3. 用户已确认的故事正史。
4. 已接受正文中的明确事实及场景入口/出口状态。
5. 当前操作模式的能力边界。
6. 用户可选意图和风格目标。
7. 通用写作质量偏好。

当意图与更高层约束冲突时，返回冲突证据并降级或停止。系统不得自行选择 retcon。

### 4.2 Canon 最小结构

不能只存人物卡和章节摘要。至少需要：

- `Entity`：人物、地点、物品、组织和概念，含别名。
- `CharacterState`：存活、位置、伤病、能力、目标、关系、情绪弧线及有效区间。
- `KnowledgeState`：谁在何时、通过何事件知道什么。
- `NarrativeEvent`：参与者、地点、故事时间、叙述位置、前置条件、结果和因果边。
- `WorldRule`：物理、魔法、科技、社会规范、地理和旅行限制。
- `PlotThread`：伏笔/承诺的建立、隐藏答案、允许揭示点、预期回收点和状态。
- `StyleProfile`：POV、聚焦人物、时态、语域、句长、对白比例、常用与禁用表达。
- `EvidenceSpan`：来源版本、章节、Unicode 码点范围、原文摘要和置信度。

每条断言不能只存一个混合“模态”，而要拆开：

```text
assertionSource: NARRATOR | CHARACTER | DOCUMENT | USER
holder: entityId | null
epistemicStatus: KNOWN | BELIEVED | RUMORED | UNKNOWN
truthStatus: CONFIRMED | CLAIMED | DISPUTED | FALSE | UNKNOWN
narrativeFrame: PRIMARY | FLASHBACK | DREAM | HYPOTHETICAL
storyTime: interval | unknown
narrationTime: document position
```

否则“角色撒谎”“梦境”“不可靠叙述”会被误当成客观事实，`FLASHBACK` 也会被错误当成真假状态。系统既要保留“角色说过这句话”这一叙事事件，也不能因此把话中命题升级为客观事实。

### 4.3 事实等级

- `CONFIRMED`：用户明确确认，最高优先级。
- `EXPLICIT`：正文直接陈述，并带证据区间。
- `INFERRED`：系统从正文推断，可被用户修正，不作为绝对硬约束。
- `PROPOSED`：仅存在于候选文本，未接受前不得进入 Canon。
- `PENDING`：正文尚未确定；未知不等于否定，生成器只能在允许范围内补充。

### 4.4 Edit Contract

规划器必须先输出结构化编辑合同，示例：

```json
{
  "sourceType": "BOOK",
  "contextCoverage": "SCENE",
  "operation": "ENRICH",
  "mode": "ATMOSPHERE",
  "strength": "MEDIUM",
  "intent": "增强压迫感，但不要提前暴露凶手",
  "allowedChanges": ["WORDING", "SENSORY_DETAIL", "MICRO_ACTION"],
  "forbiddenChanges": [
    "NEW_NAMED_ENTITY",
    "EVENT_REORDER",
    "KNOWLEDGE_CHANGE",
    "RELATIONSHIP_CHANGE",
    "PLOT_OUTCOME_CHANGE",
    "FORESHADOWING_REVEAL"
  ],
  "targetExpansionRatio": 1.25,
  "maximumExpansionRatio": 1.40,
  "protectedAnchorIds": ["s-003", "s-006"],
  "requiredEventIds": ["evt-019"],
  "openQuestions": []
}
```

扩写比例只是初始产品参数，必须按中文金标集校准。未明确要求时建议目标在原文的 `1.15-1.30` 倍以内；超过 `1.40` 倍或出现新的剧情节点时不自动通过。

## 5. 端到端流程

### 5.1 冻结请求

1. 校验 owner、权利声明、长度、编码和请求幂等键；自我声明只是一项策略输入，不等于平台已经完成权属核验。
2. 把正文、意图和可选上下文写入受管存储；数据库只保存受管 URI、字节数和 SHA-256。
3. 首次导入 `ebookAssetId` 时，通用正文投影任务从 `ebook_catalog_entry.resource_ref` 及目录偏移读取章节，生成初始 `novel_document_version`、章节正文、Unicode 码点索引和 SHA-256。`BOOK` 改写只冻结明确的 `projectId + documentVersionId`，不重新解析原资产。该任务可抽取并复用现有 `reader_extract_audiobook_text` 的格式解析逻辑，但不得依赖或创建有声书 generation。
4. 将输入拆成稳定 Block/Sentence，保存 ID、码点范围和摘要。
5. Scheduler 参数只传 `requestId`、`ownerId` 等稳定标识，不传正文、意图、模型密钥或物理路径。

等待生成期间原文若发生变化，接受时必须触发乐观锁冲突。MVP 只提供基于最新版重新生成并保留旧候选副本，禁止覆盖新正文；三方合并留到后续阶段。

### 5.2 约束提取

提取目标片段及相关历史中的：

- 人物、别名、关系、位置、状态和知识。
- 明确事件、因果、顺序和场景入口/出口状态。
- 数字、时间、颜色、称谓、关键物件和原文锁定句。
- POV、聚焦人物、时态和风格特征。
- 与当前实体或事件相关的开放伏笔。

每个事实必须能回到 `EvidenceSpan`。无证据内容只能标为 `INFERRED` 或 `PENDING`。

生成前返回轻量 `Constraint Preview`，展示“必须保留、允许增加、缺失上下文和模式降级”。用户可修正 `INFERRED`、增加锁定项或补充上下文；`CONFIRMED`/`EXPLICIT` 有充分证据且不存在歧义时可走快速模式，只有冲突或高风险歧义才暂停生成。

### 5.3 Context Pack

`Context Pack` 按以下优先级装配：

1. 目标文本、前后边界和稳定句子 ID。
2. 用户锁定项、`CONFIRMED` 事实，以及经过来源、认知、真假和叙事框架判定后可作为不变量的 `EXPLICIT` 事实；对白、梦境或不可靠叙述中的直接陈述不能自动硬化为客观事实。
3. 当前场景的入口状态、必经事件和出口状态。
4. 与本次人物、地点、物品、时间点和伏笔直接相关的历史证据。
5. 用户确认的风格样本与抽象 `StyleProfile`。
6. 用户意图、模式和改动幅度。

检索采用“实体/时间过滤 + 关键词/BM25 + 向量召回 + 小规模 rerank + 一跳叙事关系扩展”。所有结果必须按 owner、project 和版本隔离，并带来源。MVP 可以先用 MySQL 中的结构化索引和关键词检索；验证收益后再增加向量库或图数据库。

不得把整本小说不加区分地塞入提示词。长窗口是容量，不是可靠性保证；相关事实被无关正文淹没时仍会漏用。

### 5.4 编辑规划

规划器只返回 JSON，不写正文。每个操作包含：

```text
operationId
type: KEEP | REPLACE | INSERT_BEFORE | INSERT_AFTER
anchorBlockId
anchorSha256
purpose
allowedRiskLevel
requiredFactIds
forbiddenFactIds
expectedStateDelta
```

- `ENRICH` 在 MVP 中优先使用 `INSERT_BEFORE`/`INSERT_AFTER`；为避免机械堆句，可对锚点内的连接句做有限 `REPLACE`，但替换不得改变事实、事件或状态。
- `POLISH`/`REWRITE` 可使用有限 `REPLACE`，但必须建立源 Block 到目标 Block 的映射。
- 默认禁用 `DELETE`、无锚点替换、跨 Block 重排和整段自由返回。
- 若计划本身产生 `L4` 变化、遗漏必经事件或触碰保护伏笔，生成前即拒绝。

### 5.5 候选生成

正文模型只能看到经过裁剪的 `Context Pack` 和已经通过检查的 `Edit Contract`/编辑计划：

- 不提供工具、数据库凭据或其他用户内容。
- 小说正文作为不可信数据放在结构化、明确分隔的数据字段中；分隔只能降低注入风险，不能保证模型遵守。完整边界还包括生成模型无工具/秘密、输出不被执行、跨租户隔离和提示泄漏探针。
- 正文输出使用普通文本；规划、约束和校验报告使用严格 JSON Schema。
- 初版生成一个默认候选；需要比较时再生成“保守版”和“较丰富版”。
- 模型只能提交计划中的 Patch。服务端按 Block ID 和摘要应用 Patch 并重建候选文本。

### 5.6 四层校验

第一层，确定性检查：

- Anchor、摘要和编辑范围是否合法。
- 锁定句、称谓、数字和必要事件是否保留。
- 是否出现未知人物、重复操作、越界删除、重排或超出长度预算。
- 输出是否完整、有效 UTF-8、未截断，JSON 是否符合 Schema。

第二层，候选状态差分：

- 从候选抽取 `CandidateStateDelta`。
- 将新人物、关系、知识、位置、能力、关键物件、因果、伏笔状态与当前 Canon 比对。
- 瞬时纹理可以通过；未经授权的持久事实不得自动通过。

第三层，证据式一致性检查：

- 分别检查人物、事实细节、时间线与情节、世界规则、叙事视角与风格。
- 每个问题必须返回候选证据、冲突事实证据、码点位置、错误类型、置信度和建议修复范围。
- 检查器允许 `ABSTAIN`；引用不存在或无法映射时视为无效检查，而不是通过。

第四层，质量与意图检查：

- 原有语义和事件覆盖。
- 用户意图完成度。
- 文风、节奏、重复、对白有效性和“加料”价值。
- 与原文盲评时是否确实更好；“不改”是合法成功结果。

任何总分都不能覆盖硬约束失败。生成器与检查器使用不同提示模板只能实现角色分离，不能消除同源自偏好。高风险放行必须依赖确定性规则和在独立金标上校准的检查器；仍需语义裁决时使用不同模型家族复核，以降低同源偏差，并保留人工抽检。

### 5.7 有界修复

- 只修复被证据定位的 Patch，不重新生成整段。
- 最多两轮，MVP 可先限制为一轮。
- 每轮修复后完整重跑硬检查和受影响的语义检查。
- 重检后若确认存在硬剧情违规、越界写入、跨租户泄漏、权利策略失败或不可放行的内容安全问题，转为 `REJECTED`，不得人工绕过。
- 证据不足、语义检查器分歧或疑似误报转为 `REVIEW_REQUIRED`；补充/修正上下文并重新校验前不能接受。
- 纯质量问题继续有限修复，仍无净收益则转为 `NO_CHANGE_RECOMMENDED`。只有重校验无阻断项的候选才能进入 `READY_FOR_REVIEW`。

### 5.8 用户审阅与提交

界面展示：

- 原文与候选的语义 Diff，而不只是字符 Diff。
- 每项修改的分类：表达、瞬时细节、潜在持久事实、删除、重排。
- 本次约束覆盖范围和未覆盖风险。
- 每条告警的候选片段、冲突规则、原文证据和修复建议。
- 逐操作、逐段、全部接受、拒绝和手工编辑；句内级选择留到高级阶段。

存在硬约束违规时禁用“全部接受”。逐项接受以 `operationId` 为最小单位，服务端必须对用户选中的操作组合及手工编辑后的全文重新校验，不能直接拼接已分别通过的 Patch。

接受后创建不可变正文版本，正文立即成为最高权威；当前 Canon 标记为 `STALE`，增量重建并确认完成前，后续请求不得返回 `contextCoverage=BOOK`。它仍可从 `source.type=BOOK` 安全选区，但覆盖必须降级为 `SCENE`。`PROPOSED` 状态增量仍需独立确认后才能升级为 Canon。草稿、失败候选和用户拒绝内容不得污染长期检索记忆。

## 6. MyTools 落地架构

### 6.1 服务边界

推荐将领域能力放在 `service/reader-service`，因为它已经拥有电子书资产、目录、Reader schema 和长任务编排基础；模型调用继续在受限 Executor 脚本中完成。当前 Reader 尚没有通用的可编辑小说正文版本和 Canon 模型，本方案必须显式新增，不能把有声书 generation 当成小说版本。

不使用根工程的通用 Copilot 透传接口作为小说业务层。当前 Copilot 只验证 OpenAI 兼容请求外形后转发客户端构造的 messages，不具备剧情约束、版本、证据和审计能力。

建议新增：

```text
service/reader-service/
  src/main/java/com/yuyutian/mytools/reader/
    controller/NovelRewriteController.java
    controller/NovelRewriteInternalController.java
    model/NovelRewrite*.java
    repository/NovelRewriteRepository.java
    service/NovelRewriteService.java
    service/NarrativeConstraintService.java
  packages/reader_project_novel_context/1.0.0/
    manifest.yaml
    schemas/result.schema.json
    scripts/main.py
    tests/
  packages/reader_rewrite_novel_text/1.0.0/
    manifest.yaml
    schemas/result.schema.json
    scripts/main.py
    tests/
  db/migrations/V31__create_novel_rewrite.sql

service/task-scheduler-service/
  src/main/resources/db/migration/V140__seed_reader_novel_rewrite_task.sql

service/mytools-gateway/
  src/main/java/com/yuyutian/mytools/gateway/
    controller/NovelRewriteGatewayController.java
    service/NovelRewriteGatewayClient.java
    model/NovelRewriteGatewayModels.java
```

迁移版本号 `V31`/`V140` 以当前仓库基线为准；真正实施前必须重新扫描各服务最新迁移号，避免与并行开发冲突。任务服务当前仍属于 sidecar/migration 工作流，生产启用还需补齐部署、内部令牌、功能开关和回滚验证。

先复用现有有声书整书分析中的做法：冻结章节、复核 SHA-256、分块、Unicode 码点证据、结构化 JSON、有限重试、模型/规则版本溯源、原子回写和金标质量门禁。不要直接复用 `audiobook_*` 表，因为二者生命周期和业务语义不同；只有 `ebookAssetId`、`bookContentSha256`、分析模型版本和规则版本完全匹配时，才可把已有角色/关系分析只读投影为低优先级 `INFERRED` 提示，绝不能直接升级为 Canon。

### 6.2 组件职责

```text
Gateway
  身份与 owner 隔离、输入上限、功能开关、公开 DTO

Reader Service
  请求/版本/Canon 权威状态、冻结上下文、状态机、接受操作

Task Scheduler
  幂等任务、取消、deadline、重试、检查点和执行 fencing

Task Executor Package
  约束抽取、Context Pack、规划、模型调用、候选校验和有界修复

Storage Gateway
  原文、上下文快照、候选正文和报告的受管对象

Model Adapter
  PlanningModel / WritingModel / CriticModel / EmbeddingModel / RerankModel
```

`Model Adapter` 属于 Executor 包内的端口/适配层，不是新增可公网访问的服务。Reader 只传稳定请求 ID，模型密钥和供应商配置留在 Executor 运行环境；新任务包必须通过现有 executor environment contract gate 和 package assembly 校验。

模型适配层应按能力声明路由，而不是把供应商写死在业务代码中：

```text
provider
modelId
pinnedSnapshot
contextWindow
maximumOutput
supportsJsonSchema
supportsPromptCache
retentionClass
dataRegion
```

规划和抽取使用低温度结构化模型；正文使用中文文风更好的模型；检查使用低温度且能稳定引用证据的模型。是否使用云端、国内地域或私有模型，由项目的数据保留和质量要求决定。

当前根工程 Copilot 的默认本地 4B 视觉模型尚未经过中文小说金标验证，应只视为开发候选，不能直接作为生产基线。模型选型必须通过项目自己的中文金标盲测，而不能根据通用榜单决定。

### 6.3 数据表

`novel_project`

- owner、标题、来源 `ebookAssetId`、当前正文版本、当前 Canon 版本和 Canon 状态。
- 导入电子书时建立项目，但不改写原 `ebook_asset`；所有新版本沿项目 lineage 追加。

`novel_document_version`

- `projectId`、父版本、完整正文 URI、目录/Block manifest URI、SHA-256、字节数和创建来源。
- decision 重校验通过后先在 Storage Gateway 写入不可变候选对象；`COMMITTING` 阶段以项目当前版本 compare-and-set 创建新版本，通过 outbox 在 Asset Registry 登记血缘并重建目录与 Block manifest。全部完成后才进入 `ACCEPTED`；原电子书资产永不就地覆盖。

`novel_context_block`

- `documentVersionId`、章节、稳定 Block ID、Unicode 码点范围、摘要和 manifest Schema 版本。
- Patch 锚点只能指向冻结版本中的 Block；版本不匹配即拒绝应用。

`novel_context_snapshot`

- owner、来源类型、`projectId`、`documentVersionId`、章节和选区；原始 `ebookAssetId` 只保留在 project/version 血缘中。
- 原书、章节、选区和上下文的 SHA-256。
- 冻结上下文 URI、字节数、覆盖范围和 Canon 版本。

`novel_rewrite_request`

- `id`、`ownerId`、`contextSnapshotId`、`idempotencyKey`。
- operation、mode、strength、intent URI/摘要。
- 权利声明基础、范围、作品版本、期限和可选证据 URI；声明与平台核验状态分开保存。
- status、currentStage、taskInstanceId、错误码和时间戳。
- model、prompt、constraint rule 和 verifier 版本。

`novel_constraint`

- 类型、事实等级、硬/软级别、结构化/free text 值。
- `projectId`、有效正文/Canon 版本区间、有效时间、证据章节/码点范围、摘要、置信度和锁定状态。

`novel_entity` / `novel_entity_alias`

- 按 `projectId` 隔离的规范实体和别名，不按名字直接合并身份。

`novel_event` / `novel_event_relation`

- `projectId`、有效版本区间、故事时间、叙述位置、参与者、地点、因果和前后关系。

`novel_plot_thread`

- `projectId`、有效版本区间、建立、预期回收、允许揭示、状态和 spoiler 可见性。

`novel_rewrite_plan`

- Edit Contract、Patch 计划、输入和输出 Schema 版本。

`novel_rewrite_candidate`

- 候选 URI/摘要、Patch JSON、状态差分、校验报告、风险等级和生成溯源。

`novel_rewrite_decision`

- 用户逐项接受、拒绝、手工修改及最终版本；只追加不覆盖。

正文与完整报告存 Storage Gateway，MySQL 保存状态、摘要、可查询的 Canon 与短证据。生产日志不得记录原文、意图、候选正文、模型密钥或存储地址。

### 6.4 状态机

```text
PENDING
  -> CONTEXT_BUILDING
  -> CONSTRAINT_PREVIEW
  -> PLANNING
  -> GENERATING
  -> VALIDATING
  -> REPAIRING
  -> READY_FOR_REVIEW -> DECISION_VALIDATING -> COMMITTING -> ACCEPTED
READY_FOR_REVIEW -> DECLINED
DECISION_VALIDATING -> READY_FOR_REVIEW | SOURCE_CONFLICT
VALIDATING -> NO_CHANGE_RECOMMENDED

任意非终态 -> CANCELLING -> CANCELLED
任意执行阶段 -> FAILED
VALIDATING/REPAIRING -> REVIEW_REQUIRED
任意校验阶段 -> REJECTED
```

`DECLINED` 表示用户不采用，`REJECTED` 表示已确认的策略/安全/硬约束违规。用户提交局部选择或手工文本后先进入 `DECISION_VALIDATING`；失败的 decision 追加保存并返回 `READY_FOR_REVIEW`，候选本身不被覆盖。源版本变化转 `SOURCE_CONFLICT`。重校验通过后才进入 `COMMITTING`：以 compare-and-set 推进项目当前版本，Storage/Asset Registry 通过 outbox 幂等收敛；全部完成才标记 `ACCEPTED`。

状态重放必须幂等；迟到结果通过任务 fencing、请求版本、源版本和候选摘要校验拒绝。

### 6.5 API 草案

App 只访问 Gateway 的 `/api/app/v1/reader/novel-rewrites/**`；Gateway 注入可信 owner 后，携内部令牌调用 Reader Service 的 `/api/v1/novel-rewrites/**`。Reader 路径不直接暴露给客户端。

创建请求：

```http
POST /api/app/v1/reader/novel-rewrites
```

```json
{
  "idempotencyKey": "opaque-key",
  "source": {
    "type": "BOOK",
    "projectId": "uuid",
    "documentVersionId": "uuid",
    "chapterIndex": 12,
    "startCodepoint": 1200,
    "endCodepoint": 2480,
    "expectedChapterSha256": "hex"
  },
  "operation": "ENRICH",
  "mode": "ATMOSPHERE",
  "strength": "MEDIUM",
  "intent": "增强压迫感，但不要提前暴露凶手",
  "lockedRanges": [],
  "rightsAttestation": {
    "attested": true,
    "basis": "AUTHOR",
    "workVersion": "source-sha256",
    "allowedScope": "PRIVATE_REVISION",
    "territory": "CN",
    "expiresAt": null,
    "evidenceRef": null
  }
}
```

`LOCAL` 使用 `source = {type: "LOCAL", text, contextBefore?, contextAfter?}`。`idempotencyKey` 沿用现有 Reader DTO 风格，数据库建立 `(owner_id, idempotency_key)` 唯一约束。客户端不能选择 owner、供应商/模型、物理路径或内部任务标识。

```text
POST /api/app/v1/reader/novel-projects/imports
  -> POST /api/v1/novel-projects/imports

App/Gateway: /api/app/v1/reader/novel-rewrites/**
Reader:      /api/v1/novel-rewrites/**

POST {base}
GET  {base}/{id}
GET  {base}/{id}/candidates
POST {base}/{id}/cancel
POST {base}/{id}/retry
POST {base}/{id}/candidates/{candidateId}/accept
POST {base}/{id}/candidates/{candidateId}/reject
```

项目导入接口接收 `idempotencyKey + ebookAssetId + expectedAssetSha256`，异步完成正文投影后返回 `projectId + documentVersionId`。改写请求只接受返回的不可变版本 ID；同一资产后续导入新版本必须创建新的 `novel_document_version`，不得移动旧版本的含义。

接受接口请求体至少包含：

```json
{
  "idempotencyKey": "opaque-decision-key",
  "selectedOperationIds": ["op-001", "op-003"],
  "userEditedText": null,
  "expectedRequestVersion": 7,
  "expectedDocumentVersionId": "uuid",
  "expectedSourceSha256": "hex",
  "expectedCandidateSha256": "hex"
}
```

若 `userEditedText` 非空，服务端将其视为新的候选正文并完整重跑范围、状态和一致性检查。接受接口返回 `202 DECISION_VALIDATING`，客户端轮询请求状态；只有进入 `ACCEPTED` 才表示新正文版本已经发布。`reject` 记录用户 decision 并转为 `DECLINED`，不使用策略性 `REJECTED` 状态。

内部执行接口：

```text
GET  /api/internal/v1/novel-rewrites/{id}/input
POST /api/internal/v1/novel-rewrites/{id}/result
```

成功结果携 task instance、fencing token、请求版本和结果摘要原子回写。失败以现有 `TASK_ERROR_FILE`/Scheduler 终态为唯一权威，由 Reader reconciler 收敛；Executor 不直接调用第二套 `/fail` 接口，避免迟到失败覆盖已成功结果。

## 7. 错误与阻断规则

建议在现有 Reader `ErrorCode` 中从当前末尾顺延错误码；按本设计时的仓库基线为：

```text
READER_030 NOVEL_REWRITE_INVALID_INPUT
READER_031 NOVEL_REWRITE_CONTEXT_INSUFFICIENT
READER_032 NOVEL_REWRITE_SOURCE_VERSION_CONFLICT
READER_033 NOVEL_REWRITE_INTENT_CONFLICT
READER_034 NOVEL_REWRITE_CONSTRAINT_VIOLATION
READER_035 NOVEL_REWRITE_MODEL_UNAVAILABLE
READER_036 NOVEL_REWRITE_INVALID_MODEL_OUTPUT
READER_037 NOVEL_REWRITE_NOT_FOUND
READER_038 NOVEL_REWRITE_RIGHTS_ATTESTATION_REQUIRED
```

实施时若 Reader 已新增错误码，应继续顺延；`REVIEW_REQUIRED` 是正常生命周期状态，不定义为错误码。

`CONTEXT_INSUFFICIENT` 必须返回缺少的上下文类型和可降级模式，`INTENT_CONFLICT` 必须返回冲突约束及可删除/改写的意图片段；二者都是可恢复结果。系统判断安全改写没有净收益时返回 `NO_CHANGE_RECOMMENDED`，而不是伪造一个变化或把它记为失败。

确认后必须转为 `REJECTED`，不得由客户端强行接受：

- 身份、存活、位置、能力边界、知识边界或人物关系发生未授权变化。
- 事件顺序形成环、出现负时长、同时异地或无解释的死后行动。
- 世界规则、关键因果、场景出口状态被改变。
- 本次编辑范围内受保护且要求出现的剧情节拍/伏笔被提前揭示、遗漏或改变回收状态。
- 新增剧情节点、关键物件、秘密、伤亡或因果解释。
- 锁定内容或编辑范围被越界修改。
- 原文/证据摘要失配、模型输出截断或检查证据无法定位。
- 跨租户数据、权利策略或不可放行内容安全检查失败。

可自动修复一次；证据仍不确定时转 `REVIEW_REQUIRED`：

- 单处 POV 越界、局部称谓不一致。
- 扩写过多、重复、风格偏移或意图完成度不足。
- `INFERRED` 事实冲突或多个检查器结论不一致。

## 8. 质量评测与上线门禁

### 8.1 金标集

首轮建议建立 300-500 条拥有处理权的中文探索/回归样本，覆盖玄幻、都市、悬疑、言情、科幻和历史；该规模用于发现问题，不自动满足统计上线门槛。每条标注：

- 必须保留的事件、事实和句子。
- 允许增加的瞬时细节。
- 禁止产生的持久事实和剧情变化。
- 人物、别名、称谓、关系、能力、知识和物品归属。
- 时间、地点、因果、伏笔、POV、叙事者和时态。
- 本次意图和人工偏好的改写方向。

对抗样本应包含：角色撒谎、梦境、闪回、不可靠叙述、同名人物、省略主语、尊称变化、修炼等级、相对时间、跨章伏笔、输入中的伪指令和生成期间源文本变化。

### 8.2 指标

- `outOfRangeMutationRate`：编辑范围外修改率。
- `lockedContentPreservation`：锁定内容保留率。
- `sourceEventCoverage`：原有必要事件及其顺序的保留率。
- `stateInvariantViolationRate`：身份、位置、知识、关系等不变量的违规率。
- `unsupportedStateDeltaRate`：无原文证据或用户授权的状态增量率。
- `criticalContradictionEscapeRate`：严重矛盾被自动放行的比例。
- `consistencyErrorDensity`：每万中文字符的一致性错误数。
- `intentAdherence`：意图完成度。
- `humanPreferenceWinRate`：对值得改写样本，候选相对原文的盲评胜率。
- `acceptanceRate`、`partialAcceptanceRate`、`undoRate`、`postAcceptEditDistance`。
- 首次获得可用候选的延迟、Token/字符成本和修复率。

### 8.3 初始门槛

以下是内测起点，不是行业标准，需由金标校准。评测前必须预注册：独立正例的单位、总体与关键严重类别、去重/聚类规则、双侧 95% Wilson 区间算法和每层最小正例数；同时报告严重违规召回率下界与逃逸率上界。若要求零漏检时双侧 95% Wilson 召回率下界达到 0.95，单个统计层级至少需要约 73 个独立严重违规正例，因此应按类别做样本量设计，不能只看 300-500 条总数。

- 编辑范围外修改为零。
- 锁定内容保持 100%。
- 严重剧情/事实违规不得自动放行。
- 未经授权的 `L4` 持久事实为零。
- 各预注册关键严重类别的召回率 95% 置信下界至少为 0.95、精确率至少为 0.90，否则不得自动放行。
- 在人工预先判定“值得优化”的样本上，候选优于原文的比例至少 60%。
- 用户意图得分至少 4/5 的案例达到 80%。

人工偏好和意图评分应规定盲评人数、平票处理、评审一致性和置信区间，不能用单一 LLM Judge 作为上线质量证明。模型快照、Prompt、规则或抽取 Schema 任一变化后必须重跑门禁，并为指标退化预设回滚条件。

自动评审必须定期与人工复核对账。ConStory-Checker 的公开结果中，o4-mini 在 1,000 个英语合成注错诊断样本上的召回率为 0.550，即漏掉约 45%；这不能外推成中文局部改写的生产漏检率，但足以说明“模型判定通过”不能替代硬规则、独立校准和用户确认。

## 9. 隐私、安全与合规

- 使用 `rightsAttestation` 记录用户声明的权利基础、作品版本、许可范围、地域、期限和可选证据引用；它不是平台权属结论。不明来源的第三方文本阻断实质改写。中国著作权法规定了改编权以及演绎作品权利行使边界，权利问题并非只在公开或商用发布时才出现；公开、分发或商用只是需要升级法务核验的节点：[中国人大网](https://www.npc.gov.cn/c2/c30834/202011/t20201119_308796.html)。
- 正文、短证据、意图、候选、Embedding、模型缓存和备份均按敏感内容处理并加密；提供删除与留存策略，删除要传播到向量索引、缓存、备份清除队列和供应商侧。Embedding 不是匿名化文本，不能脱离 owner/project 隔离。
- 云模型的训练使用、日志保留、零数据保留和数据地域必须逐供应商、逐端点核验并留存验证记录。
- 所有 Canon 和检索按 owner/project/version 过滤，做跨租户泄漏测试。
- 小说正文可能包含“忽略此前指令”等叙事情节，必须始终作为不可信数据而非指令；生成模型不持有工具和秘密。OWASP 明确指出 RAG 或微调不能彻底消除提示注入风险：[OWASP LLM01](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)。
- 不在 Scheduler 参数、指标、异常或日志中记录正文、意图、候选、模型密钥、内部令牌和受管存储地址。
- 对输入、意图和输出分别执行内容安全检查，但要识别小说语境，避免关键词误杀；不得在普通“加料”中擅自升级危险、色情或自伤强度。
- 面向境内公众上线前，应复核生成式 AI 服务、个人信息及生成合成内容标识相关要求；本节仅给出工程检查项，不构成法律意见。

## 10. 分阶段实施

### Phase 0：离线验证

- 先在 `specs/` 新建独立功能规格，固化需求、计划、数据模型和 API 合同；本设计不是可直接编码的完整 SpecKit 输入。
- 建立 JSON Schema、提示版本和首批 300-500 条中文探索/回归金标，并按预注册统计口径补足各关键严重类别样本。
- 用一个强模型和一个成本型模型跑单次生成、计划生成和本方案三组基线。
- 验证 Patch 协议、证据偏移、严重冲突门禁和人工盲评。

退出条件：约束流水线相对单次生成显著降低严重违规，且人工认为改写确有价值。

### Phase 1：单段/单场景 MVP

- 支持 `LOCAL`，以及从已有电子书安全选区的 `BOOK` 来源；Phase 1 的 `BOOK` 仅承诺冻结文本和场景级上下文，响应中的 `contextCoverage` 仍为 `SCENE`，不宣称全书一致。
- 实现通用电子书正文投影、`novel_project`、不可变 `novel_document_version` 和稳定 Block manifest；不依赖有声书 generation。
- 支持六种模式、轻/中/丰富三档、一个候选。
- 实现冻结请求、Constraint Preview、Edit Contract、Block Patch、确定性检查、一次有界修复和语义 Diff。
- 支持按 `operationId` 接受/拒绝，组合后重新校验；结果只可接受为新版本，不自动把模型推断升级为书级 Canon。
- 接受新版本后将旧 Canon 标记为 `STALE`；重建完成前禁止返回 `contextCoverage=BOOK`，但仍可从 `source.type=BOOK` 选区并以 `SCENE` 覆盖运行。
- 默认关闭，通过 Gateway feature flag 和 owner allowlist 灰度。

### Phase 2：项目级故事记忆

- 引入人物状态、知识边界、事件时间线、世界规则和伏笔账本。
- 支持关键词/向量混合检索和 rerank。
- 只对接受版本抽取 Canon 增量，并要求证据与人工确认。
- 支持跨章一致性检查和章节级增量重建；只有 Canon 状态为 `READY` 且覆盖门槛达标时，`contextCoverage` 才可升级为 `BOOK`。

### Phase 3：规模化与高级编辑

- 多候选并行、不同模型路由、Prompt Cache 和成本预算。
- 受影响章节分析、三方合并、跨候选重组和句内级接受。
- 在明确授权和影响分析下探索独立的剧情重构模式。
- 只有积累足够高质量接受/拒绝数据后，才评估 LoRA、偏好优化或专用检查器；微调不替代 Canon。

## 11. 推荐实施顺序

1. 先冻结 API、Edit Contract、Patch 和校验报告 Schema。
2. 再建立金标集与单次改写基线，证明问题和门禁有效。
3. 实现 `LOCAL` 保守模式及确定性 Patch，不先上图数据库。
4. 接入现有 Reader 冻结正文、Storage Gateway 和 Scheduler/Executor。
5. 增加证据式语义检查和用户 Diff/接受流程。
6. 以真实错误分布决定是否引入向量检索、时态图和第二模型，而不是预先堆叠基础设施。

本方案的最小可行差异化不是“写得更长”，而是：约束对用户可见、每个冲突有证据、改动可局部接受、未接受内容永不成为正史。
