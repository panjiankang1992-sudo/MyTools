# App 层挂载的组合验收 + 恢复入口 UI

日期：2026-10-07
范围：`toolbox-web/`（前端）。**未改后端**，**未改公开字帖逻辑**，只访问本地回环（`127.0.0.1:4173` / `127.0.0.1:23199`），生产只读。
本目录不覆盖 2026-10-05／10-06 的历史报告，也未改动本目录其它文件。

## 0. 一句话结果

- **任务 1（App 层挂载的组合验收）：完成**。新增 `tests/component/app-router-mount.test.ts`（4 条），用**真实 `App.vue` + 真实 `vue-router`（`createRouter` + `createMemoryHistory`）**覆盖了路由切换、KeepAlive 排除（含**反向验证**）、退出/换账号、公共路由仍被缓存四件事。**`useRoute`／`useRouter` 一处都没有 mock。**
- **任务 2（恢复入口 UI）：完成到"最小可用"**。新增管理子页 `/#/admin/curriculum/restore` + 服务层 + 纯逻辑预检 + 7 条组件测试，并在**真实浏览器 + 真实本地后端**上跑通只读冒烟（12/12）：摘要来自**真实导出包**（3 册 3 版），库内现状比对成功，二次确认闸门生效，全程未发出任何恢复请求。
- 四道门禁全绿：`npm run typecheck` 干净、`npx vitest run` **50（基线 39 + 新 11）**、`npm test` **715/715（基线不变）**、`npm run build:production` **0 告警**。

## 1. 任务 1：真正挂载 App 的组合测试

### 1.1 为什么此前不算"测过"

审查对 F15 的要求原文是「**真正挂载 App/Editor 的组合测试**」，提示词点名「**用真实 router 或 `createMemoryHistory` 挂载 App，勿只模拟 `useRouter` 然后声称已测路由切换**」。

本轮之前的实际状况（可复核）：

- `tests/component/` 里 7 个文件**每一个**都在文件开头写了
  `vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }), useRoute: () => ({ path: '...' }) }))`；
- **没有任何一条测试挂载过 `App.vue`**。

于是 `App.vue` 里 F02 的核心实现——`isAdminRoute` 与模板里的
`<KeepAlive v-if="!isAdminRoute" :max="5">` / `<component :is="Component" v-else>`——
**一次都没有被任何测试执行过**，只在报告里靠"读代码核对"声称成立。这正是要否掉的形态。

新文件：`toolbox-web/tests/component/app-router-mount.test.ts`

### 1.2 真身与 mock 的边界（如实说明）

| 部件 | 用真身还是替换 | 理由 |
| --- | --- | --- |
| `App.vue` | **真身** | 被验收的对象本身 |
| `vue-router`（`createRouter` + `createMemoryHistory`、`RouterView`、`KeepAlive`、`useRoute`、`useRouter`） | **真身** | 提示词明确要求；mock 掉它就不可能验证路由 |
| `pinia` + `admin-auth` store | **真身** | 退出/换账号的会话语义在 store 里 |
| `TextbookListView.vue`、`TextbookEditorView.vue` | **真身**（管理路由的组件就是它们） | "组合"的含义：真实页面在真实 App/路由下被挂载 |
| `admin-api`、`admin-textbooks-api` | **替换网络函数**（`...actual` 保留 `AdminApiError` 等真实导出） | jsdom 里没有后端；这是唯一被替换的一类东西 |
| `lib/analytics` | **真身**（未 mock） | 它在 jsdom 下能正常工作：队列为空时 `startAnalytics()` 不发任何请求；`App` 卸载时会 `stopAnalytics()` |
| `/`、`/copybook` 的路由组件 | **探针组件** | `HomeView`／`WorksheetV2View` 会拉起 echarts／pdf.js／OCR 与统计请求，而这两条路由要验证的是**KeepAlive 策略**，探针把"是否被缓存"变成可判定的 `onMounted` 计数 |

### 1.3 四条断言（对应任务里的 4 项）

