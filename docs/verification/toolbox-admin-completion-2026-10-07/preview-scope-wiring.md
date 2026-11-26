# 预览范围：前端真的接上后端过滤（2026-10-07，第 162 轮）

## 结论（一句话）

预览调用现在**真的**带 `nodeKind`／`nodeKey`／`draftRevision`：选「第一单元」发出的请求是
`/admin/v1/textbooks/{id}/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=4`，
真实浏览器里产物从 **整册 198 条 / 6 页** 变成 **第一单元 66 条 / 2 页**；
范围取不到时**显示明确错误、绝不回退成整册**。

这是「明确缺实现」清单的最后一项，已闭环。

---

## 1. 改动文件

| 文件 | 改动 |
| --- | --- |
| `toolbox-web/src/lib/admin-preview-scope.ts` | 重写：新增 `previewScopeQuery()`（节点 → 请求参数）、`previewScopeFailureMessage()`（404 → 明确提示）；`describePreviewScope()` 的 `notice` 从"仍按全册生成"改成"本次预览**只包含**该范围"，课文标签带上父单元 |
| `toolbox-web/src/composables/useAdminDraftPreview.ts` | `generate()` 新增 `scopeNode`；拼真实查询串 `nodeKind`／`nodeKey`／`draftRevision`；错误经 `previewScopeFailureMessage` / `adminErrorMessage` 翻成人话 |
| `toolbox-web/src/views/admin/TextbookEditorView.vue` | `openPreview()` 传 `scopeNode: selectedNode`；新增"切范围即重取预览"的 watcher；改掉那句过时注释；课文范围带出父单元；整册文案补"在左侧目录中选择…"；预览块显示**真实页数**「共 N 页」 |
| `toolbox-web/tests/admin-preview-scope.test.ts` | 重写（3 → 7 条纯函数测试） |
| `toolbox-web/tests/component/admin-editor-preview-publish.test.ts` | 替换 1 条过时断言，新增 5 条组件测试（URL／切范围重取／404 不回退／flush 后修订号） |
| `toolbox-web/tests/e2e/local-admin-preview-scope.mjs` | **新增**：真浏览器端到端验收（本目录外的新文件，未改动任何既有文件） |
| `docs/verification/.../preview-scope-wiring.md`、`screenshots/preview-scope-*.png` | 本报告与截图 |

**未改**：后端、公开字帖逻辑、既有测试期望以外的任何文件。`10-05`／`10-06`／本目录其它文件一行未动。

## 2. 一个需要说清的取舍：范围选择复用「左侧目录选中项」

需求写的是"让管理员能选范围：整册／某单元／某课文"。**没有新增第二个下拉框**，而是让
**目录树里已有的选中项**同时决定编辑范围与预览范围——理由：

- 设计 164 行原文就是「使用当前草稿的**已选范围**」，界面上原本已经有「当前范围：…」这行字，
  它一直是**目录选中项**的标注；再加一个"预览范围"下拉会造出两个互相竞争的"范围"概念。
- 选项天然来自当前草稿的 `units`／`lessons`（目录树就由 `outline` 渲染），
  编辑器里未保存的新增单元**立刻可选**（预览前会先 flush，所以"先 flush 再用同一份数据算范围"自洽）。
- 目录里本来就有「全部内容」按钮＝整册，所以"整册"这个选项也已存在。

若评审认为必须有一个**独立的**预览范围下拉，可以加；但那会出现"编辑在单元 A、预览在单元 B"的
组合，需要另行定义"新建行归属哪个单元"，属于需求变更，本轮不做。

## 3. 真实请求 URL 的断言证据

### 3.1 组件测试（挂真实 `TextbookEditorView` + mock 服务层，断言**整条 URL**）

```
✓ 整册范围：预览请求 URL 不带 nodeKind／nodeKey，文案如实写"当前范围：整册"
✓ 选「第一单元」：请求 URL 含 nodeKind=unit 与真实 nodeKey，文案与范围一致
✓ 选课文：请求 URL 含 nodeKind=lesson，标签把父单元带出来
✓ 切范围会重新取预览：整册 → 第一单元 → 整册，每次都换 URL
✓ 404 previewScopeNotFound：显示明确错误，且绝不静默回退成整册
✓ draftRevision 用 flush 之后的修订号（保存前 3 → 保存后 7）
```

