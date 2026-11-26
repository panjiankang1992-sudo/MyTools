# 设计首期缺失内容补齐：管理端版本记录抽屉 + 键盘路径

- 日期：2026-10-07
- 目标（仅本机回环）：前端 `http://127.0.0.1:4173`，后端 `http://127.0.0.1:23199`
  （profile `local,local-admin`，假管理员 `local-admin` / `local-admin`），
  隔离 MySQL 容器 `toolbox-mysql-test`（8.4.11，`127.0.0.1:13316`）
- 设计依据：`docs/design/2026-10-05-toolbox-admin-textbooks/design-and-implementation.md`
  - 第 85 行：列表「操作」列含「版本记录」
  - 第 166 行：「版本记录放右侧抽屉：版本号、发布人、时间、修改摘要和内容对比。首版提供只读历史；**恢复某版将来应复制为新草稿发布，不回写旧版本**。」
  - 第 176 行：「…**弹窗关闭焦点回触发入口**…键盘可完成创建／编辑／保存。」
- **未改动**：后端（`service/toolbox-service/`）一行未动；未动 `2026-10-05` / `2026-10-06` 目录；
  未动本目录内他人产出的文件（`README.md`、`status-matrix.*`、`final-classification.md` 等）。
- 新增文件：
  - `toolbox-web/src/components/admin/VersionHistoryDrawer.vue`
  - `toolbox-web/src/services/admin-curriculum-api.ts`（**扩展现有文件**，新增版本历史接口）
  - `toolbox-web/src/views/admin/TextbookEditorView.vue`（接入入口 + 修正过时注释）
  - `toolbox-web/tests/component/admin-version-history-drawer.test.ts`（9 条）
  - `toolbox-web/tests/component/admin-editor-version-drawer.test.ts`（2 条）
  - `toolbox-web/tests/e2e/local-admin-keyboard-version-drawer.mjs`（17 条断言）
  - 本文档、`screenshots/version-drawer-*.png`、`evidence/browser/keyboard-version-drawer.json`

---

## 0. 结论速览

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 任务 1：版本记录抽屉（列表／摘要／原始字节／来源快照／只读） | **通过** | 11 条组件测试 + 17 条真浏览器断言，均有反向验证 |
| 任务 1 附带：`TextbookEditorView.vue` 过时注释 | **通过** | 注释里"尚未实现"只剩**图片识别（OCR 录入）**一项属实；已重写 |
| 任务 1 缺口（如实记录，未做）：修改摘要 `changeNote` 后端不返回 | **缺口·后端** | 界面显示"未记录"；详见 §1.6 |
| 任务 1 缺口（如实记录，未做）：设计 166 行「内容对比」 | **未做** | 抽屉底部如实标注；详见 §1.6 |
| 任务 2：键盘路径（Tab／Escape） | **通过** | 17/17 真浏览器断言；焦点**真能进抽屉**、**Escape 真能关**、关闭后焦点**回到触发按钮** |
| 焦点陷阱／不可达控件 | **未发现** | 24 次 Tab 全部落在抽屉内可交互元素上，0 次逃逸；详见 §2.3 |

矩阵建议（仅建议，本文件不改 `status-matrix.*`）：`C03` → 通过或维持部分；`D04` → 部分（键盘路径已做，深色主题仍未验证）；`D05` 不受本次影响。

---

## 1. 任务 1：版本记录抽屉

### 1.1 实现位置与形态

- 组件：`src/components/admin/VersionHistoryDrawer.vue`（`NDrawer` placement="right" + `NDrawerContent`，与既有 `App.vue` 的移动端抽屉同一套 Naive UI 风格）。
- 入口：编辑页头部操作栏新增「版本记录」按钮（设计第 85 行「更多菜单中的版本记录」；
  这里放在编辑页头部而不是列表行内菜单，因为抽屉要展示的是**当前这册**的历史，而编辑页已经确定了册）。
  新增教材（无 `editionId`）时按钮**禁用而不是隐藏**——隐藏会让键盘用户根本不知道有这个功能。