1. **路由切换真的发生**（`F15-1`）：`router.push('/admin/textbooks')` 后
   - `App` 自己 `useRoute()` 得到的 `route.path`（读 `wrapper.vm.$.setupState.route.path`）变为 `/admin/textbooks`；
   - `wrapper.vm.$route.path` 与 `router.currentRoute.value.path` 一致；
   - 组件树**真的换了**：`[data-probe="home"]` 消失、`findComponent(TextbookListView)` 出现且渲染出「教材录入」；
   - 并且列表页**真的发出了自己的查询**（`fetchTextbookList` 被调用）——证明挂的是真身而不是空壳。
2. **KeepAlive 排除生效**（`F02-2`）：`/` → `/admin/textbooks/new` → `/` → `/admin/textbooks/new`，
   管理探针的 `onMounted` 计数必须是 **2**（离开再回来是重新创建）。若管理路由进了 KeepAlive，计数是 1。
3. **退出/换账号**（`F02-3`）：A 登录后进真实编辑器 → 服务端草稿（`山`）进入渲染层 → 输入一条**只在内存里**的新内容（`水`）→
   通过**真实 `NDropdown` 的 `select` 事件**触发 `App.onAdminAction('logout')`。断言：
   - `adminLogout()` 被调用、`auth.isAdmin === false`、`username` 清空；
   - 界面**被带离管理页**（`/`）；
   - **编辑器实例 `isUnmounted === true`**——不是被 KeepAlive 缓存（deactivate 不会置 `isUnmounted`），
     所以它 refs 里的未保存正文真的随实例销毁；
   - 换账号 B 回到同一地址：`fetchDraft` 调用次数 1 → **2**（重新读服务端，而不是复用缓存实例），
     新实例只有服务端那一版（`山`），**没有** `水`。
4. **公共字帖不受影响**（`F02-4`）：`/` ↔ `/copybook` 来回切换，两个探针的挂载计数**都保持 1**。
   这条同时也是**反向判别力**：把 `KeepAlive` 整个删掉，计数会变 2，本条会失败——它锁住"没有扩大改动范围"。

### 1.4 命令与通过数

```
cd toolbox-web && npx vitest run tests/component/app-router-mount.test.ts
→ tests/component/app-router-mount.test.ts (4 tests) 4 passed
```

### 1.5 反向验证（实际执行，非"应该会失败"）

做法：把 `src/App.vue` 第 19 行**临时改坏**成"恒 false"（即管理路由也进 KeepAlive），跑测试并采集真实输出，再按字节恢复。

改动：

```diff
-const isAdminRoute = computed(() => route.path.startsWith('/admin'));
+const isAdminRoute = computed(() => false);
```

命令与**真实失败输出**（原样摘录）：

```
$ npx vitest run tests/component/app-router-mount.test.ts

 ❯ tests/component/app-router-mount.test.ts (4 tests | 2 failed) 519ms
     ✓ F15-1：路由切换真的发生——App 自己看到的 route.path 变了，组件树也真的换了 185ms
     × F02-2：管理路由不进 KeepAlive——离开再回来是**重新创建**，不是复用缓存实例 60ms
     ✓ F02-4：公共字帖不受影响——非管理路由**仍然**被 KeepAlive 缓存 52ms
     × F02-3：A 退出后编辑缓冲不残留——编辑器实例被销毁，B 重新从服务端读，看不到 A 的未保存内容 220ms

 FAIL  ... > F02-2：管理路由不进 KeepAlive——离开再回来是**重新创建**，不是复用缓存实例
AssertionError: expected 1 to be 2 // Object.is equality

- Expected
+ Received

- 2
+ 1

 ❯ tests/component/app-router-mount.test.ts:243:42
    241|     await flushPromises()
    242|     expect(wrapper.find('[data-probe="admin-new"]').exists()).toBe(tru…
    243|     expect(mountCounts.get('admin-new')).toBe(2)

 FAIL  ... > F02-3：A 退出后编辑缓冲不残留——编辑器实例被销毁，B 重新从服务端读，看不到 A 的未保存内容
AssertionError: expected false to be true // Object.is equality

- Expected
+ Received

- true
+ false

 ❯ tests/component/app-router-mount.test.ts:338:40
    338|     expect(editorInstance.isUnmounted).toBe(true)

 Test Files  1 failed (1)
      Tests  2 failed | 2 passed (4)
```

