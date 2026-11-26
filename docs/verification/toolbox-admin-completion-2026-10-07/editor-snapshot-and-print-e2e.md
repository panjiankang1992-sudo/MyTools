# 编辑器"当前编辑快照"→ PDF 预览，以及管理端「打印预览」浏览器端到端验收（2026-10-07）

本文件只覆盖**当前"明确缺实现"清单里仅剩的两项**：

| 编号 | 项目 | 结果 |
| --- | --- | --- |
| 任务 1 | 编辑器**当前编辑快照**（未保存的编辑）体现在草稿 PDF 预览里 | ✅ **通过**：正式验收 15/15；反向验证按预期失败 |
| 任务 2 | 管理端「打印预览」**真的开出打印窗口** + **关掉即取消** + **不调系统打印** | ✅ **通过**：正式验收 10/10；反向验证按预期失败 |

两项共用的根因绕开手段已在本轮**实测成立**（见第 1 节原始输出）：
真实鼠标 `Input.dispatchMouseEvent` 送达不稳定，本文件里的所有交互都改用
**合成 `el.click()`** + **原生 setter + `input` 事件**，并且一律**轮询等待条件**。

- 目标：`E2E_TARGET=http://127.0.0.1:4173`（本地回环，`tests/e2e/target-guard.mjs` 强制回环，非回环直接退出码 2）
- 样册：`local-acceptance-2026-10-07`（本地隔离库内真实存在）
- 后端：`127.0.0.1:23199`（父会话常驻实例，**全程只读使用，未重启、未停止**）
- 每个脚本都在 3 秒级跑完；**最慢的一次整体 18.1 秒**（反向验证里"等关闭超时"的那 15 秒），
  全部用 `node --test --test-timeout=... --test-force-exit` 包住，**不存在挂住的脚本**。

---

## 1. 手法的小规模验证（正式验收之前先证明合成输入真的被接收）

脚本：`toolbox-web/tests/e2e/local-admin-synthetic-input-smoke.mjs`
（只做一件事：把编辑页第 1 行文本框改成独特值，然后断言这个值进了**真实保存链路**）

```
$ E2E_TARGET=http://127.0.0.1:4173 ADMIN_COOKIE=toolbox_admin_local=... \
    node --test --test-timeout=180000 --test-force-exit tests/e2e/local-admin-synthetic-input-smoke.mjs

# 原始值="山" 目标值="山烟wiwe" setter 返回="山烟wiwe" 渲染=山烟wiwe
# PASS 原生 setter + input 事件让输入框（Vue 渲染）变成新值 —— 实际 "山烟wiwe"
# 捕获到 1 次草稿保存请求
# 收尾复原：输入框="山"（原值 "山"） 服务器草稿已无独特值=true
# PASS 后端确实收到了带独特值的草稿保存载荷（合成输入进了真实保存链路） —— 载荷长度 996，含「烟wiwe」
# 载荷中命中条目：{"rowId":"row-1","text":"山烟wiwe","pinyin":"shān"}
ok 1 - tests/e2e/local-admin-synthetic-input-smoke.mjs
```

**这条原始输出证明了三件事**：

1. `Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(el, v)`
   + `dispatchEvent(new Event('input',{bubbles:true}))` **确实被 Vue 接收**（页面渲染值变成新值）；
2. 合成输入进的是**真实保存链路**：脚本抓到了发往
   `PUT /admin/v1/textbooks/<id>/draft` 的请求体，里面 `entries[0].text === "山烟wiwe"`；
3. 脚本收尾把值改回原值并轮询确认服务器草稿已无独特值 —— **跑一次不污染样册**。

> 关键点（第一次踩到的坑）：Naive UI 的 `NInput` 把 `aria-label` 放在**外层 `div.n-input`** 上，
> 真正的 `<textarea>` 在里面。直接查 `textarea[aria-label=...]` 会**一条都找不到**：
> `[...document.querySelectorAll('textarea')].map(t => t.getAttribute('aria-label'))` → `[null, null, null, null, null]`。

---

## 2. 任务 1：当前编辑快照 → PDF 预览

脚本：`toolbox-web/tests/e2e/local-admin-editor-snapshot.mjs`

### 2.1 做法

1. 打开编辑页，先做**一次"编辑之前"的预览**（这一步的 `/draft/preview` 响应体会被记录，反向验证要用它冒充"服务器已保存的草稿"）；
2. 用**原生 setter + input 事件**造一次**合法**（汉字数 = 拼音音节数）且独特的未保存编辑：
   第 1 行 `山 / shān` → `快照 / kuài zhuǎn<随机 4 位>`；
3. **不点保存**，直接合成 `el.click()` 点「预览草稿」；
4. 用 `Page.addScriptToEvaluateOnNewDocument` 在**应用脚本之前**挂钩 `URL.createObjectURL`，
   取回预览**真实产出的 PDF 字节**，落盘后用站点自带的 `pdfjs-dist`（`tests/e2e/pdf-text.mjs`，**不新增依赖**）
   读出**文字层**，断言独特值在不在里面；
5. 把编辑改回原值再预览一次，断言独特值**不再**出现。

### 2.2 判定证据（正式验收 15/15，退出码 0）

