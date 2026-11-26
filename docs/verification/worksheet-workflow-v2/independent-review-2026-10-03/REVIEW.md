# 字帖改造独立验收报告

日期：2026-10-03。对象：本地 `toolbox-web` 当前实现及其交付证据。

**结论：未通过验收。** 基础检查可以通过，但关键编辑、生成和恢复流程存在可重复的错误，现有“全部通过”报告不能作为交付完成的依据。

本次只验收并新增证据文件，没有修改业务代码、修复缺陷或发布生产。即使采用交付报告记载的“中文 OCR 作为辅助录入、验证到 PDF 导出为止”的较小范围，下列内容正确性问题仍阻塞验收；结论不依赖是否要求实机或实体打印。

## 1. 本轮实际执行结果

| 检查 | 结果 | 证据与边界 |
| --- | --- | --- |
| 现有单元/集成测试 | 250/250 通过，0 skipped | [unit-tests.log](unit-tests.log)。这些测试没有覆盖本次发现的全部会话集成错误。 |
| TypeScript 类型检查 | 通过 | [typecheck.log](typecheck.log) |
| 生产模式构建 | 成功 | [build.log](build.log)。使用隔离的临时 outDir；日志中的 outDir 提示来自审查所用外部目录，不是业务编译错误。 |
| 教材结构、引用、校对及 hash 校验 | 通过 | [curriculum.log](curriculum.log)：20 个必需槽位、5 个已发布版本；这不表示 20 个槽位全部有内容，也不表示页面能选择这些版本。 |
| 本地依赖版本检查 | 通过 | [dependencies.log](dependencies.log)：21 个包匹配；该检查不验证 OCR 静态资产。 |
| 新增针对性缺陷探针 | 8/8 场景复现缺陷 | [首次结果](session-defects.json)、[主 Agent 复跑结果](session-defects-parent-rerun.json)、[可重跑探针](reproduce-session-defects.ts)。这里的“8/8”指错误被复现，不是 8 项验收通过。 |
| 本地浏览器 | 页面和原文输入已观察；端到端未完成 | 打开 `http://127.0.0.1:4173/#/copybook` 并输入“春 天”；浏览器控制的点击/键盘执行异常或超时，未得到完整生成下载结果，因此不把按钮测试记为通过，也不把控制工具异常直接认定为产品缺陷。 |
| 生产、实际文件下载、真实 OCR、实机及实体打印 | 本轮未重跑 | 现有文件仅作历史证据参考；不继承其“通过”状态。 |

源文件及验收契约的取样 hash 见 [source-snapshot.json](source-snapshot.json)。本轮探针覆盖纯领域函数、真实 composable 配合内存 localStorage、处理结果与投影；迟到生成使用可控渲染桩，未冒充真实 PDF 渲染或浏览器下载。

复跑命令，在仓库的 `toolbox-web` 目录执行（需可用的 Node 与本地依赖）：

```bash
npm test
npm run typecheck
npm run test:curriculum
npm run verify:deps
node --import tsx ../docs/verification/worksheet-workflow-v2/independent-review-2026-10-03/reproduce-session-defects.ts
```

注意：探针输出 `defectReproduced`，退出码 0 只表示探针执行完成；修复后的成功标准是对应值变为 `false`，并补充验证正确行为的回归测试。

## 2. 阻塞验收的实现问题

### R01 / P1：改了正文，旧 PDF 仍可输出