恢复后校验：

```
$ cp /tmp/App.vue.bak src/App.vue && cmp /tmp/App.vue.bak src/App.vue
App.vue restored byte-identical
```

**F02-2 与 F02-3 在"管理路由也进 KeepAlive"时确实失败**：一条抓到"组件被复用而没有重新挂载"，另一条抓到"编辑器实例没有被销毁"。`F15-1`（路由切换）与 `F02-4`（公共路由仍被缓存）保持通过——说明这两条锁的是另外的性质，没有被同一个改动一起带走。

### 1.6 一处真实的测试编写坑（留档，避免后人重踩）

`F02-3` 里第一次尝试触发退出时写的是 `wrapper.findComponent(NDropdown)`，结果**点错了组件**：
页面上的 `NSelect`（编辑器里就有）内部也渲染一个 `NDropdown`，裸查找命中的是它，
于是 `'logout'` 被当成相对路径推了进去，路由告警
`[Vue Router warn]: No match found for location with path "logout"`，而退出菜单从未被点到。
现在按"选项里含 `key: 'logout'`"定位真身，并断言一定找得到。

## 2. 任务 2：恢复入口 UI

### 2.1 位置与形态

- **服务层**：`toolbox-web/src/services/admin-curriculum-api.ts`（新）
  与公开只读的 `curriculum-api.ts` 分开：本文件走 `/admin/v1`，带独立会话与 CSRF。
  导出 `fetchExportManifest()` 与 `restorePackage(pkg)`，类型与后端
  `ExportPackage`／`ExportPackageEntry`／`RestoreReport`／`EditionReconciliation` 一一对应。
- **纯逻辑预检**：`toolbox-web/src/lib/admin-restore-preview.ts`（新）
  `parseExportPackage()` 做结构与格式校验；`buildRestorePreview()` 输出册数／版本数／当前版本数／冲突清单。
- **页面**：`toolbox-web/src/views/admin/CurriculumRestoreView.vue`（新）→ 路由 `/#/admin/curriculum/restore`
  （`src/main.ts` 注册，`meta.requiresAdmin`；以 `/admin` 开头，因此自动被 `App.vue` 排除出 KeepAlive，**未改 `App.vue`**）。
- **入口**：`src/views/admin/TextbookListView.vue` 头部工具区新增「恢复导入包」按钮（与「新增教材」并列，未改动其它行为）。

### 2.2 三步流程（先看清，再确认，再执行）

1. **解析并预览**：只读，不写库。显示
   - 册数（去重 `editionId`）、版本数、`current` 版本数、**冲突数**；
   - 逐版本判定表：`将新增` / `将跳过` / `冲突` + 一句说明；
   - 冲突项单独用 error 提示列出，文案明确写「**不会被覆盖**」。
2. **二次确认**：勾选「我确认这是破坏性操作……」后「确认恢复」才可用；
   并且闸门写在**会发请求的函数里**（`if (!pkg || !preview || !confirmed) return`），
   不只依赖按钮的 `disabled`——否则将来某处改动让按钮可点，就会在没有确认的情况下发出破坏性请求。
3. **对账结果**：`restoredEditions / restoredVersions / skippedVersions`，
   `conflicts[]` **逐条**显示并注明「没有被写入」，`reconciliations[]` 逐册列出
   `指针 / 正文哈希 / 来源快照` 的 **一致 / 不一致**。

### 2.3 冲突判定来自哪里（说清口径，不含糊）

- **包内自查**（只依赖包本身）：缺正文或哈希、Base64 非法、
  **字节长度与声明不符**（用 `base64ByteLength()` 算术推算，不解码大包）、**包内同册同版本重复**。