- 服务层放在 `src/services/admin-curriculum-api.ts`（**先读后决定**）：该文件已经是
  "`/admin/v1/curriculum/*` 管理端只读/维护客户端"，版本历史三个端点同属该前缀；
  新建一个文件会重复 `adminRequest` 的错误分类与 CSRF 约定。
- 新增三个服务函数：
  - `fetchVersionHistory(editionId, signal)` → `GET /admin/v1/curriculum/versions/{editionId}`
  - `fetchVersionSourceSnapshot(editionId, contentVersion, signal)` → `GET .../{contentVersion}/source-snapshot`
  - `buildVersionBytesUrl(editionId, contentVersion)` → `.../{contentVersion}/bytes` 的**下载地址**

> 为什么 `bytes` 走 URL 而不是 `adminRequest`：该端点返回 `application/octet-stream` **原始字节**
> （带 `X-Content-Sha256` / `X-Content-Version` 头），不是管理端的 `{code,data}` 信封；
> 用 `adminRequest` 解析会把一份正常的历史正文判成 `HTTP_200` 错误。
> 管理会话是同源 HttpOnly Cookie，`<a href download>` 会自带凭据。

### 1.2 后端契约（本地回环实测原文）

`GET /admin/v1/curriculum/versions/local-acceptance-2026-10-07`（管理员 Cookie）：

```json
{"code":"OK","data":[
 {"editionId":"local-acceptance-2026-10-07","contentVersion":"1.0.0",
  "contentSha256":"5b0e355f0988e438e31b5a531532f4157bcafe1872ac413c1d02e3620c855c4f",
  "byteLength":1668,"publishedBy":"local-admin-1001","publishedAt":1791307644000,
  "hasSourceSnapshot":true,
  "sourceSnapshotSha256":"2483ffcd2139eb8bd2332781a6809b69f04711a64da48148bfe638d41e11ac9a"},
 …1.0.1、1.0.2 同形…],"requestId":"…"}
```

- **确认没有 `changeNote` 字段**（`AdminTextbookService.VersionSummary` 也没有）。
- 空历史实测：`accept-cn-2026a` 返回 `"data":[]`。
- `bytes` 实测响应头：`200`、`Content-Type: application/octet-stream`、
  `X-Content-Sha256: db742dae…`、`X-Content-Version: 1.0.2`、`Content-Length: 1668`。
- `source-snapshot` 实测：`data.snapshotJson` 是**字符串化的 JSON**，另带 `sha256`；
  无快照的版本返回 `404 TOOLBOX_ADMIN_006`（`details.reason=sourceSnapshotMissing`）。

### 1.3 界面行为（逐条对应任务要求）

| 任务要求 | 实现 | 测试 |
| --- | --- | --- |
| 1. 列历史（时间倒序），显示版本号、发布时间、发布人 | `orderedVersions` 按 `publishedAt` 倒序、同刻按版本号倒序；`publishedAt=0` 显示"时间未记录"（**不渲染成 1970 年**） | `列出历史版本：版本号、发布时间、发布人都在（时间倒序）` |
| 2. 修改摘要；后端不返回则显示"未记录"，不编造 | 有 `changeNote` 就显示；没有就显示"未记录"，并在抽屉底部如实写明后端缺口 | `修改摘要后端未返回时显示"未记录"，不得编造`、`后端将来返回 changeNote 时能显示出来` |
| 3. 下载/查看该版本原始字节 | `<a href=.../bytes download data-download-version="1.0.2">` | `该版本原始字节：下载链接指向 /bytes 端点…` + e2e 里**真取字节并自算 SHA-256** |
| 4. 标出哪些版本有来源快照，有快照时可看快照 JSON | `来源快照：有（SHA-256 …）/ 无`；有快照才出现"查看来源快照"，点开后拉取并格式化 JSON；拉取失败**显示错误** | `区分有／无来源快照…`、`点"查看来源快照"会拉取并展示…失败时显示错误` |
| 5. 只读，不得有恢复／下架／回滚 | 抽屉内**只有** `<a download>` 与"查看来源快照"两个动作；无任何写操作 | `只读：抽屉里的可交互控件不得出现撤销／恢复／下架／回滚…`（组件）+ e2e 同项断言 |
| 6. 更新过时注释 | 见 §1.5 | 注释审查（人工） |