完整原始日志：`evidence/browser/editor-snapshot-normal.log`；结构化结果：`evidence/browser/editor-snapshot-raw.json`

```
[0.9s] 服务器草稿（编辑前）：revision=170 entries=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]
[0.9s] 第 1 行原值：text="山" pinyin="shān"
[1.5s] PASS 基线：编辑之前的预览能产出 PDF 并读到文字层 —— PDF=61067 字节 sha256=a08b4b4f07c0d9c7 页数=1
[1.5s] PASS 手法：原生 setter + input 事件让 Vue 把两个文本框渲染成新值 —— 汉字 setter={"ok":true,"value":"快照"} 拼音 setter={"ok":true,"value":"kuài zhuǎn8pbp"}
[1.5s] 编辑后（预览前）服务器草稿：revision=170 entries=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]
[1.9s] PASS 手法：合成 el.click() 触发「预览草稿」并产出新的 PDF blob —— 点击={"ok":true} PDF blob 数=2
[1.9s] 预览快照标注=「本次预览基于修订 171 · 范围 整册 · 共 1 页」 canvas=1
[1.9s] PASS 证据：预览面板把 PDF 画了出来（canvas 数 ≥ 1） —— canvas=1
[2.1s] PASS 证据：取回本次预览的真实 PDF 字节并读到文字层 —— 61789 字节（记录 size=61789）sha256=402f1c2806767360
[2.1s] PASS ★判定 1：未保存的编辑（独特拼音值）出现在预览 PDF 的文字层里 —— PDF 共 1 页；文字层片段=「练习字帖姓名：____________ 日期：____________1. kuàizhuǎn8pbp 2. shuǐ学生练习 | 第 1 / 1 页」
[2.1s] 服务器草稿（预览后）：revision=171 entries=[{"rowId":"row-1","text":"快照","pinyin":"kuài zhuǎn8pbp"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]
[2.1s] PASS 链路：预览把编辑器当前内容同步到了服务器草稿（修订号前进且含独特值） —— revision 170 → 171；条目=[{"rowId":"row-1","text":"快照","pinyin":"kuài zhuǎn8pbp"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]
[2.1s] PASS 对照：能把编辑恢复成原值 —— text="山" pinyin="shān"
[2.6s] PASS 对照：恢复后再次预览也产出新的 PDF blob —— 点击={"ok":true} PDF blob 数=2 → 3
[2.6s] PASS 对照：取回恢复后的 PDF 字节并读到文字层 —— 61067 字节 sha256=ef49714866754659
[2.6s] PASS ★判定 2：不做修改再预览时，独特值**不再**出现在 PDF 文字层里 —— 文字层片段=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」
[2.6s] PASS 对照：两次预览的 PDF 文字层不同（差异确实来自这次编辑） —— 编辑后文字层=「练习字帖姓名：____________ 日期：____________1. kuàizhuǎn8pbp 2. shuǐ学生练习 | 第 1 / 1 页」；恢复后文字层=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」；（字节 sha256 仅供参考、不可作判据：编辑后=402f1c2806767360 恢复后=ef49714866754659）
[2.7s] PASS 对照：恢复后的服务器草稿不再含独特值 —— revision=172 条目=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]
[3.2s] PASS 收尾：本地样册草稿已复原成验收开始时的内容（脚本可重复运行、不污染样册） —— 复原后=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}] 原始=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]（删行动作 0 次）
[3.2s] ===== 任务 1 结果：15/15 通过（正式验收）=====
```

**判定成立的关键一行**是 `[1.9s] 编辑后（预览前）服务器草稿：revision=160 ... "text":"山"`：
点预览的那一刻，**服务器上还是旧内容**，编辑确实**没保存**；而 0.5 秒后（`[2.4s]`）
PDF 文字层里已经是 `1. kuài zhuǎn4bfa`（独特值）—— 说明预览用的是**编辑器当前内容**。

> 上面的引用与随附日志 `editor-snapshot-normal.log` **逐字一致**（只去掉了 TAP 的 `# ` 前缀）。
> 每次运行的**随机独特值、修订号、PDF 字节哈希都会变**，逐字日志以随附文件为准；
> 本文件其余小节里的引用同理。

截图：`screenshots/editor-snapshot-preview-edited.png`
（预览面板里渲染出的练习卷，第 1 行显示的正是独特读音 `kuài zhuǎnzerf`）

### 2.3 反向验证：把"编辑器快照参与预览"改坏 → 断言必须失败

**改坏方式**（运行时注入，不改源码、不必重建）：把 `/draft/preview` 的响应体替换成
**编辑之前那次已保存草稿的响应**——这正是任务里点名的"让预览改用服务器已保存的草稿"。
（4173 提供的是**已构建产物**，改 `src/` 不会生效，所以反向验证必须在运行时做。）

**实际失败输出**（退出码 1，14/16 —— 只有与"预览读的是不是当前编辑内容"相关的两条失败）：