断言的是请求路径逐字相等（`previewPath(index)` 取 `adminRequest` 的第 index 次入参）：

| 场景 | 断言的 URL |
| --- | --- |
| 整册 | `/textbooks/demo-book/draft/preview?draftRevision=3` |
| 第一单元 | `/textbooks/demo-book/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=3` |
| 课文 cl1 | `/textbooks/demo-book/draft/preview?nodeKind=lesson&nodeKey=cl1&draftRevision=3` |
| 整册 → 单元 → 整册（3 次请求） | 第 1、3 次无 `nodeKind`／`nodeKey`；第 2 次带 `nodeKind=unit&nodeKey=cu1` |
| flush 后 | `/textbooks/demo-book/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=7`（不是 3） |

"整册不带参数"这条尤其重要：**整册必须是"两个都不传"**，而不是"传了空值"或"传了整个册的 key"。

### 3.2 真浏览器验收（`tests/e2e/local-admin-preview-scope.mjs`，14/14 通过，退出码 0）

环境：前端 `http://127.0.0.1:4173`（已重建的 dist）、后端父会话常驻实例 `127.0.0.1:23199`、
草稿 `accept-cn-2026a`（3 单元 / 6 课文 / 198 条）。手法：合成 `el.click()` +
应用脚本前给 `window.fetch` 装探针记录**真实 URL 与响应体**。

```
PASS 整册：请求 URL 不带 nodeKind／nodeKey —— /admin/v1/textbooks/accept-cn-2026a/draft/preview?draftRevision=4
PASS 整册：响应 scope.filtered=false 且条目数为整册规模 —— entries=198 scope={"nodeKind":null,"nodeKey":null,"filtered":false}
PASS 整册：界面上"当前范围：整册"且产物页数可见 —— note="本次预览基于修订 4 · 范围 整册 · 共 6 页"
PASS 切范围真的重取了预览（请求次数 1 → 2） —— calls=2
PASS 第一单元：请求 URL 含 nodeKind=unit 与 nodeKey=cu1 —— /admin/v1/textbooks/accept-cn-2026a/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=4
PASS 第一单元：响应 scope.filtered=true —— {"nodeKind":"unit","nodeKey":"cu1","filtered":true}
PASS 第一单元：产物条目数真的变少 —— entries 198 → 66
PASS 第一单元：产物页数真的变少，且文案与范围一致 —— pages 6 → 2；note="…· 范围 单元：第一单元 · 共 2 页"
PASS 第二单元：请求 URL 含 nodeKind=unit 与 nodeKey=cu2 —— …?nodeKind=unit&nodeKey=cu2&draftRevision=4
PASS 第二单元：产物首页画布与整册不同 —— 整册指纹 1566580290 → 第二单元指纹 643415574
PASS 第二单元：条目数与页数都比整册小 —— entries 198 → 66；pages 6 → 2
```

截图（`screenshots/`）：

- `preview-scope-whole-book.png` —— 「当前范围：整册」＋「范围 整册 · 共 6 页」
- `preview-scope-unit-1.png` —— 「当前范围：单元：第一单元」＋「本次预览只包含该单元及其课文与条目。」＋「共 2 页」
- `preview-scope-unit-2.png` —— 「当前范围：单元：第二单元」＋「共 2 页」，**首页内容与整册明显不同**（整册首页是"天地/人和/山水…"，第二单元首页是"山水/春夏/秋冬…"）

**如实说明一处**：第一单元恰好是整册的前 66 条，所以整册与第一单元的**第 1 页看起来一样**，
差别只在页数（6 → 2）。为此专门多切了一次**第二单元**并断言画布指纹不同，避免"看起来没变"的误判。

### 3.3 后端契约（curl 直打 23199，只读）

