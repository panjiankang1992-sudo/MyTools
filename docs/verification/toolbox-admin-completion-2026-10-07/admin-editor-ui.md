# 管理员教材编辑器：缺失 UI 与流程补齐（前端）

- 日期：2026-10-07
- 范围：`toolbox-web`（Vue 3 + TS + Naive UI + Pinia）。**只改前端**；后端由另一条线负责。
- 被授权修改的编辑器：`toolbox-web/src/views/admin/TextbookEditorView.vue`（本线唯一改动它的代理）。
- 本次未触碰：后端 Java、公开字帖逻辑（`src/components/worksheet/**`、`src/domain/worksheet-*.ts`）、`src/services/public-api.ts`。

> 结论先行：**四项全部落地（第 3 项的"真范围过滤"除外，见 3c）**。
> 新逻辑尽量放在 `src/lib/` 的小模块里并单测，编辑器模板只做接线。

## 0. 基线与本轮结果（实测命令与数字）

| 命令 | 基线（本线开始前实测） | 本轮结束 | 结论 |
| --- | --- | --- | --- |
| `npm test` | 682 通过 | **715 通过 / 0 失败** | 全绿 |
| `npm run test:component` | **23** 通过（任务书写的是 22，实测为 23） | **39 通过 / 0 失败** | 全绿 |
| `npm run typecheck` | 干净 | **干净（无输出）** | 全绿 |
| `npm run build:production` | 零告警 | **零告警**（`grep -icE "warn|error"` 结果为 `0`） | 全绿 |

新增测试：单测 +33（682 → 715），组件测试 +16（23 → 39）。

## 1. 冲突：差异可见 + 「读取最新」（F05/F12，设计 170—171 行）

### 状态：**已实现 + 已验证**

**新增纯模块**
- `toolbox-web/src/lib/admin-draft-conflict.ts`
  - `diffDraftEntries(local, remote)`：按 **rowId** 对齐（不是下标），把条目分成 `local-only` / `remote-only` / `changed` / `identical`，并给出计数与 `identical` 布尔值；`preserved` 之外的"内容投影"取 `text/pinyin/meaning` + **排序后的** `listTypes`（类别是集合语义）。
  - `summarizeConflictDiff(diff)`：分别说明"本地有 N 条服务器没有 / 服务器有 N 条本地没有 / N 条两侧都改过"。
  - `resolveConflictPanel(details, hasPendingRetry)`：解析 `AdminApiError.details.expectedRevision`，并记录"是否因本次冲突放弃了一份排队中的重试"。
- 测试：`toolbox-web/tests/admin-draft-conflict.test.ts`（8 条）。

**编辑器接线**
- 409 不再只显示一句错误：渲染**冲突面板**（`aria-label="保存冲突"`），含 `服务器已有更新` 标签、`查看差异`、`读取最新`、`稍后处理`。
- `查看差异` 调 `fetchDraft` 拉服务端最新草稿并与本地条目比对，逐行显示"本地：… / 服务器：…"。
- `读取最新` 是**两步**：先展开内联确认（"…会用服务器上的内容替换当前编辑器中的改动…确定吗？"），确认后才回读。回读后：
  - `revision`、`baseContentVersion`、`SaveState` 全部对齐到服务端那一版 —— **这修掉了真 bug**（此前不回读 revision，再点保存仍 409，只能刷新整页）；
  - 本地改动**整份暂存到内存**（`stashedLocal`），面板外常驻一个 `恢复本地改动` 入口（不能只放在面板里：读取最新后面板关闭，暂存内容就没有入口了）。
- 状态机接线：`save()` 的 409 分支调用 `saveStateRef.value.peekPendingRetry()`（**必须在 `conflict()` 之前**，因为 `conflict()` 会清空它）再调用 `saveStateRef.value.conflict(frozen)`；`publish()` 的 409 同样进入面板（那里没有冻结操作，传 `null`）。
- **未接 `SaveState.fail()`（有意）**：把失败操作放进 `pendingRetry` 后，下一次 `begin()` 会**复用旧 payload 重发**；若用户在失败后又删了一行，旧 payload 会把它**复活** —— 正是设计 §3.5 明令禁止的"已删除的行不能被迟到响应复活"。因此重试路径在本轮保持不用，并在 `enterConflict` 旁写明理由。`peekPendingRetry()` 的取值语义由 `resolveConflictPanel` 的单测覆盖（传 `true/false` 两种）。
- 文案：`admin.conflict` 改为指向冲突面板（仍保留"覆盖"字样，`tests/admin-error-messages.test.ts` 的断言不变）。