```
[1.8s] 编辑后（预览前）服务器草稿：revision=168 entries=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]
[1.8s] 反向验证：预览接口响应已被替换为"编辑之前已保存的草稿"
[2.3s] FAIL ★判定 1：未保存的编辑（独特拼音值）出现在预览 PDF 的文字层里 —— PDF 共 1 页；文字层片段=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」
[2.8s] FAIL 对照：两次预览的 PDF 文字层不同（差异确实来自这次编辑） —— 编辑后文字层=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」；恢复后文字层=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」；（字节 sha256 仅供参考、不可作判据：编辑后=b974899a2adb35be 恢复后=38ed073323f2747a）
[2.8s] PASS 对照：恢复后的服务器草稿不再含独特值 —— revision=170 条目=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]
[2.8s] PASS 反向验证机制：预览接口确实被换成了"已保存草稿"的响应 —— 被替换的预览响应次数=2
[3.2s] PASS 收尾：本地样册草稿已复原成验收开始时的内容（脚本可重复运行、不污染样册） —— 复原后=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}] 原始=[{"rowId":"row-1","text":"山","pinyin":"shān"},{"rowId":"row-2","text":"水","pinyin":"shuǐ"}]（删行动作 0 次）
[3.2s] ===== 任务 1 结果：14/16 通过（反向验证（预期失败））=====
  未通过：★判定 1：未保存的编辑（独特拼音值）出现在预览 PDF 的文字层里 —— PDF 共 1 页；文字层片段=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」
  未通过：对照：两次预览的 PDF 文字层不同（差异确实来自这次编辑） —— 编辑后文字层=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」；恢复后文字层=「练习字帖姓名：____________ 日期：____________1. shān 2. shuǐ学生练习 | 第 1 / 1 页」；（字节 sha256 仅供参考、不可作判据：编辑后=b974899a2adb35be 恢复后=38ed073323f2747a）
```

完整原始日志：`evidence/browser/editor-snapshot-reverse.log`

即：一旦预览改成读"已保存的草稿"，PDF 文字层里就**只剩 `shān / shuǐ`**，
独特值消失，判定 1 立刻失败 —— **断言确实咬住了这一层**。

---

## 3. 任务 2：管理端「打印预览」的浏览器端到端

脚本：`toolbox-web/tests/e2e/local-admin-print-preview-e2e.mjs`

### 3.1 先读实现，再决定怎么观察（这是本轮没再挂住的原因）

`src/lib/worksheet-print.ts` 的 `openPrintPreview(openWindow, options)` 是：

- `openWindow()` → 调用方（`src/composables/useAdminDraftPreview.ts:161`）传的是
  `() => window.open('', '_blank')` —— **新窗口 / 新 target**，不是 iframe；
- 窗口内由 `createPrintPreview` 同步搭出工具栏，**打印按钮初始 `disabled = true`**；
- 只有 `options.renderPageImage` 把**每一页**都渲染并解码成功后，`ready()` 才
  `printButton.disabled = false` 并加 `body.print-ready`（**就绪门**）；
- `closeButton.onclick` 与 `pagehide`/`unload` 都走 `dispose()` —— **窗口关闭即取消**。

因此观察方式定为：**CDP 的 target 列表**（新 target = 打印窗口真的被创建）
+ 在**打印窗口自己的 target 会话里**读就绪状态 + 关闭后该 target 消失。

### 3.2 判定证据（正式验收 10/10，退出码 0）

完整原始日志：`evidence/browser/print-e2e-normal.log`；结构化结果：`evidence/browser/print-preview-e2e.json`

```
[1.2s] PASS 前置：草稿预览已生成、打印按钮已解禁 —— 点击={"ok":true} 产出=true 解禁=true
[1.2s] PASS 手法：合成 el.click() 触发「打印预览」 —— {"ok":true}
[1.3s] PASS ★判定 1：点「打印预览」真的开出了新窗口（CDP 多出一个 page target） —— 新 target=A4D4D4DA7C88 url=about:blank
[1.3s] PASS 证据：window.open 确实被应用调用了一次并返回了窗口对象 —— open 调用 0 → 1；返回值=[true]
[1.3s] 打印窗口会话自检："about:blank | 教材草稿预览"
[2.0s] PASS ★判定 2：打印页全部就位后才解禁打印（就绪门生效） —— ready=true 打印按钮禁用=false 页图=1/1 已解码=1 状态=「」
[2.0s] PASS ★判定 3：全程 window.print 一次都没被调用（没有真的调起系统打印） —— 主页面计数=0；打印窗口自身的 print 计数=0；时间线=[]
[2.0s] PASS ★判定 2b：打印按钮**在窗口刚建出来时是禁用的**（就绪门不是事后补的） —— 首次出现打印按钮时：禁用=true 已就绪=false（观察器错误=[null,null]）
[2.0s] 关闭前，打印窗口自身的 print 计数={"own":0,"images":1}
[2.4s] PASS ★判定 4：关掉打印窗口后该 target 消失（窗口关闭即取消） —— 点击关闭={"ok":true} 之后仍在=false
[2.4s] PASS 兜底：关闭全程结束后依然没有任何系统打印调用 —— 主页面计数=0 探针={"printCalls":0,"openCalls":1,"timeline":[]}
[2.4s] ===== 任务 2 结果：10/10 通过（正式验收）=====
```

逐条对上任务要求：