### 1.4 测试与通过数

```bash
cd toolbox-web
npx vitest run tests/component/admin-version-history-drawer.test.ts
#  Test Files  1 passed (1)   Tests  9 passed (9)
npx vitest run tests/component/admin-editor-version-drawer.test.ts
#  Test Files  1 passed (1)   Tests  2 passed (2)
```

组件测试**挂载真实组件**（`VersionHistoryDrawer.vue` / `TextbookEditorView.vue`），
只把服务层替换成 spy；`NDrawer`/`NDrawerContent`/`NAlert`/`NButton` 全是真身。

> **一条测试写法上的坑（记下来避免下一个人重踩）**：`NDrawer` 把内容 Teleport 到 `document.body`，
> 所以断言必须查 `document.querySelector(...)`，**不能**用 `wrapper.find(...)`
> ——第一版 9 条里 8 条因此失败（`Cannot call trigger on an empty DOMWrapper`），而产品是对的。
> 另外 `watch(show, …, { immediate: true })` 是必需的：抽屉以 `show=true` 首次挂载时
> 没有"变化"事件，不 immediate 就永远不加载历史。

### 1.5 过时注释的修正（`TextbookEditorView.vue`）

改动前的原文（用户任务里引用的那句）：

```
 * 本轮实现：教材信息表单 + 双列内容录入 + 草稿保存 + 校验 + 发布。
 * 尚未实现：左侧单元／课文目录树、批量粘贴、图片识别、版本记录、草稿预览。
```

逐项核对后的实际情况：

| 原注释项 | 是否属实 | 依据 |
| --- | --- | --- |
| 左侧单元／课文目录树 | **已实现** | 模板里的 `aside.outline-pane`（`tree` / `onAddUnit` / `onRemoveUnit` …） |
| 批量粘贴 | **已实现** | `pasteText` + `onBulkPaste()` + `parseBulkRows` |
| 图片识别 | **本页确实没有** | 全文件只有注释里出现 `ocr/图片`；图片录入界面在公开链路 `components/worksheet/WorksheetImageInput.vue` |
| 版本记录 | **本次已实现** | `VersionHistoryDrawer.vue` + 编辑页入口 |
| 草稿预览 | **已实现** | `previewOpen` + `useAdminDraftPreview` + `openPreview()` |

改后的注释只保留**真实未达成项**（图片识别录入），并写明
「版本记录里"内容对比"与"修改摘要"的后端字段缺口见抽屉与验证文档」。

### 1.6 如实记录的缺口（**未编造、未绕过**）

1. **修改摘要 `changeNote` 后端不返回。**
   发布请求体确实带 `changeNote`（`src/lib/admin-publish-request.ts`，且
   `admin-publish-request.test.ts` 覆盖"去空白并截断到上限"），
   但 `AdminTextbookService.VersionSummary` 没有该字段，2026-10-07 本地回环实测响应里也没有。
   因此抽屉对**每一个**版本显示"未记录"，并在底部写明：
   「修改摘要：后端当前不返回该字段（发布请求体的 `changeNote` 未被持久化），
   因此所有版本都显示"未记录"——这是如实记录的缺口，不是界面漏读。」
   界面按**可选字段**读取 `changeNote`，服务端将来补上就能直接显示（有测试覆盖这条）。
2. **设计 166 行「内容对比」本期不做。** 抽屉底部如实标注「内容对比（设计 166 行）本期不做」。
   没有做一个"看起来像对比、实际只是并列展示"的东西。本次任务清单里也没有这一项。
3. **只读边界按要求保留**：没有"恢复该版本为新草稿"、没有下架、没有回滚
   （设计 166 行明确"恢复某版将来应复制为新草稿发布"，本期不做）。这有正反两面的断言：
   组件测试与 e2e 都检查抽屉内**可交互控件**里不出现恢复／回滚／下架／删除／覆盖。

---

## 2. 任务 2：键盘路径（真浏览器，CDP `Input.dispatchKeyEvent`）

脚本：`toolbox-web/tests/e2e/local-admin-keyboard-version-drawer.mjs`
（强制回环：用 `requireLoopbackTarget`，非回环直接退出码 2；**全程不发真实鼠标事件**）

