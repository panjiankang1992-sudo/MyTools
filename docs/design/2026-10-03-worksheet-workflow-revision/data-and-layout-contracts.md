# 字帖改造附录：输入、状态、处理与纸面契约

日期：2026-10-03；版本：2.0。本文件为待实现契约，配套 [修改主方案](modification-plan.md) 和 [验收用例](acceptance.md)。最新要求为**先输入、后选择全局题型**，与旧提案中的按字表自动分配/混排没有兼容义务。

## 1. 系统边界

教材选择继续读取既有 `GET /public/v1/textbooks`、指定版本及 entries 接口；统计继续使用现有四个 worksheet toolId。图片 OCR、纯文本解析、读音候选、字帖排版均在浏览器进行，不需要新增公开图片上传 API，不借用 MyTools 鉴权接口。

本次允许新增前端依赖与同域静态 OCR 资产；不新增登录、外部翻译服务、PDF OCR、混合题型或公开词库写入。任何新增字段应同步类型、数据校验与测试，不通过 TypeScript `as` 绕过契约。

## 2. 原文、来源与解析模型

保留现有四值 `WorksheetMode`。新增下列概念，具体文件拆分可调整，但行为不变：

```ts
type SourceKind = 'textbook' | 'custom' | 'image' | 'legacy';
type ParsePolicy = 'whitespace' | 'one-per-line';
type CheckState = 'missing' | 'suggested' | 'confirmed';

interface InputSourceRef {
  sourceId: string;
  kind: SourceKind;
  editionId: string | null;
  contentVersion: string | null;
  entryId: string | null;
  imageId: string | null;
  localOverride: boolean;
}
interface InputToken {
  tokenId: string;
  text: string;
  rawStart: number;
  rawEnd: number;
  sourceRefs: InputSourceRef[];
  pinyinSegments: Array<{ grapheme: string; syllable: string }>;
  pinyinState: CheckState;
  meaning: string | null;
  meaningState: CheckState;
  senseId: string | null;
  acceptableAnswers: string[];
  issues: Array<{ code: string; field: string; severity: 'error' | 'review' }>;
}
interface InputImportRecord {
  sourceId: string;
  kind: SourceKind;
  appliedAtRevision: number;
  label: string;
  sourceVersion: string | null;
}
interface WorksheetInputDocument {
  schemaVersion: 2;
  documentId: string;
  subject: 'chinese' | 'english';
  revision: number;
  rawText: string;
  parsePolicy: ParsePolicy;
  tokens: InputToken[];
  imports: InputImportRecord[];
}
interface WorksheetProcessingOptions {
  mode: WorksheetMode | null;
  chineseUnit: 'word' | 'character';
  duplicatePolicy: 'merge-equivalent' | 'keep-occurrences';
  ordering: 'input' | 'random';
  selection: 'all' | 'random-count' | 'balanced-unit';
  requestedCount: number | null;
  seed: string | null;
  repetitions: 1 | 2 | 3 | 4 | 5;
}
```

约束：

- `rawText` 是唯一原文，textarea 与它双向绑定。tokens/语音候选只是派生数据；不能以 tokens.join 覆盖用户原始换行、多个空格和引号。
- offsets 使用 JS 字符串 UTF-16 下标 `[start,end)`，与 textarea.selectionStart 一致；字符计数/字格采用 Unicode 字素（汉字主体按码点处理），不能混用 offset 与格数。
- imports 是已发生导入的来源索引，不是自动恢复来源的指令。恢复草稿不能再次执行 OCR/教材导入。
- `legacy` 只用于来源不明的旧本机数据，不能伪装成正式教材。原教材词条被改字后，旧读音/义项不能继续冒充匹配答案；设置 localOverride 并重新校对。
- 来源类别与 mode 无关。一个来自识字表的字，用户选择汉字字帖后同样按“拼音→米字格”生成。

### 2.1 稳定 token 与原文编辑