- 位置：[worksheet-session.ts:84](../../../../toolbox-web/src/domain/worksheet-session.ts#L84)、[worksheet-session.ts:101](../../../../toolbox-web/src/domain/worksheet-session.ts#L101)。
- 复现：生成“春 天”的产物后，原文改为“秋 冬”；或者将“春 天”的分项策略改为逐行。
- 实测：前者原文已变，artifact 仍为 `current`，`canOutput=true`；后者从两项变一项，仍可输出旧产物。
- 原因：正文等价判定只比较条数；切换解析策略时直接传入 `equivalent=true`。
- 修复要求：比较规范化的内容、顺序和当前输出所需字段；解析策略改变题目集合也须使产物过期。输出入口核对当前内容/版式版本与产物版本，不能只信旧状态标记。
- 复验：上述两种操作立即禁用导出/打印并标记旧预览过期；重新生成后的实际下载文件内容对应新原文。对应 W29/W30。

### R02 / P1：原文格式错误，仍会用旧词条生成

- 位置：[worksheet-input.ts:386](../../../../toolbox-web/src/domain/worksheet-input.ts#L386)、[worksheet-session.ts:242](../../../../toolbox-web/src/domain/worksheet-session.ts#L242)。
- 复现：已有有效“春 天”后，原文改为未闭合引号 `"unclosed`，再调用生成。
- 实测：存在 `WORKSHEET_PARSE_QUOTE`，但生成仍成功，题目继续是旧“春”“天”，输出再次启用。
- 原因：解析失败保留旧 tokens；生成入口没有检查当前原文的解析状态，只检查后续处理结果。
- 修复要求：解析状态纳入会话真值，生成入口统一阻断无效原文；保留旧 tokens 可用于恢复，不得把它们当作当前有效输入。
- 复验：错误保留在输入框并定位；不可生成、下载或打印旧卷；修复引号后才重新开放生成。对应 W33。

### R03 / P1：迟到生成会恢复已清空内容或覆盖新编辑

- 位置：[useWorksheetV2Session.ts:295](../../../../toolbox-web/src/composables/useWorksheetV2Session.ts#L295)、[useWorksheetV2Session.ts:312](../../../../toolbox-web/src/composables/useWorksheetV2Session.ts#L312)、[worksheet-session.ts:272](../../../../toolbox-web/src/domain/worksheet-session.ts#L272)。
- 复现：让生成 Promise 暂停，清空会话，再让旧生成返回，按真实 composable 的逻辑回写结果。
- 实测：清空后原文为 `''`、不可输出；旧任务返回后又变为“春 天”、可以输出。
- 原因：完成检查针对旧闭包中的 workflow；调用方无条件把旧结果的整个 session 覆盖到当前 session。
- 修复要求：生成开始时捕获 documentId/revision/generationId；结束时与最新会话比较，只接纳仍有效的产物，不回写旧会话。清空、路由离开和编辑必须取消或作废旧请求；释放废弃 URL。
- 复验：生成中编辑、清空、切模式以及先发后至四条路径均不能恢复旧内容；busy 正确结束，仅最新版本允许输出。对应 W30/W34。

### R04 / P1：草稿保存成功，恢复却丢字段

- 位置：[useWorksheetV2Session.ts:449](../../../../toolbox-web/src/composables/useWorksheetV2Session.ts#L449)、[useWorksheetV2Session.ts:465](../../../../toolbox-web/src/composables/useWorksheetV2Session.ts#L465)。
- 复现：英语按逐行输入 `ice cream\nGood morning`，补齐两条释义、设置 14 mm 和标题，保存；创建新会话后恢复。
- 实测：原文虽然保留，但按空白重解析成 4 个词；释义全部为 null，格距回到 12 mm、标题为空。v1 草稿迁移还丢失原题型与拼音。
- 原因：v2 恢复只应用 rawText/processing，忽略已保存的 parsePolicy、corrections、layout 等；v1 路径只取 rawText。
- 修复要求：先恢复解析策略，再恢复原文及稳定身份绑定的校对、来源、版式和选择；旧版只读迁移也必须保留合法题型与配对字段。不能仅凭 rawText 一致判恢复通过。
- 复验：主动保存→新会话恢复，词数、短语边界、读音/释义、模式、标题、格距和可恢复来源均一致；旧键不变；存储失败仍保留编辑。对应 W31/W32。

### R05 / P1：确认一项，会误确认其他多音词

- 位置：[useWorksheetV2Session.ts:375](../../../../toolbox-web/src/composables/useWorksheetV2Session.ts#L375)。
- 复现：“行”“重”均为自动拼音建议，只确认“行”。
- 实测：“重”也从 suggested 变为 confirmed。
- 原因：单项确认之后调用全列表确认，且传入 `() => false`，把其他项也视为无歧义。
- 修复要求：单项操作仅更新指定 token；批量确认使用实际多音字判定，不得把未审项批量置为已确认。
- 复验：只确认“行”时，“重”仍待校对；缺音、音节数不符和多音词均不能借此绕过必要审核。对应 W23。

### R06 / P1：教材选择入口丢失，只能取固定册次

- 位置：[useWorksheetV2Session.ts:166](../../../../toolbox-web/src/composables/useWorksheetV2Session.ts#L166)、[WorksheetV2View.vue](../../../../toolbox-web/src/views/WorksheetV2View.vue)。证据等级：代码与页面结构核查，未完成 API 导入的浏览器复跑。
- 当前行为：语文固定 1 年级上册、英语固定 3 年级上册，直接取第一个版本；页面没有年级、上下册、版本和字表类型的选择入口。单元/课文选择不能代替册次选择。
- 影响：用户不能选用其他已发布教材，也不能按原要求自由选择识字表、写字表和词语表后再决定全局题型。
- 另：`editionIdValue` 初始化后没有赋值，传入导入逻辑的教材版本身份为空。
- 修复要求：提供完整的册次/版本/范围选择，查询使用用户选择，保留覆盖状态与版本身份；字表只决定输入内容，不自动映射题型。
- 复验：所有已发布册次可达；缺失册次如实提示；混选字表后可自由切换两种中文题型，原文不因题型改变。对应 W01/W03/W19/W43。

## 3. 同时需要修复或补验的其他缺口

| 编号 | 证据与影响 | 修复及复验要求 |
| --- | --- | --- |
| R07 / P2：重复次数展开两次 | 探针实测 2 词 × 2 次变成 8 题。处理层已展开，composable 又把 repetitions 传给再次展开的渲染层。当前页面缺少重复控件，但含 repetitions 的恢复草稿可进入该状态。 | 只由一层展开；2×2=4、200×2=400，超过 500 明确阻断；题号、摘要及 PDF 题数一致。W26/W27。 |
| R08 / P2：英语校对界面依赖拼音状态 | [WorksheetEntryList.vue:79](../../../../toolbox-web/src/components/worksheet/WorksheetEntryList.vue#L79) 中，新英语词 meaning=null/meaningState=missing/pinyinState=missing 时，不呈现释义输入框。代码核查，未完成浏览器复跑。 | 按学科/题型显示字段，英语可以直接补中文释义；无需先“确认拼音”。W24/W25。 |
| R09 / P2：第 61 项起无法逐项校对 | [WorksheetEntryList.vue:27](../../../../toolbox-web/src/components/worksheet/WorksheetEntryList.vue#L27) 只截前 60 项，没有分页或载入更多。代码核查。 | 使用分页或虚拟列表，让 200 项内任何一项可定位、编辑及确认；第 61/200 项纳入实际交互复验。W23/W27。 |
| R10 / P2：移除图片未清空队列 | [WorksheetImageInput.vue:375](../../../../toolbox-web/src/components/worksheet/WorksheetImageInput.vue#L375) reset 没有清 items/bitmap/crop；界面清空与队列状态不一致。代码核查，未冒充实测。 | 移除后清队列、取消任务并释放资源；选满 5 张→移除→重新选择可以成功，旧任务不得追加结果。W10/W16/W17。 |
| R11 / P2：旋转预览与 OCR 输入处理不一致 | [WorksheetImageInput.vue:273](../../../../toolbox-web/src/components/worksheet/WorksheetImageInput.vue#L273) 送识别图只裁剪/缩放原 bitmap，没有应用用户选择的旋转变换。代码核查。 | 将方向纠正与裁剪使用同一坐标定义；用带方向标记的图验证 0/90/180/270 度预览与实际 OCR 输入一致。W12。 |
| R12 / P2：承诺的调整及答案功能没有页面入口 | 当前 WorksheetV2View 没有标题、格距、重复、逐字策略或答案输出入口；generateNow 固定 `answer:false`。底层可以生成样本 PDF 不等于用户能操作。 | 补齐本轮契约承诺的入口，或记录明确的范围变更；经页面调整→再次预览→实际下载验证。W20/W21/W24/W25/W28/W34/W36。 |

R07 已执行探针；R08—R12 为代码核查发现，仍需修复后的真实界面或图像证据，不能标成已经完成浏览器复验。

## 4. 现有验收报告及脚本需要纠正

1. **大量状态没有实际执行支撑。** [run-acceptance.mjs:46](../../../../toolbox-web/scripts/run-acceptance.mjs#L46) 将不抛异常的回调直接记 pass。W11、W35—W37、W39b、W41、W43、W44 等包含写死的历史结论与 `expect(true)`；W39a 是空回调后 pass。这些不会发现当前页面回归。
2. **缺必测子项仍标通过。** W20/W21 的脚本明确承认未完成 PDF 答案扫描、DOM/答案对应等验证，却仍 pass。必须遵守 acceptance.md 的规则：任一必测子场景未完成，不得把主用例记为通过。
3. **失败不使命令失败。** 脚本最后只写报告，没有对 fail 设置非零退出码；它不能作为可靠门禁。应分别处理 fail、not_run、blocked、正式排除项。
4. **计数错误。** 原 acceptance-results.json 共 54 条：50 pass、1 fail、3 blocked，其中含 13 条片段记录。按 W01—W44 归并为 40 pass、1 fail、3 blocked；其中的 pass 仍有上述证据不足，不能继承为本轮通过数。若接受 OCR 且移除 3 项，范围总数最多为 41，不是报告写的 43。
5. **最终文档互相矛盾。** FINAL-REPORT.md 顶部宣称全通过，正文仍有失败、阻塞和旧入口描述；delivery.md 也留有旧状态。需要一份与当前构建、范围裁定、子项证据一致的最终结果，旧过程记录应标明历史性质。

本次没有运行会覆盖旧证据的 `test:acceptance`，也没有将其历史结论抄写为本次执行结果。

## 5. 交给实现 Agent 的修复顺序与再次验收门槛

1. **先修会话一致性（R01—R05）。** 把本报告探针转为正式回归断言，覆盖真正的 composable 状态提交；在断言正确结果的同时保留触发错误的时序与输入。
2. **补齐可操作的业务入口（R06/R08/R09/R12），纠正重复展开（R07）。** 不以底层函数或临时样本替代用户页面能力。
3. **修 OCR 图像与队列状态（R10/R11）。** 若 OCR 继续定位辅助录入，仍必须保证旋转、移除、取消和校对采用的是用户实际选定的内容。
4. **重建验收门禁与证据索引。** 44 个主项分别列出 scope/status/subcases/evidence/build hash；片段不增加主项计数。没有执行的子项保持 not_run；不要用固定字符串或 expect(true) 代替行为验证。
5. **最后复跑完整链路。** 至少覆盖中英文三类来源、两种模式、同数量替换、解析错误、生成竞争、草稿跨会话恢复、版式调整，以及通过正式按钮取得的 PDF 文件。记录文件 hash、页数及内容；纸面预览、下载、打印预览引用同一有效产物。

再次通过的必要条件：R01—R06 全部修复并有对应回归证据；其他在范围内的功能缺口关闭；所有纳入范围的必测子项均有真实执行记录；测试、报告和交付说明计数一致。**250 项测试通过本身不能替代上述验收。**