| 任务要求 | 证据 |
| --- | --- |
| "打印预览确实被创建" | ★判定 1：CDP target 数变多，新 target 存在且其会话自检 `title=教材草稿预览`、工具栏 3 个按钮齐全 |
| "关闭后该信号消失" | ★判定 4：在打印窗口会话里点「关闭窗口」→ 轮询到该 target 从 `Target.getTargets` 里消失 |
| "没有真的调用系统打印" | ★判定 3：主页面计数 0 **且**打印窗口自身的 `print` 计数 0；调用链压根没走到 `target.print()`（就绪门 + 全程没人点「打印」） |
| "关掉它即可取消（设计要求的窗口关闭即取消）" | ★判定 4 + `worksheet-print.ts` 的 `dispose()`（关闭按钮与 `pagehide` 都走它）|

截图：`screenshots/print-e2e-window-ready.png`
（打印窗口：工具栏「导出 PDF / 打印 / 关闭窗口」+ "1 页" + 已渲染的 A4 页图，且「打印」按钮已是启用态）

### 3.3 反向验证：把"关闭即取消"改坏 → 断言必须失败

**改坏方式**（运行时注入，不改源码）：在 `window.open` 返回的打印窗口上
`Object.defineProperty(opened,'close',{value:()=>{计数但不关}})`，
并把 `addEventListener` 对 `pagehide`/`unload` 的注册吃掉 —— 于是"点关闭 → 窗口消失"必然失效。

**实际失败输出**（退出码 1，9/10 —— 只有"关闭即取消"这一条失败）：

```
[0.0s] 反向验证：运行时把打印窗口的 close/addEventListener 改坏（不改源码、不必重建）
[1.6s] PASS ★判定 1：点「打印预览」真的开出了新窗口（CDP 多出一个 page target） —— 新 target=A0527A1AD795 url=about:blank
[2.3s] PASS ★判定 2：打印页全部就位后才解禁打印（就绪门生效） —— ready=true 打印按钮禁用=false 页图=1/1 已解码=1 状态=「」
[2.4s] PASS ★判定 3：全程 window.print 一次都没被调用（没有真的调起系统打印） —— 主页面计数=0；打印窗口自身的 print 计数=0；时间线=[]
[2.4s] PASS ★判定 2b：打印按钮**在窗口刚建出来时是禁用的**（就绪门不是事后补的） —— 首次出现打印按钮时：禁用=true 已就绪=false（观察器错误=[null,null]）
[2.4s] 关闭前，打印窗口自身的 print 计数={"own":0,"images":1}
[17.7s] FAIL ★判定 4：关掉打印窗口后该 target 消失（窗口关闭即取消） —— 点击关闭={"ok":true} 之后仍在=true
[18.5s] ===== 任务 2 结果：9/10 通过（反向验证（预期失败））=====
  未通过：★判定 4：关掉打印窗口后该 target 消失（窗口关闭即取消） —— 点击关闭={"ok":true} 之后仍在=true
```

完整原始日志：`evidence/browser/print-e2e-reverse.log`
（注意：`点击关闭={"ok":true}` 说明**按钮被点到了**、且反向改坏也没影响其他判定 ——
失败点精确定位在"关闭后窗口还在"这一条上。`17.3s` 与 `18.1s` 的差值就是那 15 秒关闭等待超时，
脚本**有界**、不会挂住。）

---

## 4. 哪些做法**不成立**（附真实错误原文）

本轮**没有**用到真实鼠标与键盘：合成 `el.click()` + 原生 setter 已经足够，
所以不再重复验证 `Input.dispatchMouseEvent` 的不稳定性（沿用两条线的既有报告）。
以下是我**实际踩到并绕开**的失败做法，全部带真实报错/现象原文：

### 4.1 注入脚本里用顶层 `return` → 脚本**不执行**，且不报错

`Page.addScriptToEvaluateOnNewDocument` 的 `source` 里写了顶层 `return true;` 时，
整段脚本**语法非法、不执行**，但 `addScript...` 依然返回 `{"identifier":"1"}`。
现象是后续取值 `undefined`：

```
# Error: TypeError: Cannot read properties of undefined (reading 'some')
#     at <anonymous>:3:52
注入脚本 id 检查： false
```

**修正**：注入脚本必须用 IIFE 包住（`(() => { ... return true; })();`），或不写 `return`。

### 4.2 在页面加载**之后**才挂钩 `URL.createObjectURL` → 钩子永远不被用到

应用打包时已经把 `URL.createObjectURL` 的函数引用取在手里，页面加载后改 `URL` 没有意义。
现象：预览明明画出了 canvas，钩子却一个 blob 都没记录：

```
# [2.0s] PASS 证据：预览面板把 PDF 画了出来（canvas 数 ≥ 1） —— canvas=1
# [61.2s] FAIL 手法：合成 el.click() 触发「预览草稿」并产出新的 PDF blob —— 点击={"ok":true} blob 数=0
# [61.3s] FAIL 证据：取回本次预览的真实 PDF 字节 —— 没有记录到 blob 地址
```

**修正**：改用 `Page.addScriptToEvaluateOnNewDocument`，在**应用脚本之前**挂钩。

### 4.3 `waitFor` 的条件表达式有两种写法，混用会静默失效