**针对性测试**
- 单测：`node --import tsx --test tests/admin-draft-conflict.test.ts` → 8 通过。
- 组件：`npx vitest run tests/component/admin-editor-conflict.test.ts` → 4 通过。

**反向验证（这条测试为什么有鉴别力）**
1. 把 `readLatest()` 里的 `revision.value = remote.revision; resetSaveState(...)` 删掉 → `读取最新必须先确认；确认后回读修订号，下一次保存用新修订号` **失败**（saveDraft 仍收到旧修订号 3）。已实测。
2. 单测里"对方在中间插一行"的用例：若按下标比较，`changedCount` 会变成 2；实现按 rowId 对齐所以为 0。

## 2. changeNote（设计 362 / 160 / 166 行）

### 状态：**前端已实现 + 已验证；后端尚需接受该请求体**

**先读了设计再决定必填性**：
- 第 362 行（API 表）：`{draftRevision,baseContentVersion,changeNote}`，**未标必填**；
- 第 166 行：版本记录含"修改摘要"；
- 第 160—162 行的"保存并发布"顺序里没有"必须填写摘要"这一步。
→ 因此实现为**可选**（空串表示未填写），并在 `buildPublishIntent` 里注明依据。

**新增纯模块** `toolbox-web/src/lib/admin-publish-request.ts`
- `buildPublishIntent({revision, baseContentVersion, changeNote})` → `{draftRevision, baseContentVersion, changeNote}`；
- `normalizeChangeNote`：去首尾空白、截断到 200 字；`baseContentVersion` 缺失时为 `null`。
- 测试：`toolbox-web/tests/admin-publish-request.test.ts`（5 条，含一条**真发请求**的用例）。

**服务与界面接线**
- `publishDraft(editionId, intent, idempotencyKey?)` 现在发 JSON 正文，**同时**把 `draftRevision` 放进查询串 —— 当前服务端是用 `@RequestParam(name="draftRevision")` 读它的（`AdminTextbookController.publish`），只发正文会被静默忽略，"发布绑定确认过的修订"就会失效且不报错。
- 编辑器新增"修改摘要"输入框；`baseContentVersion` 随 `load()` / `读取最新` 更新；发布时用**这次保存之后**的 `revision`。

**针对性测试**
- `node --import tsx --test tests/admin-publish-request.test.ts` → 5 通过。其中 `publishDraft 真的把三字段发进请求体` 用桩 `fetch` 断言真实 JSON 与 URL（`…/publish?draftRevision=9`）。
- 组件：`tests/component/admin-editor-preview-publish.test.ts` 的 `发布请求体包含 changeNote 与 draftRevision/baseContentVersion` → 断言 `publishDraft` 收到 `{draftRevision: 5, baseContentVersion: 'demo-book@2', changeNote: '修订第一单元的词表'}`。

**反向验证**：只测纯函数 `buildPublishIntent` 时，调用方把返回值丢掉也能通过；因此额外用桩 `fetch` 断言**线上真正发出去的正文**，并断言正文里 `changeNote` 非空。

## 3. 四题型选择 + 当前编辑快照 + 选择范围（设计 164 / 162 / 344 行）

### 3a 题型选择：**已实现 + 已验证**
- `src/lib/admin-preview-mode.ts` 增补 `PREVIEW_MODE_LABELS` / `previewModeLabel` / `previewModeOptions`，中文名与公开端 `useWorksheetV2Session.ts:27-32` **逐字一致**（拼音字帖／汉字字帖／英语默写／中文释义练习）。
- 编辑器新增"预览题型"下拉：语文两型、英语两型；切学科走 `coercePreviewMode` 纠正；界面上显示"题型：<中文名>"。
- 测试：`tests/admin-preview-mode.test.ts` 扩到 7 条；组件测试断言"语文选项 = [汉字字帖, 拼音字帖]""切到英语后 previewMode = english-dictation 且选项换成英语两型"。