纯排版改变不触碰 tokenId。文本编辑先重新解析，再在前后 token 序列中做稳定的一对一匹配；相同文本、同一局部来源且映射无歧义的 token 保留 ID 和修正。不能用 `map[text]` 合并所有相同字，造成多音字互相覆盖。

对于不确定匹配（重复同字不同读音、词被拆开/合并、替换来源），保留可核对的旧候选但取消确认状态，显示需要复核；不要猜来源。用户改了 text，默认失效该项拼音/释义确认，只有经明确教材精确匹配或用户再次确认才恢复。

词条标签的“修改正文/拆开/合并”应由编辑操作更新 rawText 的对应范围再解析，不能维护一份和 textarea 不同步的条目表。手工读音/义项覆盖挂在 tokenId 与 textFingerprint 上；fingerprint 不同不得沿用。

### 2.2 教材转文本

1. 选择范围及 listTypes，取完所有分页并核验同一 edition/contentVersion。
2. 按既有单元/课/条目稳定顺序读取；仅将条目的 text 序列化到共同文本框，拼音/义项/sourceRefs 作为附带映射保留。
3. 多类字表同选时，可先保留重复出现，步骤 2 再按“合并相同项”处理；不能因类别不同复制题型，也不能在导入时丢掉不同读音/义项。
4. 新增内容默认追加；替换必须是用户选择的替换动作。选项变更只修改选择器候选，不自动改原文。
5. 教材中文词内部不插空格，词间一个普通空格；英语含空格的短语按下面引号规则序列化。来源文本不合法时定位错误，不默默裁掉。
6. 来源版本缺失/部分覆盖/服务失败继续如实显示；不得回退示例。原文已有内容不因请求失败清空。

### 2.3 文本解析与规范化

| 输入/策略 | 派生结果 | 原文处理 |
| --- | --- | --- |
| 中文 `春天 花朵 山 水` | 四项：春天、花朵、山、水 | 原样保留 |
| 中文 `春天花朵` | 一项；提示可加空格分词 | 不自动分词 |
| 中文 `春天\n花朵`（实际换行） | 两项 | 保留换行 |
| 全角空格/Tab/连续空格 | 空白分项，不产生空题 | 保留原始空白 |
| 英文 `apple "ice cream" don't` | 三项，短语含一个内部空格 | 保留包裹引号，题面不显示包裹引号 |
| 英文逐行模式：一行 `ice cream` | 一项，行内空格保留 | 仅换行分项 |
| `Spring spring` | 两个不同项 | 不自动 lowercase |
| 高级双列表 | 旧 parser 解析 text+读音/释义，再生成原文和确认字段 | 同时保留导入格式记录；不把逗号当普通词分隔符 |

默认分词实现为小型 tokenizer 状态机：空白、未引用文本、双引号文本、转义四种状态。双引号用于保留短语；支持 `\"` 与 `\\`，未闭合引号定位行/列并阻断下一步。中文也可使用引号保留特殊短语，但汉字字帖里的非汉字内容必须经过字段校验。

输入中文标点/英文逗号、分号不是默认词边界：发现后给出“转为空格分隔”的显式清理操作及前后预览，可撤销；不能悄悄移除后拼成另一个词。单引号、英语连字符、小数/编号不通过通用 replace 擅改。短语的引号规则不套用于 CSV 内部，CSV 继续由现有专用 parser 处理。

派生规范化使用 NFC、去首尾空白；引用短语内部连续空白规范化为一个空格。规范化不覆盖 rawText，不把 `ü` 或声调组合字符去掉。不可见控制字符定位报错；新增文本按 textContent 显示，不使用 v-html。

规模初值：原文 ≤64 KiB UTF-8；一次解析最多 2000 token，超出阻断并提示缩小范围；一次选入题单 ≤200 项、重复后 ≤500 题、每项重复 1—5 次。全册超过 200 项仍可进入输入/候选区，在步骤 2 选择数量，不能自动截取前 200 项。单项长度沿用有界校验，超长项能定位而不使整个页面卡死。