`tests/e2e/cdp.mjs` 的 `waitFor` 原先固定拼 `return ${expression};`：

- 表达式以 `})()` 结尾时，紧跟换行再拼 `})()`，**ASI 会把它切成两个语句**，
  取值静默变成 `undefined`（不报错）。临时诊断输出就是"同一件事写成四种样子、结果不一样"：

  ```
  # [debug] A={"ok":true}      ← return { ok: true };
  # [debug] B=undefined        ← (() => { return { ok: true }; })();   （ASI 切断）
  # [debug] C=undefined        ← (() => { return { ok: true }; })()
  # [debug] D=undefined        ← ({ ok: true })
  ```

  上游后果：连"能不能找到「预览草稿」按钮"都拿不到，脚本报
  `TypeError: Cannot read properties of undefined (reading 'ok')`；
- 多语句体（`const x = ...; return ...;`）被括号包住时报
  `SyntaxError: Unexpected token 'const'`。

**修正**（已落到 `cdp.mjs`）：先按括号表达式求值，遇到 `SyntaxError` 再退回语句体形态执行。

### 4.4 `evaluate()` 不带 sessionId → 读到的是**主页面**，不是打印窗口

`tests/e2e/cdp.mjs` 的 `evaluate(cdp, expression)` 原本**不接受** sessionId，
于是"在打印窗口会话里读状态"实际上是在主页面求值。现象极具误导性
（看起来像产品缺陷：打印窗口没有工具栏、标题是主站标题）：

```
# [91.9s] FAIL ★判定 2：打印页全部就位后才解禁打印（就绪门生效） —— ready=false 打印按钮禁用=null 页图=0/0 已解码=0 状态=「」
# [91.9s] FAIL 证据：打印窗口是应用自己搭的结构 —— title=「禹宇天的工具箱」 按钮=[]
# [107.2s] FAIL ★判定 4：关掉打印窗口后该 target 消失 —— 点击关闭={"ok":false,"reason":"找不到「关闭窗口」按钮"} 之后仍在=true
```

这不只是多花时间：它让**第 140 轮那次"挂住超过 15 分钟"**有了明确解释 ——
脚本在**主页面**上轮询"打印窗口就绪"（永远不可能成立），只能等到超时/手动停掉。
**修正**：`evaluate` 增加可选 `sessionId` 参数（向后兼容），并加会话自检
（`location.href + ' | ' + document.title` → `about:blank | 教材草稿预览`）确认读对了窗口。

### 4.5 把 Chrome profile 写进 `docs/` 证据目录 → 226 MB 运行时垃圾

临时 profile 曾经建在 `docs/verification/.../evidence/browser/chrome-*`，
其中 `chrome-print-*` 一个目录就 69 MB（另有被存活进程持有的 110 MB / 107 MB）。

**修正**：新增 `createProfileDir()`/`removeProfileDir()`（`mkdtemp(join(tmpdir(),...))`），
并在 `launchChrome()` 加了**硬闸门**：`userDataDir` 解析后含 `/docs/` 直接抛错
（`拒绝把浏览器 profile 放进 docs/ 证据目录`）。清理数字见第 6 节。

**顺带发现并清掉的两类残留**（都不是我本轮新造的垃圾，但都属于这项验收的工作流）：

1. **第 140 轮脚本遗留的活着的浏览器**：`$TMPDIR/tb-print-vKxsBq/chrome`（104 MB）
   对应的 headless Chrome **从 03:50:29 一直活到 05:07**（1 小时 16 分，PPID 已变成 1、端口还是旧脚本默认的 9349）。
   这解释了为什么直接 `rm` 那个目录会失败：**文件被这个进程占着**。
   已按"精确匹配 `--user-data-dir=` 这一个路径"的方式只杀这一个进程树（其余 `/tmp/diag`、`playwright_chromiumdev_profile-*` 等属于别的会话，一律没碰）。
2. **我自己的清理不够硬**：原实现只 `child.kill('SIGKILL')` 后 `rm`，一旦有子进程活着就会静默留下目录。
   现在新增 `stopChrome(child, profileDir)`：先杀子进程，再按**完整 profile 路径**
   兜底 `pkill -f --user-data-dir=<本次 profile>`（只匹配自己这条路径）；
   `removeProfileDir()` 同时**重试 4 次、失败打印告警**而不是把错误吃掉。

修完后连续跑完 4 次 e2e（两项 × 正向/反向）+ 1 次手法验证，复查：
**我前缀的 profile 目录残留 0、我的 Chrome 进程残留 0、`docs/` 残留 0。**

### 4.6 「随便改一个文本框」可能让预览直接失败 —— 编辑必须**语义合法**

只把汉字改成 2 个字、拼音仍是 1 个音节（`山 / shān` → `快照 / shān`）时，
预览**直接失败、PDF 根本不会生成**。干净复现的原始输出：

```
# 原值： {"text":"山"}
# 改汉字为两个字： {"ok":true,"value":"快照"}
# 行状态： {"text":"快照","issue":"拼音音节数量需要与汉字一致。"}
# 预览面板： {"canvas":0,"block":"草稿预览...本次预览基于修订 147 · 范围 整册\n打印预览\n关闭预览\n暂时无法生成\nINCOMPLETE_ENTRY\n重新生成"}
# 复原后： {"text":"山"}
```