### 3b 预览用当前编辑内容：**已实现 + 已验证**
- `openPreview()` 先 `flushPendingSave()`：取消防抖定时器 → 若有脏改动则**立即保存并等待成功** → 保存失败或仍有保存在途时**取消预览**（不再拿保存前的整册糊弄）。
- 预览带 `{revision, scope}` 快照；`useAdminDraftPreview.generate(editionId, snapshot)` 把它们存进 `revision`/`scope`，预览面板显示"本次预览基于修订 N · 范围 X"。
- **诚实边界**：预览接口当前不接受修订号，也**不返回**修订号，所以"同一修订"的保证来自"先保存成功再预览"，而不是服务端校验。界面上写的也是"基于修订 N"（我方保存后的修订），不是"服务端确认过的 N"。要变成强保证需要后端配合（见第 5 节第 2 条）。
- 测试：`tests/component/admin-editor-preview-publish.test.ts`：
  - `点预览前必须先保存成功，且预览用的修订号就是保存后的修订号` —— 断言调用**顺序** `save` 在 `/draft/preview` 之前，且界面出现"本次预览基于修订 7"、`draftPreview.revision.value === 7`；
  - `保存失败时不发预览请求，而是取消预览` —— 断言 `adminRequest` **一次都没被调用**。

### 3c 只预览选中范围：**部分实现（已如实告知，未做真过滤）**
- 编辑器始终显著显示"当前范围：整册 / 单元：X / 课文：Y"；选中非整册时额外显示"后端预览接口当前只支持整册预览，本次生成仍包含全部单元／课文；已选范围仅用于标注"。
- **为什么不做真过滤（读代码得到的事实，不是猜测）**：
  - `GET /textbooks/{id}/draft/preview` 只接 `editionId`（`AdminTextbookController.preview`），没有范围参数；
  - 返回的条目是公开形状 `CurriculumEntry`，**没有 `rowId`、也没有 `clientUnitId`**；正式 `unitId` 由服务端 `DraftToBookMapper.allocateUnique` 按序分配，前端拿不到（预览不写回 `idMapping`）。按文字匹配会把重名的词一起选中 —— 那是另一种静默出错。
- 因此选择"如实标注 + 把需要后端的字段写进清单"，而不是做看起来像过滤、实际发整册的界面。
- 测试：`tests/admin-preview-scope.test.ts`（3 条）+ 组件测试 `选中单元时当前范围必须可见，并如实说明后端仍按全册生成`。

**反向验证**：把 `openPreview()` 里的 `flushPendingSave()` 换成常量 `true`（即"不 flush"）→ 上面两条预览测试**全部失败**。已实测。

## 4. 多类别（listTypes）UI + 条目级来源（设计 115 / 297 行）

### 状态：**已实现 + 已验证；且不需要新增后端草稿字段**

**关键发现（读代码确认）**：服务端 `AdminTextbookDraft.DraftEntry` 已有不透明保留映射 `preserved`，且 `DraftToBookMapper.preservedSourceRefs` 会把 `preserved.sourceRefs` 展开成正式的 `List<EntrySourceRef>`。因此条目级来源走 `preserved.sourceRefs`，**不需要草案 DTO 新字段**，`tests/admin-draft-contract.test.ts` 也不会被破坏（`preserved` 是服务端已声明的字段）。

**顺带修掉一个数据丢失级 bug**：`pairsToEntries()` 每次输入都会重建条目对象，此前**不带 `preserved`** —— 管理员改一个字就会把服务端的 `sourceRefs`、`partOfSpeech`、`senseId`、`context`、`acceptableAnswers` 等全部抹掉。现已原样浅拷贝往返，并有单测锁住。

**新增纯模块** `toolbox-web/src/lib/admin-entry-metadata.ts`
- 类别：`ADMIN_LIST_TYPE_ORDER`、`listTypeLabel`（与公开端 `WorksheetCurriculumPicker` 对齐：`vocabulary` 在英语里叫"单词表"）、`toggleListType`、`normalizeListTypes`、`matchesListTypeFilter`（空筛选 = 全部；命中任意一个所选类别）、`setListTypesForRows`、`toggleListTypeForRow`。
- 来源：`EntrySourceRef{sourceId,locator,page}`、`sourceRefsOf`、`primarySourceRefOf`、`setPrimarySourceRef`（只改**第一条**，其余保留；清空时删除第一条而不是整包清）、`withSourceRefs`（保留 `preserved` 里其它字段）、`applySourceRefToRows`（给范围设默认来源）、`sourceRefLabel`。
- 测试：`tests/admin-entry-metadata.test.ts`（12 条）。