### 2.4 去重、拆字、抽取

确定顺序：解析 → 确认分词/读音/义项 → 可选逐字展开 → 等价去重 → 选题 → 显式排序 → 重复展开。

默认 `chineseUnit=word`、`duplicatePolicy=merge-equivalent`、selection=all、ordering=input。去重键为 NFC text + 规范读音/义项 + sense/必要语境，保留第一次出现的位置并合并全部来源；不同读音/义项不合并。同读音同字来自识字和写字表时可合并，因为本次全卷只有一个 mode。去重前后都显示数量，可选择保留每次出现。

“逐字练习”只影响派生项；用户从逐字切回保留词语后，rawText 及词边界不变。展开后的字继承词语内确认读音，多音字不得重新用孤字默认读音替换。

随机/均衡保留既有固定 seed 算法，但不能在 selectedEntries 最后再次 sort(compareEntries)。均衡仅在全部选入项有可信教材单元时启用；自定义/OCR 混入时禁用并说明原因，不能把所有自定义项假塞一个教材单元。可显式只对教材子集使用均衡。

## 3. 全局题型与校对

| mode | 学生卷必需字段 | 答案卷额外条件 | 书写区 |
| --- | --- | --- | --- |
| chinese-pinyin | 合法汉字 text | 每字确认的拼音和对齐关系 | 每字空四线三格 |
| chinese-dictation | 汉字 text + 每字确认拼音 | 汉字本身为目标答案；歧义提示经过审核 | 每字空米字格 |
| english-meaning | 英文单词/短语 text | 确认的中文义项 | 中文空横线 |
| english-dictation | 英文目标 text + 确认的中文义项 | 允许答案和词性/语境明确 | 英文空四线三格 |

首次新建 mode=null，在处理步骤由用户选择。任意模式改变立即使产物过期，原文与校对结果保留；不会因换模式自动重新 OCR 或重新选择教材。

分别计算 canGenerateStudent 与 canGenerateAnswer。阻断条件只取当前输出对象所需字段；例如尚缺拼音应阻断拼音字帖的答案卷，不应顺带阻断只显示汉字的学生卷。字段校对 UI 仍保留缺项提示。

### 3.1 拼音优先级与对齐

优先级：本次用户确认覆盖 → 精确来源条目的已审读音 → 明确匹配的教材候选 → 本机自动候选。来源可信不等于任意同字都可以套它的读音；自定义文本命中多个语境必须供用户选择。

本机自动拼音结果状态 suggested，显示“自动建议，待确认”。提供逐项修改与批量确认明确无歧义项；未解决的多音/音节数量不匹配项不能被“全部确认”跳过。用户自定义读音只保存到本机，不写回公共词库。

使用逐字 pinyinSegments，不依赖一串拼音 split 后盲目对齐。多音字、轻声、ü、隔音符号、儿化、补充平面字符单独有 fixture；尚不支持的对齐形式应提示修改并阻断受影响输出，不隐藏错误。拼音字帖学生卷无拼音也可生成，答案按钮说明尚缺读音。

### 3.2 英语输入与义项

默认文本框输入英文目标，不要求用户同时输入中文；“英语默写”需要中文提示时在处理表补齐。提供高级“英文、中文”双列表导入，一次补充多项。

中文原文无法唯一推断英文，若输入只含中文则显示需要补英文目标的条目，不用自动猜译生成练习。英文大小写、短语、撇号、连字符保留；不同义项可同时存在。接受答案只来自确认内容，不凭猜测加入。

## 4. OCR 适配器与确认

建议接口如下，属于新增本机模块，不是服务端 API：

```ts
interface OcrRequest {
  jobId: string;
  imageId: string;
  inputRevision: number;
  language: 'chinese' | 'english';
  image: Blob;
}
interface OcrCandidate {
  jobId: string;
  imageId: string;
  inputRevision: number;
  rawRecognizedText: string;
  suggestedText: string;
  confidence: number | null;
  regions: Array<{
    text: string; confidence: number | null;
    bbox: { x: number; y: number; width: number; height: number };
  }>;
  engineVersion: string;
  modelVersion: string;
}
```