（`src/lib/worksheet.ts:111` 的 `INCOMPLETE_ENTRY` 守卫：当前题型需要的读音/释义不完整就抛错。）
**修正**：独特值要成对写（汉字数 = 拼音音节数），例如 `快照 / kuài zhuǎn<随机>`；
否则会把"编辑不合法"误判成"预览没实现"。

### 4.7 **PDF 字节的 sha256 不能当"内容变了"的判据**

`pdf-lib` 的子集字体名带**随机后缀**（内容流里可见
`/NotoSansCJKsc-Regular-7098480789`、`-9742682568`、`-2000805986`），
同一份内容两次生成，字节也不同。反向验证里就出现了这个假信号：

```
# 编辑后文字层=「…1. shān 2. shuǐ…」；恢复后文字层=「…1. shān 2. shuǐ…」
# （字节 sha256 仅供参考、不可作判据：编辑后=36e3e63cd374938a 恢复后=64b8919f010ea325）
```

**修正**：判据一律换成 PDF 的**文字层**。这条对后续所有 PDF 验收都适用。

### 4.8 旧的打印脚本为什么点不到按钮（第 140 轮第一次尝试）

`tests/e2e/local-admin-print-preview.mjs` 直接找「打印预览」按钮，但那个按钮
**只在 `previewOpen === true` 之后才渲染**（`TextbookEditorView.vue:1314`）。
所以它拿到的是 `null`（旧证据 JSON 里就是 `"extra": "结果=null"`），
`新窗口=[]`。**修正**：必须先合成点击「预览草稿」生成产物，再点「打印预览」。

---

## 5. 门禁结果

在 `toolbox-web/` 下：

| 门禁 | 最终一次实测 | 任务给定基线 | 结论 |
| --- | --- | --- | --- |
| `npm test` | `# tests 719 / # pass 719 / # fail 0`（exit 0） | 715 | ✅ **0 失败**（条数 +4 见下方说明） |
| `npx vitest run` | `Test Files 12 passed (12) / Tests 76 passed (76)`（exit 0） | 71 | ✅ **0 失败**（条数 +5 见下方说明） |
| `npm run typecheck` | `vue-tsc --noEmit` 无输出，exit 0 | — | ✅ |
| `npm run build:production` | `✓ built in 1.57s`，exit 0；**0 条告警**（唯一含 "error" 的行是产物文件名 `admin-error-messages-*.js`） | — | ✅ 0 告警 |

> **基线为什么从 715/71 变成 719/76（必须说清楚，不能含糊）**：
> 我第一次跑门禁时确实是 `715/715/0` 与 `71/71/0`。之后**同一工作区里有另一个并发工作者**
> 在 05:03–05:08 改了 `src/lib/admin-preview-scope.ts`、`src/composables/useAdminDraftPreview.ts`、
> `src/views/admin/TextbookEditorView.vue`，并新增/改了 `tests/admin-preview-scope.test.ts`、
> `tests/component/admin-editor-preview-publish.test.ts` —— 这正是 +4 与 +5 的来源。
> **我本人没有改动任何产品源码（`src/`）或任何 `*.test.ts`**（我的改动只在 `tests/e2e/` 与 `docs/`）。
> 为避免"结论建立在别人改动之前的产物上"，我在他们改动落地、重新构建之后**又完整跑了一遍两项验收**：
> 任务 1 = **15/15**、任务 2 = **10/10**，反向验证仍分别为 14/16 与 9/10（如期望般失败）。
> 也就是说：**结论在当前工作区状态下依然成立**。

- **后端未改动**，因此按任务约定**不跑** `mvn -o -f service/toolbox-service/pom.xml test`（基线 412）。
- 生产构建产物哈希与验收前一致（如 `TextbookEditorView-D-LQ7ntp.js`、`worksheet-print-CtAZQE6i.js`），
  说明 4173 上跑的预览与源码一致，上述端到端结论是在**当时那份产物**上取得的。

---

## 6. 环境、隔离与卫生

- 目标：`http://127.0.0.1:4173`（本地 preview）+ `127.0.0.1:23199`（父会话常驻后端，**只读**）。
  **没有**请求生产、**没有**部署、**没有**在生产建账号或发布教材。
- **没有杀 `127.0.0.1:23199`**，**没有**删改隔离 MySQL 容器 `toolbox-mysql-test`（仅回环 13316）。
- 样册 `local-acceptance-2026-10-07`：两次验收脚本都在收尾把草稿复原并轮询确认。
  本轮全部跑完后实测：`revision 158 [('row-1','山','shān'), ('row-2','水','shuǐ')]` ——
  **内容与初始完全一致**（我接手时读到的初始内容就是 `山/shān` + `水/shuǐ`）。
  唯一变化是**修订号**（每次保存必然加一，这是 CAS 修订号本身的语义，不是内容被留下改动）。
  （中途有一个一次性复现脚本的收尾把 row-1 拼音写成了空串，已用同样手法写回 `shān` 并复核到
  `与初始一致： true`；这也说明"编辑合法性"必须自己盯住，见 §4.6。）