```bash
cd toolbox-web
ADMIN_COOKIE="toolbox_admin_local=<本地会话 Cookie>" \
E2E_TARGET=http://127.0.0.1:4173 \
node tests/e2e/local-admin-keyboard-version-drawer.mjs
# 通过 17/17   exit 0
```

会话 Cookie 由 `curl` 打 `/admin/v1/auth/csrf` → `/admin/v1/auth/login` 取得
（`X-Toolbox-Admin-Csrf` 头 + `Origin: http://127.0.0.1:4173`），只用于本机回环。

### 2.1 断言清单（17 条，全部通过）

```
PASS 进入管理编辑页（注入会话 Cookie 后直达）
PASS 编辑页已加载该册草稿（头部出现修订信息）
PASS 可以把焦点放到「版本记录」按钮上（触发入口本身可聚焦）
PASS 回车可打开版本记录抽屉                       事件=["keydown:Enter","click:-"]
PASS 抽屉列出了该册历史版本                       版本行=3 版本号=["1.0.2","1.0.1","1.0.0"]
PASS 该版本原始字节可下载（200 + octet-stream），字节与自算 SHA-256 同 X-Content-Sha256 一致
PASS 打开后焦点进入抽屉（在 role="dialog" 或抽屉内容里）
PASS Tab 在抽屉内依次落在可交互元素上（不是 body、不是隐藏哨兵）   异常落点=0
PASS Tab 不会离开抽屉（焦点陷阱生效，未落到页面背景）              离开抽屉的落点=0
PASS Escape 可关闭版本记录抽屉                     关闭=true
PASS 关闭后焦点回到「版本记录」触发按钮（设计第 176 行）          activeElement 是触发按钮=true
PASS 关闭后可再次用键盘打开（Escape 没有把入口弄坏）            再次打开=true
PASS Tab 可达「查看来源快照」按钮                  用了 2 次 Tab
PASS 回车可展开该版本来源快照 JSON                 展开=true 头={"snapshotSchemaVersion": 1, …
PASS 抽屉是只读的（控件里没有恢复／回滚／下架／删除／覆盖）        写操作控件=[]
PASS 键盘路径期间无渲染进程异常                    pageErrors=[]
PASS 键盘路径期间无异常响应（4xx/5xx）             badResponses=[]
```

原始留档：`evidence/browser/keyboard-version-drawer.json`（含完整 `tabTrail`、控制台、
失败请求、异常响应）。截图：`screenshots/version-drawer-keyboard-open.png`、
`…-after-escape.png`、`…-snapshot.png`。

### 2.2 焦点是否**真能**进抽屉、**真能**关掉 —— 是

- **进得去**：回车打开后 `document.activeElement` =
  `<button class="n-base-close n-base-close--absolute n-drawer-header__close" tabindex="0" aria-label="close">`，
  `el.closest('[role="dialog"]')` 非空。
- **关得掉**：发一个 `Escape`，`.n-drawer` 与 `[data-testid="version-drawer"]` 都从 DOM 消失。
- **焦点回来了**：关闭后 `document.activeElement ===` 那个「版本记录」按钮（实测 `true`），
  满足设计第 176 行「弹窗关闭焦点回触发入口」。
- 关闭后**再按一次回车能再次打开** —— 入口没有被 Escape 弄坏。

### 2.3 实测 Tab 顺序（前 24 步，抽屉内）

三版本（1.0.2 / 1.0.1 / 1.0.0，本地库 `local-acceptance-2026-10-07`，
每版都有来源快照），落点：

```
1.  A「下载该版本原始字节」        13. BUTTON「查看来源快照」
2.  BUTTON「查看来源快照」          14. BUTTON「close」
3.  A「下载该版本原始字节」        15. A「下载该版本原始字节」
4.  BUTTON「查看来源快照」          16. BUTTON「查看来源快照」
5.  A「下载该版本原始字节」        17. A「下载该版本原始字节」
6.  BUTTON「查看来源快照」          18. BUTTON「查看来源快照」
7.  BUTTON「close」               19. A「下载该版本原始字节」
8.  A「下载该版本原始字节」        20. BUTTON「查看来源快照」
9.  BUTTON「查看来源快照」          21. BUTTON「close」
10. A「下载该版本原始字节」        22. A「下载该版本原始字节」
11. BUTTON「查看来源快照」          23. BUTTON「查看来源快照」
12. A「下载该版本原始字节」        24. A「下载该版本原始字节」
```