- **与库内现状比对**：先 `GET /admin/v1/curriculum/export-manifest`（**只读**）拿到
  每册 `history[].contentSha256`，再逐版本判定——同版本**同哈希 → 将跳过**，**异哈希 → 冲突（不会被覆盖）**。
- 若清单读不到（网络/权限失败），**不谎称"无冲突"**：摘要照给，
  并显示「未能读取库内现状……下面的冲突判定只覆盖包内自查；库内冲突会在恢复结果里逐条列出」。

**明确没做**：前端**不计算 SHA-256**（浏览器端 `crypto.subtle` 在 jsdom 下不保证存在，逐版哈希大包也很贵）。
哈希与快照指纹的校验交给服务端（它本来就在写入前做，失败即报 `contentHashOrLengthMismatch`）。
前端只做**长度**这一项低成本预检，足以拦住"粘错包 / 被截断的包"。

### 2.4 组件测试（7 条，挂真实组件，mock 服务层）

`toolbox-web/tests/component/admin-restore-ui.test.ts`

| 测试 | 断言要点 |
| --- | --- |
| 摘要渲染 | 册数 `2`、版本数 `3`、冲突 `1`；冲突项列出 `book-a@1.0.1` 且含「不会被覆盖」；同哈希那版判为「将跳过」；**摘要阶段 `restorePackage` 一次都没被调用** |
| 确认后才调用 | 未勾选时点击「确认恢复」→ **不调用**，并提示「需先勾选上面的确认项」；勾选后点击 → 调用 **1 次**，且发的是**解析后的包对象**；结果数字真的渲染（2 / 1） |
| 冲突被显示 | 服务端返回 `['book-a@1.0.1:CONFLICT_HASH_MISMATCH']` → 冲突区出现该条并含「没有被写入」；**不得**同时出现「服务端没有报告冲突」 |
| 逐册对账 | 两册：一致那册不出现「不一致」；不一致那册出现 **2 处**「不一致」（指针 + 来源快照） |
| 库内现状读不到 | 摘要仍可用（3 版 0 冲突），但必须出现 `preview-manifest-note` 且写明「只覆盖包内自查」 |
| 不是导出包 | 报错文案含 `toolbox-curriculum-package`；不渲染摘要；`restorePackage` 与 `fetchExportManifest` **都没有被调用** |
| 长度不符 | 在空库前提下把某版 `byteLength` 改成 999 → 冲突数 `1` 且含「正文长度与声明不符」 |

```
cd toolbox-web && npx vitest run tests/component/admin-restore-ui.test.ts
→ tests/component/admin-restore-ui.test.ts (7 tests) 7 passed
```

### 2.5 真实浏览器只读冒烟（本地回环）

脚本：`toolbox-web/tests/e2e/local-restore-smoke.mjs`（新；与既有 `seed-admin-textbooks.mjs` 等并列）。
**安全边界写死在脚本里**：目标必须是 `E2E_TARGET` 显式指定的**回环**地址（复用 `target-guard.mjs`），
且**绝不调用** `POST /admin/v1/curriculum/restore-package`——只走
登录 → 列表页入口 → 路由 → `GET /curriculum/export-package`（只读）→ 前端预检，
最后断言"未勾选确认前不可提交、页面上没有恢复结果"。

```
$ cd toolbox-web
$ E2E_TARGET=http://127.0.0.1:4173 node tests/e2e/local-restore-smoke.mjs

PASS  本地假管理员可通过界面登录 — 管理员 · local-admin
PASS  教材列表页工具区出现「恢复导入包」入口
PASS  点击入口后进入 /#/admin/curriculum/restore（路由已注册） — 点击路径=synthetic-click hash=#/admin/curriculum/restore
PASS  GET /admin/v1/curriculum/export-package 可用（会话与只读路径通） — versions=3
PASS  粘贴真实导出包后渲染出恢复摘要 — 点击路径=synthetic-click
  摘要：册数=3 版本数=3 冲突=0 库内清单说明=「」
PASS  摘要版本数与包内版本数一致 — 界面=3 包内=3
PASS  库内现状读取成功（摘要与库内比对过，没有降级提示）
PASS  未勾选确认前「确认恢复」不可用（二次确认闸门） — disabled=true
PASS  没有发出任何恢复请求（页面上没有恢复结果）
PASS  无未预期的 4xx/5xx 响应
PASS  无页面级未捕获异常
PASS  无控制台 error

===== 恢复入口只读冒烟：12/12 通过 =====
```