```
整册            : HTTP 200 entries=198 units=3 lessons=6 scope={"nodeKind":null,"nodeKey":null,"filtered":false}
第一单元 cu1    : HTTP 200 entries=66  units=1 lessons=2 scope={"nodeKind":"unit","nodeKey":"cu1","filtered":true}
课文 cl1        : HTTP 200 entries=0   units=1 lessons=1 scope={"nodeKind":"lesson","nodeKey":"cl1","filtered":true}
不存在范围      : HTTP 404 TOOLBOX_ADMIN_006 details={"reason":"previewScopeNotFound","nodeKind":"unit","nodeKey":"nope"}
修订号不符      : HTTP 409 TOOLBOX_ADMIN_004 details={"reason":"revisionMismatch","expected":3,"actual":4}
```

## 4. 反向验证（实际失败输出，原文摘录）

### 4.1 删掉传参 → URL 断言必须失败

改动：把 `useAdminDraftPreview.generate()` 里 `if (scoped.nodeKind)` 改成 `if (false && …)`，
即**不再传** `nodeKind`／`nodeKey`（`draftRevision` 仍传）。

```
⎯⎯⎯⎯⎯⎯ Failed Tests 5 ⎯⎯⎯⎯
 FAIL  … > 选「第一单元」：请求 URL 含 nodeKind=unit 与真实 nodeKey，文案与范围一致
AssertionError: expected '/textbooks/demo-book/draft/preview?dr…' to be '/textbooks/demo-book/draft/preview?no…'
Expected: "/textbooks/demo-book/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=3"
Received: "/textbooks/demo-book/draft/preview?draftRevision=3"

 FAIL  … > 选课文：请求 URL 含 nodeKind=lesson，标签把父单元带出来
 FAIL  … > 切范围会重新取预览：整册 → 第一单元 → 整册，每次都换 URL
 FAIL  … > 404 previewScopeNotFound：显示明确错误，且绝不静默回退成整册
      AssertionError: expected '/textbooks/demo-book/draft/preview?dr…' to contain 'nodeKind=unit'
 FAIL  … > draftRevision 用 flush 之后的修订号（保存前 3 → 保存后 7）
Expected: "/textbooks/demo-book/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=7"
      Tests  5 failed | 5 passed (10)
```

**结论**：URL 断言有鉴别力——参数不传，5 条立刻失败；恢复后 10/10 通过。

（第一轮这个反向验证是先把 5 条测试写出来、跑绿，再做上述改动复跑的。日志见
`/tmp/reverse-a-scope-params-removed.log`，仅临时目录。）

### 4.2 把 404 改成静默回退成整册 → 对应测试必须失败

改动：`generate()` 里捕获 `previewScopeNotFound` 后**改发一次不带范围的整册请求**，
成功就用整册内容继续（真正的"假装过滤"）。
同时把该测试的 mock 改成"第一次 404、第二次（整册）能成功"，
这样回退实现会真的拿到内容，而不是靠同一个错误蒙混。

```
⎯⎯⎯⎯⎯⎯ Failed Tests 1 ⎯⎯⎯
 FAIL  … > 404 previewScopeNotFound：显示明确错误，且绝不静默回退成整册
AssertionError: expected '修改教材填写教材信息，然后录入对应内容。…草稿还没有内容，先录入再预览。…'
                 to contain '预览范围不存在'
- Expected
+ Received
- 预览范围不存在
+ … 当前范围：单元：第一单元 … 草稿还没有内容，先录入再预览。 …
      Tests  1 failed | 9 passed (10)
```

**结论**：静默回退会**吞掉**范围错误、把界面变成"单元标签 + 整册内容/空内容"，测试立刻失败；
只有"如实报错、不回退"的实现才通过。日志 `/tmp/reverse-b-silent-fallback.log`。

（另：第一次做这条反向验证时测试还是旧写法（第二次请求也被 mock 成 404），失败信息是
`expected "vi.fn()" to be called 1 times, but got 2 times`——同样能拦住；后来把测试加强成
"第二次能成功"，失败点前移到**错误文案被吞**，鉴别力更强。）

## 5. 门禁四项（最终冻结状态）

| 门禁 | 基线 | 本轮结果 | 说明 |
| --- | --- | --- | --- |
| `npm test`（node:test） | 715 通过 | **719 通过 / 0 失败** | +4：`admin-preview-scope.test.ts` 3 → 7 |
| `npx vitest run`（组件） | 71 通过 | **76 通过 / 0 失败（12 文件）** | +5：预览范围 URL／切范围／404／修订号 |
| `npm run typecheck` | 干净 | **退出码 0，无输出** | `build:production` 内含 `vue-tsc --noEmit` |
| `npm run build:production` | 0 告警 | **✓ built，`warn` 命中 0** | 唯一含 "error" 的是 chunk 文件名 `admin-error-messages-*.js` |
| 真浏览器验收（新增） | —— | **14/14 通过，退出码 0** | `tests/e2e/local-admin-preview-scope.mjs` |