- **焦点陷阱存在且有效**：24 步里 `0` 步离开 `[role="dialog"]`，`0` 步落在 `body`／隐藏哨兵上。
  顺序呈周期性（3 个版本 × 2 个控件 + 关闭按钮 = 7 个落点循环）。
- **顺序说明（不是缺陷，但如实写出）**：打开后焦点落在**关闭按钮**上，
  因此第一次 Tab **跳过**了关闭按钮，先到第 1 个版本的下载链接，关闭按钮在每次循环末尾才再次出现。
  这是 Naive UI 焦点陷阱「焦点已在第一个元素上，Tab 走向下一个」的正常语义。
- **不可达控件**：未发现。抽屉里每个控件（3 × 下载链接 + 3 × 查看来源快照 + 关闭按钮）都在 Tab 序列里出现，
  「查看来源快照」只用 2 次 Tab 就能到达并被回车激活。
- **触发按钮在页面 Tab 顺序中的位置**：从它 `Shift+Tab` 退到 `BUTTON「预览草稿」`，
  再 `Tab` 回到 `BUTTON「版本记录」` —— 入口在正常文档顺序里，没有被孤立。

### 2.4 一条**驱动侧**的真实发现（不是产品缺陷，但会伪装成缺陷）

同一台无头 Chrome、同一个按钮，按键配方不同结果完全不同（本轮实测）：

| 配方 | 页面收到的事件 | 结果 |
| --- | --- | --- |
| `rawKeyDown` + `keyUp` | `keydown`、`keyup` | **不激活按钮**，抽屉不开 |
| `rawKeyDown` + `char` + `keyUp` | 会**多触发**（实测 3 次 click） | 抽屉打开后又立刻被那次多余激活点到了**关闭按钮**（现象像"回车打开又立刻关掉"） |
| `keyDown(text='\r')` + `keyUp` | `keydown`、`click` | 正好一次，抽屉打开 ✓ |

结论：`Input.dispatchKeyEvent` 发回车必须用 **`keyDown` 带 `text`** 这一种，
`char` 与 `rawKeyDown` 都不能用。这一条写进了脚本的注释里。
（记录它的价值：第 2 行那个现象极易被误判成"焦点进抽屉导致回车把抽屉关了"的产品缺陷，
实际是驱动发错了事件。）

---

## 3. 反向验证（**实际失败输出**）

方法：在**最终实现**上分别注入一个缺陷 → 跑对应测试 → 看它是否真的失败 → 还原。
每组都在还原后 `diff` 确认与最终实现逐字节一致。

### E1 删掉错误显示分支（让失败静默成空列表）

补丁：从模板里删掉 `v-else-if="hasError"` 那个 `<NAlert data-testid="version-error">`。

```
===== E1: 删掉错误显示分支：失败静默成空列表  (exit 1) =====
      Tests  1 failed | 8 passed (9)
 FAIL  tests/component/admin-version-history-drawer.test.ts > 版本记录抽屉（设计 3.6 第 166 行） > 服务层报错时必须显示错误原文（不能静默成空列表）
AssertionError: expected null not to be null
```

### E2 把列表渲染改成"永远空列表"

补丁：`orderedVersions = computed(() => [...versions.value].sort(...))`
→ `computed(() => ([] as AdminVersionSummary[]).sort(...))`。