这说明：真实后端返回的真实导出包（3 版）被页面解析、摘要数字**与包内版本数一致**，
并且 `GET /curriculum/export-manifest` 真的读到了库内现状（没有降级提示）——即"先展示摘要/冲突"那一步在真实环境可用。

#### 2.5.1 冒烟里一处**未解决**的驱动层现象（不掩饰）

脚本本应全部用 CDP `Input.dispatchMouseEvent` 走真实鼠标。实测它**不稳定**：

- 有整轮里"列表页入口"的真实鼠标点击生效了；
- 也有整轮里**渲染进程完全没收到 click**——在 `document` 捕获阶段挂的 click 计数器为 **0**，
  而 `document.elementFromPoint(按钮中心)` 命中的正是该按钮内部元素（**不是被遮挡**）。
  同一轮里**登录按钮**的真实鼠标点击是生效的（登录成功）。
- 因此脚本改成"先真实鼠标、效果不出现再退回页内合成 `click`，并**打印实际生效的是哪条路径**"。
  上面这次 12/12 里点击路径都是 `synthetic-click`；更早一次运行里入口那一步是 `real-mouse`。

结论边界：**这是我这支临时脚本的驱动层现象，不是产品结论**。
`elementFromPoint` 命中且无遮挡，说明真实用户鼠标能点到；应用逻辑（预检、只读请求、闸门）已由合成 click 路径验证。
但**我未能定位该现象的成因**，也没有用它去证明或否定任何产品行为。

## 3. 变更文件

新增：

- `toolbox-web/tests/component/app-router-mount.test.ts`
- `toolbox-web/tests/component/admin-restore-ui.test.ts`
- `toolbox-web/src/services/admin-curriculum-api.ts`
- `toolbox-web/src/lib/admin-restore-preview.ts`
- `toolbox-web/src/views/admin/CurriculumRestoreView.vue`
- `toolbox-web/tests/e2e/local-restore-smoke.mjs`

修改（最小改动）：

- `toolbox-web/src/main.ts` —— 新增一条管理路由 `/admin/curriculum/restore`。
- `toolbox-web/src/views/admin/TextbookListView.vue` —— 头部工具区新增「恢复导入包」按钮 + 一处 `gap` 样式。

**未改**：`src/App.vue`（反向验证时临时改坏并已按字节恢复）、后端、公开字帖逻辑、`tests/component/` 既有 7 个文件、`package.json`（未加任何依赖）。

## 4. 门禁结果（全部在本轮改动之后重跑）

| 命令 | 结果 | 基线 |
| --- | --- | --- |
| `npm run typecheck` | 干净（无输出） | 干净 |
| `npx vitest run` | **50 passed (9 files)** | 39 |
| `npm test` | **715 passed / 0 failed** | 715 |
| `npm run build:production` | `✓ built`，**0 条告警** | 0 告警 |

## 5. 未达成项与真实原因

1. **A09 仍缺"真实浏览器端到端"的退出/换账号点击路径**。jsdom 组合测试已覆盖到"编辑器实例被销毁 + 新账号重新读服务端"，
   但真实浏览器里"点右上角菜单 → 退出"这一步没有脚本化验证。原因：本地环境只有一个假身份账号
   （`local-admin`/`local-admin`），**没有第二个账号**可用来真正演示"换账号"；只做"退出"又覆盖不到 A09 的另一半。
   本轮把这条留在组合测试层，并把真实浏览器的那一半明确标为未做。