**编辑器接线**
- 新增"表类别与来源"面板（与公开端双列编辑器分开，不改公开组件）：按类别筛选、当前范围默认类别、每行 4 个类别多选、每行来源/定位/页码录入、"应用到当前范围"。
- 批量粘贴不再写死 `listTypes: []`：使用"当前范围默认类别"，并给新行套用"当前范围默认来源"。
- **筛选与合并范围必须一致**：`entriesForNode()` 同时应用节点与类别筛选，因此 `visibleEntries`、`onRowsUpdate`、`commitRowsFor` 用的是同一个范围。若只过滤显示、合并仍按整节点，被筛掉的行会被当成"已删除"而消失。
- 元数据改动统一经 `beginMetadataChange()`（先 `onRowsUpdate(rows.value)` 提交编辑器缓冲 → 记录撤销快照）；`onRowsUpdate` 同时负责标脏与安排自动保存，所以类别/来源改动**会被真的保存**。
- 测试：`tests/component/admin-editor-metadata.test.ts`（7 条），含"筛选状态下编辑不得删掉被筛掉的行"与"类别改动触发自动保存且新类别进入请求体"。
- 单测：`tests/admin-textbook-draft.test.ts` 增 2 条（`preserved` 往返 + 浅拷贝）。

**反向验证**
- 把 `beginMetadataChange()` 里的 `onRowsUpdate(rows.value)` 删掉 → `类别改动会触发自动保存…` **失败**（实测）。
- `matchesListTypeFilter` 的空筛选用例、`sourceRefsOf` 对非法结构的用例，分别锁住"空筛选 = 全显示"与"不猜结构"。

## 5. 需要后端配合的字段清单

| # | 接口 | 字段 | 类型 / 形状示例 | 当前后端状态（读代码得到） | 前端现状 |
| --- | --- | --- | --- | --- | --- |
| 1 | `POST /admin/v1/textbooks/{id}/publish` | 正文 `draftRevision` | `number`，例 `9` | `AdminTextbookController.publish` 用 `@RequestParam(name="draftRevision")` 读，**没有 `@RequestBody`** | **正文与查询串都已发**（正文为设计契约；查询串兼容现状） |
| 1 | 同上 | `baseContentVersion` | `string \| null`，例 `"demo-book@3"` | 服务端内部有基线校验（`publishLocked` 里的 `baseline`），但**不从请求体读** | 已发（来自 `GET /draft` 的 `baseContentVersion`） |
| 1 | 同上 | `changeNote` | `string`（可为 `""`），例 `"补充附录来源"` | **未接受**：`AdminTextbookService.publishLocked` 把审计说明硬编码成 `"发布修订 " + revision` | 已发；需要后端写入版本记录/审计 |
| 1 | 同上 | 语义 | — | 建议：正文 DTO `{draftRevision, baseContentVersion, changeNote}`，`draftRevision` 与 `stored.revision` 不符 → 409（details 建议统一为 `{reason, expectedRevision, actualRevision}`，与保存冲突一致） | 前端只解析 `details.expectedRevision`，解析不到就显示"未知"，不猜 |
| 2 | `GET /admin/v1/textbooks/{id}/draft/preview` | 请求参数 `draftRevision` | `number`，例 `7` | **不接受**；返回体里也没有修订号 | 前端只把修订号用于**界面标注**，不发送（发了也会被忽略，反而像做了强校验） |
| 2 | 同上 | 请求参数 `nodeKind` / `nodeKey` | `nodeKind=all\|unit\|lesson`，`nodeKey=clientUnitId` 或 `clientLessonId` | **不接受**；返回的 `CurriculumEntry` 没有 `rowId`/`clientUnitId`，前端无法可靠映射 | 未发送；界面如实写"仍按全册生成" |
| 2 | 同上 | 响应 `draftRevision` | `number` | 无 | 建议加入：有它才能把"预览的就是我保存的那一版"从"我方先保存"升级为**服务端可校验**（`draftRevision` 不符 → 409/422） |
| 3 | 草稿 DTO `DraftEntry` | **无需新增字段** | `preserved.sourceRefs: [{sourceId, locator, page}]` | 已支持：`DraftToBookMapper.preservedSourceRefs` 把它展开为 `List<EntrySourceRef>` | 已按此形状写入（`page` 为 `number` 或 `null`，`sourceId`/`locator` 空时发 `null`） |
| 4 | `POST /admin/v1/textbooks/{id}/draft`（PUT 同） | **无需新增字段** | 条目字段集不变；新增的 `preserved` 已在 DTO 中 | `fail-on-unknown-properties=true` 不受影响 | `tests/admin-draft-contract.test.ts` 保持通过 |