```
===== E2: 把列表渲染改成永远空列表  (exit 1) =====
      Tests  7 failed | 2 passed (9)
 FAIL  … > 列出历史版本：版本号、发布时间、发布人都在（时间倒序）
AssertionError: expected ' 该册的全部已发布版本，按发布时间倒序。本抽屉是只读的：不提供恢复旧版、下…' to contain '1.0.0'
Expected: "1.0.0"
Received: " 该册的全部已发布版本，按发布时间倒序。本抽屉是只读的：不提供恢复旧版、下架或回滚。  修改摘要：后端当前不返回该字段（发布请求体的 changeNote 未被持久化）， 因此所有版本都显示"未记录"——这是如实记录的缺口，不是界面漏读。 内容对比（设计 166 行）本期不做。 "
 FAIL  … > 区分有／无来源快照：有快照才出现"查看来源快照"，无快照显示"无"
AssertionError: expected [] to deeply equal [ '2.0.0' ]
- Expected
+ Received
- [
-   "2.0.0",
- ]
+ []
 FAIL  … > 修改摘要后端未返回时显示"未记录"，不得编造
AssertionError: expected null not to be null
```

### E3 不区分有无来源快照（一律当成"没有快照"）

补丁：把标记"有快照"的分支条件 `<template v-if="version.hasSourceSnapshot">` 改成 `v-if="false"`。

```
===== E3: 不区分有无来源快照（一律当成没有快照）  (exit 1) =====
      Tests  1 failed | 8 passed (9)
 FAIL  … > 区分有／无来源快照：有快照才出现"查看来源快照"，无快照显示"无"
AssertionError: expected ' 该册的全部已发布版本，按发布时间倒序。本抽屉是只读的：不提供恢复旧版、下…' to contain 'snap-200'
Expected: "snap-200"
Received: " … 2.0.02026/10/7 01:27:24（1791307644000）发布人：local-admin-1001修改摘要未记录原始字节1668 字节来源快照无下载该版本原始字节查看来源快照1.0.0… 来源快照无下载该版本原始字节该版本没有来源快照 修改摘要：…"
```

### E4 把加载错误吞掉（`catch` 里不记 `loadError`）

补丁：`catch` 里删掉 `loadError.value = cause;`（只清空列表）。

```
===== E4: 把加载错误吞掉：catch 里什么都不记  (exit 1) =====
      Tests  1 failed | 8 passed (9)
 FAIL  … > 服务层报错时必须显示错误原文（不能静默成空列表）
AssertionError: expected null not to be null
```

> 一个**结果与预期不同**的实验，如实记录：最初我还试过"删掉编辑页里自己写的
> 焦点归还逻辑"这一组，期望组件测试失败，结果 **2 passed** ——
> 说明那条断言测不出"谁归还了焦点"（Naive UI 的 `VFocusTrap` 自己就会归还）。
> 于是进一步在**真浏览器**里删掉自己那段重跑键盘脚本，仍然 17/17 通过。
> 结论：那段逻辑是**重复实现**，已删除（见 §4），
> 由 §3 之外的这条"实删 + 真浏览器仍通过"作为反向验证。

---

## 4. 一处按证据简化掉的实现（不是遗留缺陷）

第一版在编辑页里自己写了"关闭后 `nextTick` 聚焦触发按钮"（`versionTriggerRef` + `nextTick`）。
反向验证显示：删掉它之后，组件测试与**真浏览器 17/17 键盘路径**都仍然通过
——因为 `NDrawer` 的 `VFocusTrap` 自带 `returnFocusOnDeactivated`，实测会把焦点放回
「版本记录」按钮。因此最终实现**删除了这份重复逻辑**，只保留
`v-model:show="versionDrawerOpen"`，并在注释里写明"若将来换掉抽屉实现，
`关闭后焦点回到触发按钮` 这条断言会立刻发现"。

（顺带：`tests/background-tab-safety.test.ts` 会拦截"源码里新增的 `new Promise(...setTimeout)`"。
我一度在焦点重试里用了 16ms 定时等待，被它挡下；这也是最终没有保留任何定时重试的原因之一
——正确性不该依赖定时等待。这一点该测试做得对。）

---

## 5. 最终验证（全绿，退化为基线之上的增量）

命令与结果（在 `toolbox-web/` 下执行）：

```bash
npm test
#  # tests 715   # pass 715   # fail 0        （基线 715 → 715，未回归）
npx vitest run
#  Test Files  11 passed (11)   Tests  61 passed (61)
#                                              （基线 50 → 61，新增 11 条）
npm run typecheck
#  退出码 0，无输出
npm run build:production
#  ✓ built in 1.22s，0 告警
#  （`grep -i "warn\|error"` 只命中产物文件名 dist/assets/admin-error-messages-*.js）
```