### 4.1 生命周期

`selected → preparing → recognizing → review → applied`；任一步可以 cancelled/failed，允许重试。applied 仅表示用户确认加入原文，不代表识别百分百准确。

1. 校验扩展名、MIME、真实图像头、文件字节与像素；不只相信浏览器 accept。超限在大画布/引擎分配前拒绝。
2. 应用图像方向，提供 90° 旋转与识别区域；默认整图。坐标记录在解码并校正方向后的像素空间，缩放后的 ROI 必须按比例换算。
3. 工作图缩放到预算内，界面显示提示；裁选/重新识别可基于原图重做，不反复缩小旧工作图。对比度/灰度为显式可选增强，保留原图预览。
4. 首次进入图片入口后按语言加载模型；中文用 chi_sim，并按实际需要配 eng，英语只加载 eng。Worker 数默认 1，多图按列表顺序处理，不按完成先后拼文字。
5. 开始识别时冻结 imageId/jobId/inputRevision；取消时终止相关 worker/job，必要时下次重建 Worker。120 秒从 recognize 开始计；资产加载另设 60 秒超时并展示下载状态，二者不混成假进度。
6. 候选结果不直接写 textarea。用户可删页码/标题、修正错字、加空格；之后点击追加/替换。若原文 revision 已变化，重新展示应用方式，绝不能按旧请求自动覆盖。
7. 删除图片、切学科或重试后迟到结果不得复活旧图片。手工已编辑的候选不能被重试结果悄悄覆盖，重试返回新候选，由用户选择采用。
8. 清空/离开流程释放 Object URL、画布和 Worker；已应用原文独立保留。识别失败、模型加载失败或无文字时保留原文和可重试的图片。

### 4.2 词语与置信度

OCR 行不等于词条，中文无空格串不能被假定为词表；先呈现真实识别文本。提供“按空白”“每行一项”和中文显式“逐字”方式，标签预览供校正。自动分词最多作为可确认建议，不是正式教材校验。

可用的低置信区域（例如 <80/100）标记待核对；confidence=null 时明确未提供，不能显示为 100%。即使高置信也保留结果确认步骤。识别拼音行与汉字行混杂时让用户裁区域/删行，不宣称自动正确配对。

### 4.3 静态资源与测试

新增 `public/ocr/<assetVersion>/` 的受管资源清单，列 tesseract.js、核心 WASM、worker、chi_sim/eng traineddata 的版本、来源、许可证与 SHA-256。允许构建前脚本按锁定清单准备文件，生产浏览器不能去第三方 CDN 补缺件。首页/纯文本字帖不预加载 OCR；模型请求仅发生于 OCR 使用。