- Chrome profile 污染清理（详见 `evidence/browser`）：

  | 时点 | `evidence/browser/` 的 `du -sh` |
  | --- | --- |
  | 处理前（实测） | **70M**（`chrome-print-79287` 69M；另两个 blob profile 被 14 个存活 Chrome 子进程持有） |
  | 清理后 | **68K**（只剩真正的证据 JSON） |

  同时删掉了本轮自己的三个调试脚本与调试产物；**没有**动该目录里其它文件
  （例如第 140 轮的历史证据 `evidence/print-preview-e2e.json` 原样保留）。
  之后每跑完一轮都复查过 `docs/` 下再无 `chrome-*`/profile 目录；
  最终一轮跑完（4 次 e2e + 1 次手法验证）复查：`docs/` 残留 `0`、
  我前缀的 `$TMPDIR` profile 残留 `0`、我起的 Chrome 进程残留 `0`、指向 `docs/` 的 Chrome 进程 `0`。
- Chrome 的 `--test-timeout` + `--test-force-exit` 全程保留，**任何脚本都不会无限挂住**
  （最慢一次整体 18.1 秒）。

---

## 7. 新增/修改的文件

新增验收脚本与工具（都在 `toolbox-web/tests/e2e/`）：

| 文件 | 作用 |
| --- | --- |
| `local-admin-synthetic-input-smoke.mjs` | 手法小规模验证（合成输入 → Vue → 真实保存载荷） |
| `local-admin-editor-snapshot.mjs` | 任务 1 验收 + 反向验证（`E2E_EXPECT=reverse`） |
| `local-admin-print-preview-e2e.mjs` | 任务 2 验收 + 反向验证（`E2E_EXPECT=reverse`） |
| `pdf-text.mjs` | 用站点自带 `pdfjs-dist` 读 PDF 文字层（**不新增依赖**） |

对既有文件的修改（`toolbox-web/` 在 git 里未被跟踪，因此这里逐条列出改动原文）：

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `tests/e2e/cdp.mjs` | ① `evaluate()` 增加可选 `sessionId`；② `evaluate()` 拼接前补分号；③ `waitFor()` 支持"表达式/语句体"两种形态；④ 新增 `createProfileDir()`/`removeProfileDir()`（带重试与告警）/`stopChrome()`（按精确 profile 路径兜底清理残留进程）；⑤ `launchChrome()` 拒绝 `docs/` 下的 profile | ①②③ 修掉"静默读到主页面 / 静默 undefined / SyntaxError"；④⑤ 修掉证据目录被 226 MB profile 污染且无提示的问题 |

> 这三处 `cdp.mjs` 改动都是**向后兼容的加固**（既有脚本 `run.mjs`/`scenario.mjs`/`browser-acceptance.mjs`
> 仍按原签名调用），并未改变任何一个既有验收的断言语义。

新增证据：

- `evidence/browser/editor-snapshot-normal.log`、`editor-snapshot-reverse.log`、`editor-snapshot-raw.json`
- `evidence/browser/print-e2e-normal.log`、`print-e2e-reverse.log`、`print-preview-e2e.json`
- `evidence/browser/editor-snapshot-*-text.json`（PDF 文字层原文）
- `evidence/browser/synthetic-input-smoke.json`
- `screenshots/editor-snapshot-preview-edited.png`、`editor-snapshot-preview-reverse.png`
- `screenshots/print-e2e-window-ready.png`、`print-e2e-window-ready-reverse.png`
- `pdfs/editor-snapshot-pristine.pdf`、`editor-snapshot-edited.pdf`、`editor-snapshot-second.pdf`

### 7.1 `tests/e2e/cdp.mjs` 的改动逐条存档（可据此还原）

`toolbox-web/` 在 git 里未被跟踪，且本机 `git` 当前不可用
（任何 git 命令都返回 `You have not agreed to the Xcode license agreements`），
因此这里把 5 处改动**原文**贴出来，便于审查与还原。
改动后文件：`toolbox-web/tests/e2e/cdp.mjs`，sha256 = `44fd4c6962d82c68425c7bba1eccd8e969f68b278d507b5f33491e50a441faf4`。

**① `evaluate()` 增加可选 `sessionId`（原签名 `evaluate(cdp, expression)`）**

```diff
-export async function evaluate(cdp, expression) {
+export async function evaluate(cdp, expression, sessionId) {
   const result = await cdp.send('Runtime.evaluate', {
     expression: `(async () => { ${source} })()`,
     awaitPromise: true,
     returnByValue: true,
     userGesture: true
-  });
+  }, sessionId);
```

**② `evaluate()` 拼接前补分号（防 ASI 把返回值吃掉）**

```diff
-  const result = await cdp.send('Runtime.evaluate', {
-    expression: `(async () => { ${expression} })()`,
+  const source = expression.trimEnd().endsWith(';') ? expression.trimEnd() : `${expression.trimEnd()};`;
+  const result = await cdp.send('Runtime.evaluate', {
+    expression: `(async () => { ${source} })()`,
```

**③ `waitFor()` 支持"单表达式 / 多语句体"两种形态**

```diff
-    last = await evaluate(cdp, `return ${expression};`);
+    last = await evaluate(cdp, `return (${expression});`).catch(async error => {
+      if (!/SyntaxError/u.test(String(error?.message ?? error))) throw error;
+      return await evaluate(cdp, expression);
+    });
```