`npm test` / `npx vitest run` 各连跑 3 次结果一致（避免把偶发当稳定）。

真实浏览器（回环）：

```bash
ADMIN_COOKIE="toolbox_admin_local=<Cookie>" E2E_TARGET=http://127.0.0.1:4173 \
  node tests/e2e/local-admin-keyboard-version-drawer.mjs      # 通过 17/17，退出码 0
```

留档时间：`evidence/browser/keyboard-version-drawer.json` 与 3 张截图均为 **03:35**，
对应**最终实现**（即 §4 简化、去掉定时重试之后的那份源码；`diff` 已确认之后未再改动）。

> **环境变动记录（如实）**：03:38 左右，本机前端 `127.0.0.1:4173` 上的预览进程**已不在**
> （`lsof -iTCP:4173 -sTCP:LISTEN` 无输出，`curl http://127.0.0.1:4173/` 返回 000），
> 后端 `127.0.0.1:23199` 仍在（`java` PID 12427 监听）。
> 按任务边界「不要启动替代服务器」，我**没有**再起一个 4173 服务；
> 若需要重跑键盘脚本，请先让环境里的预览进程回到 4173（`cd toolbox-web && npm run preview`），
> 脚本本身不需要任何改动。

## 6. 未达成项与边界（如实）

1. **修改摘要 `changeNote` 仍是空的**：后端不返回该字段（§1.6 第 1 条）。
   本次**不改后端**，因此界面只能显示"未记录"。这是缺口，不是通过。
2. **设计 166 行的「内容对比」未做**：本次任务清单未要求，且设计把它与"修改摘要"并列，
   实现它需要后端提供逐版本正文差异或前端拉两份 `bytes` 自行比对；
   抽屉底部已如实标注"本期不做"。
3. **未验证项**：只在 `1280×900` 视口下跑过键盘路径（抽屉是固定 520px 宽、
   右侧浮层，不参与响应式布局的挤压）。窄屏／深色主题下的抽屉观感**没有**本次证据，
   不宣称通过（`D04` 的深色主题仍然未验证，见 `hit-targets-and-dark.md`）。
4. **未执行**：生产只读核对、部署、生产建账号／发布教材 —— 按边界一律未做。
5. **本目录其它文件未改**：`status-matrix.md/json`、`final-classification.md`、
   `README.md`、`2026-10-05/*`、`2026-10-06/*` 均未触碰。矩阵状态建议见本文开头。

## 7. 对矩阵 `C03` / `D04` / `D05` 的建议状态

| 矩阵项 | 现状（本目录既有记录） | 本次新增证据 | 建议 |
| --- | --- | --- | --- |
| **C03** 草稿与版本分离 | 部分（草稿与版本分离；旧版本字节逐字节可取回） | 管理端**有了版本记录入口**：3 个版本按时间倒序列出（1.0.2/1.0.1/1.0.0），版本号、发布人、时间、字节长度、来源快照哈希都在；`bytes` 端点在同源会话里真取回 1668 字节并自算 SHA-256 与 `X-Content-Sha256` 一致；抽屉**只读**，没有把"恢复旧版"做成写操作 | **建议：通过（就"分离 + 可查 + 原始字节可取回"而言）**。若要维持"部分"，剩余缺口只有"修改摘要 / 内容对比"（属设计 166 行的另一句）。可写成：通过，备注 `changeNote` 后端缺口 |
| **D04** 响应式与可访问性 | 部分（五尺寸截图 + 命中区已复测；**键盘路径仍未做**；深色主题未验证） | **键盘路径已做**：纯 CDP 键盘（Tab/Enter/Escape）走通 进入编辑页 → 打开版本记录抽屉 → 抽屉内 Tab → Escape 关闭；焦点真进抽屉（关闭按钮）、真被陷阱留住（24 步 0 次逃逸、0 个不可达控件）、关闭后真回到触发按钮；17/17 通过并有原始 JSON 留档 | **建议：维持"部分"**，但把备注里的"键盘路径仍未做"改为"**键盘路径已完成**（17/17，脚本可重跑）"；真正剩下的未验证项是**深色主题**（与本次无关）与"抽屉在窄屏／深色下的观感" |
| **D05** 预览与打印 | 部分（四题型 PDF 已实测；管理端打印链路已有 14 条测试） | 本次**未触碰**预览／打印；仅在同一编辑页头部新增了一个按钮（「版本记录」），不影响预览链路（`npm test` 715/715、`vitest` 61/61 均通过） | **建议：不变**（本次不构成对 D05 的新证据，也不构成回归） |