2. **没有在浏览器里真跑一次破坏性恢复**。恢复入口的写入路径（`POST restore-package`）只在组件测试里被断言"确认后才调用"，
   **没有对任何真实数据库执行过**。这是刻意的：本轮的范围是前端接线，且恢复是破坏性操作，
   不该由一次前端冒烟顺手在共享的本地库上执行。后端侧的"空库恢复 + 逐项对账"已有独立证据
   （见本目录 `source-snapshot-chain.md` §3／§4）。
3. **恢复入口没有覆盖 `repair-source-snapshot`（补录）**。任务只要求"恢复入口"，补录是另一个写接口（需 `basis` 且不可重复补），
   本轮没有为它做界面。它仍只能通过接口调用。
4. **摘要里的"冲突"不含 SHA-256 级校验**（只做长度与清单哈希比对），原因见 §2.3；真正的哈希/快照指纹校验在服务端，
   冲突会在恢复结果里逐条返回并展示。
5. **管理页顶栏标题对新路由显示的是"首页"**。`App.vue` 的 `current` 只认"分类路由"与"已登记工具路由"，
   `/admin/*` 都落到 `pages[0].label`——这是**既有行为**（`/admin/textbooks` 也一样），本轮没有顺手改 `App.vue`
   以免把改动扩大；恢复页自身渲染了 `<h2>恢复导入包</h2>`，页面内标题是准确的。
6. **D04（响应式与可访问性）本轮没有推进**：没有做五尺寸截图，也没有跑键盘路径。
   新页面沿用了既有 aria（`aria-label`、表格语义）与 `@media (max-width: 720px)` 的表单化布局，但**没有验证**。

## 6. 对验收矩阵的建议状态

| 行 | 原状态 | 建议 | 依据 |
| --- | --- | --- | --- |
| **A09**（退出／换账号） | 部分（测试） | **部分（增强）**：升级点是"组合层"——真实 `App` + 真实 router + 真实编辑器下，退出后编辑器实例 `isUnmounted=true`、界面被带离管理页、新账号重新读服务端且看不到上一人的未保存内容，并有反向验证（§1.3 第 3 条、§1.5）。**仍不升为通过**：真实浏览器端到端的退出/换账号路径未做，且本地只有一个账号，换账号无法真实演示（§5.1） |
| **D02**（导出与回退） | 部分 | **部分（增强）**：补上了此前明确缺失的"恢复入口 UI"——预览摘要 + 二次确认 + 冲突逐条显示 + 逐册对账（§2.1–§2.3），7 条组件测试 + 真实浏览器只读冒烟 12/12（§2.4、§2.5）。**仍不升为通过**：没有在浏览器里真跑一次破坏性恢复（§5.2），补录接口没有界面（§5.3） |
| **D04**（响应式与可访问性） | 部分 | **不变（部分）**：本轮未做五尺寸截图与键盘路径；新页面未引入回归，但也没有新增证据（§5.6） |

## 7. 复现命令

```bash
cd toolbox-web

# 任务 1 组合测试
npx vitest run tests/component/app-router-mount.test.ts

# 任务 1 反向验证（改坏 → 必须失败 → 恢复）
cp src/App.vue /tmp/App.vue.bak
sed -i '' "s|const isAdminRoute = computed(() => route.path.startsWith('/admin'));|const isAdminRoute = computed(() => false);|" src/App.vue
npx vitest run tests/component/app-router-mount.test.ts   # 期望 2 failed（F02-2 / F02-3）
cp /tmp/App.vue.bak src/App.vue && cmp /tmp/App.vue.bak src/App.vue

# 任务 2 组件测试
npx vitest run tests/component/admin-restore-ui.test.ts

# 任务 2 真实浏览器只读冒烟（仅回环；不会调用恢复写接口）
E2E_TARGET=http://127.0.0.1:4173 node tests/e2e/local-restore-smoke.mjs

# 四道门禁
npm run typecheck
npx vitest run
npm test
npm run build:production
```