复现：

```bash
cd toolbox-web
npm test
npx vitest run
npm run typecheck
npm run build:production
# 浏览器（需先重建 dist，再用 curl 登录拿 Cookie）
ADMIN_COOKIE='toolbox_admin_local=…' E2E_TARGET=http://127.0.0.1:4173 \
  node tests/e2e/local-admin-preview-scope.mjs
```

## 6. 未达成 / 真实错误（如实）

| 项 | 状态 |
| --- | --- |
| 课文范围在**真浏览器**里的端到端 | **未做**。种子草稿 `accept-cn-2026a` 的 198 条 `clientLessonId` 全是 `null`，按课文预览会返回 0 条 → 界面如实报"草稿还没有内容"，无法观察"页数变少"。课文范围由**组件测试**覆盖（URL 断言 + 父单元标签 + 后端 curl 证明 cl1 返回 1 单元 1 课文）。要补的话需先播种带课文归属的草稿。 |
| 404 在**真浏览器**里的显示 | **未做**（UI 上无法构造一个"草稿里不存在"的范围键，除非先删单元再预览；组件测试已覆盖且做了反向验证）。 |
| 独立"预览范围"下拉 | **未做**，见 §2 的取舍说明；若要，属需求变更。 |
| 新建但**未保存**的单元能否作为范围 | 逻辑上可以（先 flush 再预览），但**未实测**：目录是内存态、flush 之后才落库，本轮没有专门造这个场景。 |
| 打印预览窗口里的页数 | 未接（沿用既有打印预览链路，本轮未改动打印代码）。页数改为在预览块内显示产物 `pageCount`。 |

**没有伪造**：以上"未做"项都没有用别的证据冒充。

## 7. 对状态矩阵 D05／D06 的建议

矩阵 `status-matrix.json` 目前**只有 D05「预览与打印」，没有 D06 行**（`ids` 到 D05 结束）。
建议：

1. **D05 更新**（保持"部分"）：把 note 里"前端一处都没传 `nodeKind`/`nodeKey`/`draftRevision`"、
   "「当前范围：整册」只是标注"这两段**更正为已闭环**，并追加本轮证据：
   - 前端真的发 `nodeKind`/`nodeKey`/`draftRevision`（组件测试逐字断言 URL；反向验证：不传参数 → 5 条失败）；
   - 404 `previewScopeNotFound` 显示明确错误、不回退（反向验证：改成静默回退 → 测试失败并吞掉错误文案）；
   - 真浏览器 14/14：198 条/6 页 → 66 条/2 页，且第二单元首页画布与整册不同；
   - 仍然"部分"的原因：**课文范围与 404 的浏览器端到端未做**（见 §6），打印链路仍是既有 14 条测试而非管理端端到端。
2. **若要新增 D06**，建议命名「预览范围前端接线」，状态填 **通过**，证据：测试+实测，
   可重跑：`npx vitest run tests/component/admin-editor-preview-publish.test.ts` 与
   `node tests/e2e/local-admin-preview-scope.mjs`；note 用本报告 §3 的 URL 与页数。
   但要提醒：**D06 只覆盖"整册/单元"两档的浏览器端到端**，课文档只有组件测试——
   若行状态想要严谨，应写「部分（课文档未做浏览器端到端）」。

## 8. 关联文件

- 上一轮的事实记录：`preview-scope-frontend-gap.md`（描述"前端没接线"，本轮已闭环，**未改动该文件**）
- 渲染层样张：`pdf-scope-sample.md`（320 条→4 页 / 64 条→1 页）
- 浏览器验收脚本：`toolbox-web/tests/e2e/local-admin-preview-scope.mjs`
- 截图：`screenshots/preview-scope-whole-book.png`、`preview-scope-unit-1.png`、`preview-scope-unit-2.png`