官方 API 支持指定资源路径，结构化输出需按所锁版本显式开启；不要假设默认 text 结果含字框。实现时按 [Tesseract.js API](https://github.com/naptha/tesseract.js/blob/master/docs/api.md) 与 [本地资源配置](https://github.com/naptha/tesseract.js/blob/master/docs/local-installation.md) 核对选定版本。

质量集使用原创合成清晰中英词表（有 ground truth），再加旋转/照片/低清/无文字。先验证实际引擎输出再校对，不能把人工改好的文本冒充 OCR 输出。清晰集合汇总中文 CER≤5%、英文 WER≤5% 为初始准入目标；低清集合不要求神奇准确，但必须能纠错且不能静默成功。固定规范化、模型版本、输入 hash、字符/词计数，单独记录实际值；目标未达标先定位而不是删难样本。

计量固定为：中文做 NFC 后仅去掉 Unicode 空白，不删错字、标点或数字，按 Unicode 码点计算编辑距离，CER=(替换+删除+插入)/参考字符数；英语做 NFC、统一连续空白，保留大小写和标点，以空白分词计算编辑距离，WER=(替换+删除+插入)/参考词数。分子/分母跨清晰集合求和，不平均每张比率；无文字图独立测试，不以零分母计准确率。指标针对 rawRecognizedText，不能先人工纠正/词典替换再计算。

## 5. 状态、原文返编与快照

### 5.1 状态转移

```text
INPUT -> PROCESS -> GENERATING -> PREVIEW -> OUTPUT
  ^          |                       |
  |          +------ EDIT -----------+
  +-------------- ADJUST ------------+
```

INPUT/PROCESS 的原文框使用同一 document.rawText。PREVIEW 点调整总回到带原文的编辑界面；OUTPUT 是当前 artifact 的操作，无独立派生文件。

至少维护 `inputRevision`、`processingRevision`、`renderRevision`、`generationId` 和 `artifact.status=current|stale`。可统一为单调 draftRevision 加内容/版式 hash，但必须能判别异步请求与产物是否仍适用。

| 变化 | 原文 | 下游处理/预览 | 操作身份 |
| --- | --- | --- | --- |
| 返回编辑，未改变任何值 | 完整保留 | 可继续沿用原 artifact | 不变 |
| 修改文本/读音/义项/词边界/模式/重复/实际选题顺序 | 保留新值和相应修正 | 旧 artifact stale；重新校验生成 | 内容确实改变并有效生成时新 operation |
| 标题/密度/格距/间距 | 原文不变 | 新 renderRevision，重新排版 | 原 operation |
| 空白改变但解析结果等价 | 保留新原文 | 重新解析；语义与版式一致可复用 bytes，但绑定新原文修订 | 原 operation |
| OCR 结果尚未采用 | 原文不变 | 只变候选区 | 不变 |
| 清空 | 清空 | 取消任务、清空处理与产物、busy=false | 无成功事件 |
| 重新生成同一已确认配置/网络重试 | 不变 | 沿用快照/标识 | 原 operation |

进入编辑状态时暂停输出操作，明确指示完成或取消调整；即使旧 artifact 还在内存，也不能让快捷键下载过期文件。校验失败留输入与错误，不回到旧 PDF 伪装生成成功。

### 5.2 快照更新

保留全局 snapshot.mode；新增 schemaVersion=2、inputDocumentId/inputRevision、parsePolicy、词边界、已确认 segments/meaning 和处理选项。snapshot 只复制已选词条，文档原文存在独立本机草稿，不嵌入学生 PDF。

- contentHash 包含 global mode、每项实际文字/确认读音/义项/必要安全语境、顺序、词边界、逐字策略、重复、算法/seed 和来源版本。
- renderHash = hash(contentHash + artifactKind + title + 全部layout + fontVersion + rendererVersion)。不能只 hash 条数。
- 新 rendererVersion 使用 `worksheet-renderer-v2`；升级字体另有 fontVersion。
- 快照深拷贝后只读冻结，包括嵌套拼音、sourceRefs、acceptableAnswers；渲染只接受快照投影，不读页面 reactive 对象。
- 产物记录 generationId、inputRevision、renderRevision、snapshotHash、sha256、kind、pageCount。返回结果时同时检查当前 subject/document/revision/generationId，再替换预览。
- 学生/答案各自产物必须来自同一冻结内容快照；页数允许不同，题号与题目顺序必须对应。当前缺答案时不把上一题单的答案卷留在切换入口。

### 5.3 v1 草稿迁移

沿用主动保存，不因 OCR 自动把原图或识别文本持久化。v2 只保存可序列化原文/来源摘要/校对/配置，不存 Blob/base64/临时 URL/Worker；图片对象只在本次内存，恢复已识别文字不需要原图，重新识别需要重新选图。

读取顺序：合法 v2 → 用户选择恢复 v1 → 新空草稿。保存失败（容量/禁存储）提示当前编辑仍在，不丢原文。v1 迁移保留原 key，显式保存后才写 v2；迁移异常不 reset 所有 localStorage。

原 v1 未保存 rawText，无法恢复字面上的历史空格/引号：从 customEntries 生成等价规范化文本，显示“已迁移旧词条，原始输入格式未保存”。只保存 selectedIds 的教材部分不能离线编造；可联网按原版本重新取回并让用户确认，失败仍保留已恢复自定义文本。不得将这种有限迁移宣传成旧原文逐字保真。

## 6. 紧凑排版契约

### 6.1 页面与参数

| 参数 | 新建默认 | 可选/约束 |
| --- | --- | --- |
| paper/orientation | A4/portrait | 210×297 mm，保持 |
| marginMm | 15 | 内容宽 180 mm |
| density | compact | comfortable 可用 5 mm 行/项间距 |
| gridMm | 12 | 10/12/14；恢复明确手动值 |
| chineseGrid | mi | 新汉字字帖必用米字格；不再默认为 tian |
| itemGapMm / rowGapMm | 2.5 / 3 | 所有纸面参数进入 renderHash |
| pinyinCellMinMm | 16 | 空拼音每字最小书写宽度，不等于米字格边长 |
| fourLineGapMm | 3 | 四条线总高 9 mm |
| number font / pinyin font | 9 pt / 10 pt | 拼音可测量后降至 9 pt，再不足必须扩宽 |
| Chinese prompt font | 14 pt | 不为了塞列无限缩小 |

所有尺寸计算先使用 mm 或统一 pt，`pt=mm×72/25.4`。A4 几何误差≤0.5 pt；深色主题不影响白色纸张与打印线条。

### 6.2 题块测量与顺序装行

1. 编号区宽 `numberWidth=max(3 mm, measure(number + '.',9pt)+0.8 mm)`；不能继续给每题固定一整大列。
2. 汉字字帖，每字列距 `pitch_i=max(gridMm, measure(syllable_i,pinyinFont)+1 mm)`，米字格自身仍是 gridMm 正方形，居中在对应列距内。题块宽为 numberWidth+sum(pitch_i)。整词拼音逐字对齐到空格，不直接画一整串导致错位。
3. 拼音字帖，每字书写区至少16 mm，四线总高9 mm，汉字居中置于对应区下方；词内部按字排列。题块宽为 numberWidth+sum(各字书写宽)。汉字不放入空拼音格中占用作答位置。
4. 英语默写，中文提示在上、连续四线在下。最小书写宽40 mm，较长目标词/短语按实际手写容量增加；英文释义练习的横线最小40 mm。不把四线长度等同于电脑英文文字宽度；短单词通常每行4项，长短语可更少。
5. 各题先求 promptLines、writingGeometry、height；当前行剩余宽能容纳就追加，不能容纳才换行。行高取其中最大高度，另加 rowGap；不因单字变双字强制另起行。
6. 保持选定顺序，不用从后面找短词来塞空隙。页面边界按可用高度放整行；整题及其所有续行不可跨页。
7. 单题超180 mm时，按汉字/拼音对应单元或英语词边界折为同一题的续行组，仅首行编号。若整题高度超过一页可写区，定位报错并建议拆分；不截断尾部或无限缩字。
8. 顶部标题/姓名栏与页脚留固定区域，先计算可用高度；不可为了挤更多题穿越安全区。换页无多余空白尾页。

### 6.3 固定密度夹具

使用实际发布字体测量并生成 PDF，不以浏览器 CSS 列数代替纸面检查。以下是原创排版夹具，不是教材。

- 米字格单字，12 mm、短读音：`一 二 三 四 五 六 七 八 九 十 山 水`，人工指定对应正确读音；第一页首行应容纳10项。题号实测若超过预留必须用真实编号宽计算，不能覆盖格子。
- 米字格双字：`大山 小河 白云 红花 小手 大地`，使用明确读音；首行6项。
- 拼音字帖：以上单字的汉字提示+16 mm空四线区；首行至少8项。
- 14 mm米字格、长音节 `shuāng/chuāng`、长词/三位题号另测，不套短音节密度数字。格子和可书写空间不得为达标被缩小。

单词题宽增加时每行4—5题是可解释的真实容量；这次要消除的是现有单字也被固定列宽稀疏展开。交付前提供新旧相同输入、相同格距的 PDF 对比。

本轮用当前 `NotoSansCJKsc-Regular.otf` 与 fontkit 做了**字体宽度测量**（不是产品生成/打印验收）：9 pt 的 `1.` 加0.8 mm为3.444775 mm，`10.`为5.2069 mm。按上述2.5 mm项间距，首行10个单字约178.71 mm、6个双字约177.17 mm、8个拼音单字约173.06 mm，均在180 mm内；若仍用3 mm项间距，10单字约183.21 mm，不能达标。实现不得忽略真实编号宽度；正式 PDF 几何还需重新验证。

### 6.4 米字格与投影

`drawMiGrid` 每格：四边外框 + 水平中心虚线 + 垂直中心虚线 + 两条对角虚线。建议外框0.5 pt、辅助线0.25 pt，黑白打印有层次；方向从一角到对角，不能画错成只有十字。

学生投影仍只含题干、安全语境、题号和几何必需信息。不要为了测量/方便画字格，把答案文本、整份原文或 OCR 图片塞进学生投影或 PDF 附件/元信息。汉字字帖的目标字数可用于格数，目标汉字正文不能进入学生内容流；拼音字帖反向同理。

答案独立投影、独立文件；同一学生 artifact 的 bytes 供页面预览、下载和打印窗口使用。实际下载文件 SHA-256 必须与该 artifact 一致，不能在点击下载时再次抽题/排版。

## 7. 错误、性能与统计

前端错误统一使用英文 code/locale key，新增 `WORKSHEET_INPUT_EMPTY`、`WORKSHEET_PARSE_QUOTE`、`WORKSHEET_INPUT_LIMIT`、`WORKSHEET_REVIEW_REQUIRED`、`WORKSHEET_STALE_ARTIFACT`、`OCR_FORMAT_UNSUPPORTED`、`OCR_IMAGE_LIMIT`、`OCR_ASSET_FAILED`、`OCR_TIMEOUT`、`OCR_NO_TEXT` 等稳定码。用户界面翻译，错误记录不打印原图、完整原文或答案。

原文解析采用防抖/增量处理；2000 token 上限以内应有明确响应。PDF/识别重计算可取消，不能仅让取消按钮改变文案而后台继续覆盖。冷启动与热缓存分开测量；OCR 单图时间不是统一性能承诺，必须记录实际设备、模型、分辨率与耗时。

输入文本、识别结果、文件名、题单答案、tokenId/sourceRef 不进入统计。成功生成沿用 `worksheet.chinese-pinyin`、`worksheet.chinese-dictation`、`worksheet.english-dictation`、`worksheet.english-meaning`；纯布局变化不新增成功。明确新建同样题单可生成新的 operation，网络/生成重试不新建身份。

打印只记录用户主动调用系统打印的请求，不将预览打开、下载点击、afterprint 或窗口关闭当成实际打印成功。

## 8. 实施时核对的官方资料

本轮已查阅项目说明以核对能力方向，具体安装版本由实施 Agent 锁定并验证，不使用浮动 latest 部署：

- [Tesseract.js 项目说明](https://github.com/naptha/tesseract.js)：浏览器图片 OCR、Worker 复用、PDF 能力边界。
- [Tesseract.js API](https://github.com/naptha/tesseract.js/blob/master/docs/api.md)：生命周期、资源路径与输出选项。
- [Tesseract.js 本地安装](https://github.com/naptha/tesseract.js/blob/master/docs/local-installation.md)：静态资产配置。
- [pinyin-pro 项目说明](https://github.com/zh-lx/pinyin-pro)：带声调数组、多音字与自定义读音接口；不是教材校对凭据。