**必须声明的验证层级**：上表"当前后端状态"来自**读代码**（`AdminTextbookController.java`、`AdminTextbookService.java`、`DraftToBookMapper.java`、`AdminTextbookDraft.java`），**不是**对运行中的服务端做的端到端验证；本线没有启动后端，也没有改后端。

## 6. 未做 / 部分做的真实原因

1. **预览的真范围过滤（第 3 项，部分）**：卡在接口能力上，而不是前端工作量。理由见 3c：接口无范围参数，返回体缺 `rowId`/`clientUnitId`，前端映射会靠猜。已做的是"范围始终可见 + 限制如实写明 + 后端字段清单"。
2. **`SaveState.fail()` 的重试路径（第 1 项，有意不接）**：接了会让失败后**已删除的行被旧 payload 复活**（设计 §3.5 明令禁止）。若将来要接，正确做法是加一条"仅当冻结之后没有任何新编辑时才允许复用 payload"的守卫（目前 `SaveState` 没有暴露"当前编辑代次"，需要给它加一个只读 getter）。
3. **首版不做自动三方合并**：设计 171 行明确"首版不做"，实现为"展示差异 + 用户选择读取最新 / 恢复本地改动"。
4. **条目级来源只编辑"第一条"**：界面上没有"多条来源"的编辑入口；`sourceRefsOf`/`setPrimarySourceRef` 会**保留**多余记录，不做整包替换（宁可少编辑，不可静默丢数据）。
5. **`AdminApiError.details` 只解析 `expectedRevision`**：发布冲突的 details 形状是 `{reason, expected, actual}`，与保存冲突不同；未做跨形状猜测，读不到就显示"未知"。若后端统一形状，前端可以展示"服务器当前修订"。

## 7. 本轮改动文件

新增：
- `toolbox-web/src/lib/admin-draft-conflict.ts`
- `toolbox-web/src/lib/admin-entry-metadata.ts`
- `toolbox-web/src/lib/admin-preview-scope.ts`
- `toolbox-web/src/lib/admin-publish-request.ts`
- `toolbox-web/tests/admin-draft-conflict.test.ts`
- `toolbox-web/tests/admin-entry-metadata.test.ts`
- `toolbox-web/tests/admin-preview-scope.test.ts`
- `toolbox-web/tests/admin-publish-request.test.ts`
- `toolbox-web/tests/component/admin-editor-conflict.test.ts`
- `toolbox-web/tests/component/admin-editor-preview-publish.test.ts`
- `toolbox-web/tests/component/admin-editor-metadata.test.ts`

修改：
- `toolbox-web/src/views/admin/TextbookEditorView.vue`（接线：冲突面板、预览设置、修改摘要、类别与来源面板）
- `toolbox-web/src/lib/admin-preview-mode.ts`（补题型中文名与选项）
- `toolbox-web/src/lib/admin-textbook-draft.ts`（`preserved` 字段 + 往返）
- `toolbox-web/src/lib/admin-error-messages.ts`（`admin.conflict` 文案指向冲突面板）
- `toolbox-web/src/composables/useAdminDraftPreview.ts`（`generate` 接收修订号/范围快照）
- `toolbox-web/src/services/admin-textbooks-api.ts`（`publishDraft` 发设计契约请求体）
- `toolbox-web/tests/admin-preview-mode.test.ts`、`toolbox-web/tests/admin-textbook-draft.test.ts`（扩充断言）

## 8. 复现命令

```bash
cd toolbox-web
npm run typecheck                 # 干净
npm test                          # 715 通过
npm run test:component            # 39 通过
npm run build:production          # 零告警
# 针对性
node --import tsx --test tests/admin-draft-conflict.test.ts tests/admin-entry-metadata.test.ts \
  tests/admin-preview-scope.test.ts tests/admin-publish-request.test.ts tests/admin-preview-mode.test.ts
npx vitest run tests/component/admin-editor-conflict.test.ts \
  tests/component/admin-editor-preview-publish.test.ts tests/component/admin-editor-metadata.test.ts
```