---

## 4. 独立复跑与一次**驱动配方缺陷**的修正（第 137–138 轮，由父会话补记）

### 4.1 我复跑时得到的是 **16/17**，不是本文件上面写的 17/17

代理在 03:35 跑出 17/17 并留档 ✓。但它在收尾报告里同时写道：
「03:38 左右 `127.0.0.1:4173` 的前端预览进程**已不在**」。

我把 **4173 恢复后复跑同一条脚本**，得到的是：

```
FAIL Escape 可关闭版本记录抽屉 —— 关闭=false
通过 16/17
```

**同一条脚本、同一份实现、两次结果不同** ✗ —— 即这一条当时**是 flaky 的** ✓。

### 4.2 根因：**脚本**用了错的键盘事件配方 + 固定等待

```javascript
const pressEscape = () => dispatch([
  { type: 'rawKeyDown', key: 'Escape', ... },   // ← rawKeyDown
  { type: 'keyUp', key: 'Escape', ... }
]);
...
await pressEscape();
await sleep(900);                               // ← 固定 900ms，不是等待条件
const closed = ... !document.querySelector('[data-testid="version-drawer"]');
```

**两处问题，都在测量方法上，不在产品上** ✗ ✓：

1. **`rawKeyDown` 不触发目标处理器** ✗ —— 这正是**代理自己在报告里已经发现的同一个坑** ✓：
   它写明「CDP 发回车必须用 `keyDown(text='\r')+keyUp`；**`rawKeyDown+keyUp` 不激活按钮**」✓ ——
   但那条结论只被用在了 **Enter** 上 ✗，**Escape 仍然用着 `rawKeyDown`** ✗ ✓；
2. **固定 900ms 等待** ✗ —— 环境慢一点就被读成"没关上" ✗，那是测量的缺陷 ✗。

### 4.3 修正与验证

| 改动 | 内容 |
| --- | --- |
| Escape 改用 `keyDown`（带 `text: '\u001b'`）+ `keyUp` | 与 Enter 同一配方 ✓ |
| 固定 `sleep(900)` 改为**等待条件**（轮询抽屉消失，最多 5 秒） | 慢环境不再被误判 ✓ |

**连续两次复跑**：

```
第 1 次：PASS Escape 可关闭版本记录抽屉 —— 关闭=true    通过 17/17
第 2 次：PASS Escape 可关闭版本记录抽屉 —— 关闭=true    通过 17/17
```

**稳定通过** ✓ —— 且**产品代码一行未改** ✗ ✓（说明"Escape 能关"本来就成立 ✓）。

### 4.4 这件事该怎么记

| 说法 | 对错 |
| --- | --- |
| "产品在 Escape 关闭上有缺陷" | ❌ **不对** |
| "这条验收曾经 flaky，根因是脚本的键盘配方与固定等待" | ✅ **对** |
| "修正后连续两次 17/17" | ✅ **对** ✓ |

**与本文件 §2 记录的 Enter 配方坑是同一个根因** ✓ ——
**说明"用 CDP 驱动键盘"这件事本身需要一份正确的配方** ✓，
而**配方错了会表现为产品缺陷** ✗ ✓ —— 这一点值得单独记住 ✓。

### 4.5 我另外做的独立核验（不依赖服务器）

- **抽屉的真实动作只有三个读操作** ✓：`fetchVersionHistory`、`fetchVersionSourceSnapshot`、`buildVersionBytesUrl` ✓；
  **没有** `restore`/`rollback`/`publish`/删除 ✓（组件里命中的 `delete` 是本地错误状态清理 ✓）。
- **组件测试 11/11 通过** ✓（9 条抽屉 + 2 条编辑页接线 ✓）。