**④ 新增 `createProfileDir()` / `removeProfileDir()`（含重试与告警）**

```diff
+export async function createProfileDir(prefix = 'toolbox-e2e-') {
+  return await mkdtemp(join(tmpdir(), prefix));
+}
+export async function removeProfileDir(dir) {
+  if (!dir) return;
+  for (let attempt = 0; attempt < 4; attempt += 1) {
+    const result = await rm(dir, { recursive: true, force: true }).then(() => true, () => false);
+    if (result) return;
+    await new Promise(resolve => setTimeout(resolve, 400));
+  }
+  console.warn(`临时浏览器 profile 未能删除（可手动清理）：${dir}`);
+}
```

（配套新增 `import { mkdtemp, rm } from 'node:fs/promises'`、`import { tmpdir } from 'node:os'`、
`import { join, resolve, sep } from 'node:path'`。）

**④b 新增 `stopChrome()`（按精确 profile 路径兜底清理残留进程）**

```diff
+export async function stopChrome(child, profileDir) {
+  try { child?.kill('SIGKILL'); } catch { /* 已退出 */ }
+  if (!profileDir) return;
+  await new Promise(resolve => setTimeout(resolve, 300));
+  try {
+    const { spawnSync } = await import('node:child_process');
+    // -f 匹配整条命令行；用完整 profile 路径，避免误杀其它会话的浏览器。
+    spawnSync('pkill', ['-f', `--user-data-dir=${profileDir}`], { stdio: 'ignore' });
+  } catch { /* pkill 不可用时忽略：前面的 child.kill 已经覆盖主进程 */ }
+}
```

**⑤ `launchChrome()` 拒绝把 profile 放进 `docs/`**

```diff
 export async function launchChrome({ executablePath, port, userDataDir }) {
+  const resolvedProfile = resolve(userDataDir ?? '');
+  if (resolvedProfile.includes(`${sep}docs${sep}`)) {
+    throw new Error(`拒绝把浏览器 profile 放进 docs/ 证据目录：${resolvedProfile}（请改用 createProfileDir()）`);
+  }
   const child = spawn(executablePath, [
```

### 7.2 新增脚本的文件哈希

| 文件 | sha256 |
| --- | --- |
| `tests/e2e/local-admin-editor-snapshot.mjs` | `120bd438f8e8b99dadae1b0d27aeb06582ffe7417af57d95acd32f75c19bc962` |
| `tests/e2e/local-admin-print-preview-e2e.mjs` | `66501d2e3a467f688668ad29c0383c19e51220ca4cbe136f2e2daab5c065e6b6` |
| `tests/e2e/local-admin-synthetic-input-smoke.mjs` | `d5eede9b87266ab761f476f96304cd7f605ab865c4b40fa84e3e71cd5b1317fe` |
| `tests/e2e/pdf-text.mjs` | `e60a427f1e92059298030842674cb1bc217b4faf0c21804c3c91be229fc49785` |

---

## 8. 对矩阵 `D05`（预览与打印）的建议状态

现状：`D05 = 部分`，理由是"四题型选择 + 预览快照已实现并有测试；**四题型 PDF 与打印预览样张仍在进行中**（浏览器线）"。

建议改为 **`已实现`**（浏览器端到端补齐），并给出如下子项切分：

| D05 子项 | 建议状态 | 依据 |
| --- | --- | --- |
| 题型选择 + 预览快照（范围/修订标注） | ✅ 已实现且有测试 | 既有 14 条 + 本轮任务 1 的 15/15 |
| **当前编辑快照 → PDF 预览** | ✅ **本轮补齐**（浏览器端到端） | 判定 1/2 + 反向验证失败输出（本文件 §2） |
| 打印预览 · 就绪门 · 窗口关闭取消 · 超时中止 · 不谎报成功 | ✅ 已实现且有 14 条单元/组件测试 | `print-preview-coverage.md`（`npx tsx --test tests/worksheet-print.test.ts` → 14/14） |
| **管理端「打印预览」浏览器端到端（真的开窗 + 关闭即取消 + 不调系统打印）** | ✅ **本轮补齐** | 判定 1/2/2b/3/4 + 反向验证失败输出（本文件 §3） |
| 四题型 PDF 各 ≥3 页 + 答案卷 | ✅ 已完成（前序轮次） | `four-mode-pdfs.md` |

**保守说明（不建议夸大的部分）**：

1. 本轮**没有**做"就绪门被绕过时强行点打印"的对抗（那部分由既有的
   `browser-acceptance.mjs` 早触发探针 + 14 条单元测试覆盖），
   但本轮**新增**了"打印按钮在窗口刚建出来时就是禁用的"这条**创建瞬间**证据（判定 2b）；
2. 本轮**没有**真的调用系统打印（这是设计要求的正确行为，也是判定 3 的内容）——
   因此"系统打印对话框长什么样"没有、也不该有截图；
3. 本轮只跑了 `local-acceptance-2026-10-07` 这一册（中文、单页 1 页）；
   四种题型 × 多页的覆盖仍以前序轮次的结论为准。
