# 三条"剩余项"端到端的实测结果（2026-10-07 第 168–171 轮）

## 结论速览

| 任务 | 结果 |
| --- | --- |
| **A09 第二账号 / 换账号不串草稿** | ✅ **28/28 全部 PASS** —— 可以收口 ✓ |
| **B03 撤销在真实页面的组合** | ⚠️ **E2E 出现 5 条 FAIL** ✗，但**读代码看实现是对的** ✓ → **尚不能判定是产品缺陷还是测试假象** ✗ |
| **B09 真实断网 / 丢响应** | ⚠️ **E2E 出现 4 条 FAIL** ✗，且**两次运行结果不一致**（先 PASS 后 FAIL）✗ → **疑似 flaky 或实现问题，未定** ✗ |

**三条我都用独立教材跑（`e2e-a09-*` / `e2e-b03-6lukn` / `e2e-b09-6o9na`）** ✓，
**没有改共享的验收样册** ✓。

---

## A09：✅ 全部通过（28/28）

关键断言（原文）：

```
PASS 匿名状态：没有编辑器实例，且落在非管理路由（守卫带 redirect 回来）
PASS A 的私稿确实在编辑器里（换账号前的前提成立）
PASS A 的私稿**没有**落库（被拦掉的保存没有写进服务端）
PASS 退出后编辑器实例已卸载（管理页不在 KeepAlive 里）
PASS A09-①：退出后**离开了管理路由**（没有停在编辑页）
PASS A09-②：退出后 A 的私稿在 DOM 与所有输入框里都找不到
PASS 浏览器会话确实是第二个账号（不是 A）
PASS B09/A09-③：B 进入编辑页时**重新挂载并重新加载**（观察到新的 GET /draft）
PASS A09-④：B 看不到 A 的私稿（DOM 与输入框都没有）
PASS A09-⑤：B 看到的内容逐条等于服务端草稿
PASS A09-⑥：页面修订号就是服务端修订号（不是缓存实例的旧状态）
```

**这正面回答了 A09 的缺口**（"仍缺真实浏览器端到端与第二账号"）✓：
**换账号后 A 的私稿在 DOM、输入框、路由、编辑器实例四处都清干净** ✓，
且 **B 看到的是服务端真值而不是缓存** ✓。

---

## B03：⚠️ E2E 失败 5 条，但代码读下来是对的

### 失败原文

```
FAIL 场景1：撤销后新单元真的从目录消失
      [{"unit":"第一单元","lessons":[]},{"unit":"临时单元撤销6t193","lessons":[]}]
FAIL 场景1：目录与基线逐字相同             （同上）
FAIL 场景2：撤销后第 1 行真的回到原值       实际="山撤销6t193"
FAIL 场景2：内容真的回到原值（页面 DOM）     实际="山撤销6t193"
FAIL 场景2：rowId 与顺序逐个未变
```

**但同一次运行里还有**：

```
PASS 场景1：撤销栈回到空（按钮重新禁用）
PASS 场景1：条目标识与顺序与基线逐字相同
PASS 场景1：页面提示「已撤销：新增单元」
PASS 场景2：新内容写在**同一行标识**上（没有新增行）
```

**"栈弹了、提示也显示了、标识与顺序没乱"** ✓，**但"内容没回去"** ✗ —— 这个组合很值得注意 ✓。

### 我读代码得到的（与 E2E 相反的）印象

```js
function recordBeforeChange(label) {
  history.record({ outline: outline.value, entries: entries.value }, label);  // 改动前记录 ✓
}
function onAddUnit() {
  recordBeforeChange('新增单元');                    // 先记录 ✓
  outline.value = addUnit(outline.value, title);     // 再换新 ✓
}
function undo() {
  const snapshot = history.undo();
  if (!snapshot) { ...return; }
  outline.value = snapshot.state.outline;            // 恢复 outline ✓
  entries.value = snapshot.state.entries;            // 恢复 entries ✓
  syncRowsToSelection();
  notice.value = `已撤销：${snapshot.label}。`;
}
```

**并且我专门查了"快照按引用存会不会失效"** ✓：**不会** ✗ ——
因为所有改动都是**换新数组**（`outline.value = addUnit(...)` ✓、`entries.value = [...entries.value, ...]` ✓），
**没有一处原地 `push`/`splice`** ✗ ✓ → 旧数组对象不会被改写 ✓，快照有效 ✓。

### 所以我**不能判定**它是不是产品缺陷

两种可能都存在，**我都没排除** ✗：
- **(a) 产品缺陷** ✗：例如"撤销后又被某个 watcher／自动保存回读覆盖回去"（`PASS 场景1：撤销栈回到空` 说明栈在动 ✓，但内容被别的东西写回了 ✗）；
- **(b) 测试假象** ✗：例如测试点到的"撤销"不是历史撤销 ✓、或在撤销之后又发生了一次同步 ✓。

**我**不**在证据不足时把它写成"产品有 bug"，也**不**写成"通过"** ✗ ✓ ——
它现在就是**未定** ✗，需要下一步**定位**（见下）。

### 一个已经明确的边界（不属于 bug）

**场景2 是"文本编辑"** ✓，而撤销栈的文档与设计 B03 都只说
「**重命名／重排／删除**后撤销」✓ —— **纯文本内容编辑本来就不在这个撤销范围内** ✗ ✓。
所以"文本编辑后按撤销不还原"**可能不是缺陷** ✗，而是**设计边界** ✓；
**但场景1（新增单元）是结构改动** ✓，**它失败才是关键** ✗。

---

## B09：⚠️ 4 条 FAIL，且两次运行结果不一致

### 失败原文

```
FAIL 相位A：界面如实显示失败              ["草稿已保存。"]
FAIL 相位A：**没有**谎报成功（页面没有「已保存」提示）   ["草稿已保存。"]
FAIL 相位A：保存时间戳没有前进（savedAt 不变）   "修订 2" → "修订 2 · 保存于 05:26:23"
FAIL 相位C：界面看到的是失败（不谎报成功）    ["草稿已保存。"]
```

**同时 PASS 的很关键**：

```
PASS 相位A：服务端草稿**一个字节都没变**（内容与修订号都未动）
PASS 相位A：被拦下的写请求
PASS 相位B：重试成功且修订号只前进 1
PASS 相位B：内容写入且只出现一次（不产生重复条目）
PASS 相位B：条目标识与顺序不变
PASS 相位C：同键重放返回 200 且**返回原结果**
PASS 相位C：同键重放**没有再写一次**（修订号不再前进）
PASS 相位D：同键第二次发布返回同一个版本号（复用回执）
PASS 相位D：版本记录只有一条（不产生重复版本）
```

**所以幂等性、不产生重复、丢响应后同键重放都过了** ✓✓ —— **这部分是好的** ✓。
**失败的全是同一件事：界面在写请求被拦下时仍显示「草稿已保存。」** ✗

### 但它**两次运行结果不一致** ✗

我第一次读同一份 JSON 时，`相位A：界面如实显示失败` 是 **`ok: true`** ✓、
detail 是 `["操作失败：网络连接失败。当前编辑内容仍保留在本页，请检查网络后重试…` ✓；
**重跑后同一项变成 FAIL** ✗、detail 变成 `["草稿已保存。"]` ✗。

**同一脚本、同一实现、两次结果不同** ✗ → **这条是 flaky 的** ✓，
**或者代理在两次之间改了实现/测试** ✗ —— **我都没有排除** ✗ ✓。

### 代码线索（值得下一步查）

`TextbookEditorView.vue:1138`：

```js
message.value = outcome === 'saved' ? '草稿已保存。' : '有新改动待保存。';
```

**提示只取决于 `outcome === 'saved'`** ✓ —— 所以问题会落在
**"写请求被拦下时 `outcome` 为什么还是 `saved`"** ✗ ✓：
可能是 **`outcome` 来自上一次成功的保存** ✗、
或 **拦截发生在"界面已认为成功"之后** ✗、
或 **`emulateNetworkConditions` 对这次请求没生效而请求其实成功了**（但那样服务端就会变 ✗，而实测服务端**没变** ✗ ✓）。

---

## 下一步该做什么（给接手者）

| 任务 | 具体做法 |
| --- | --- |
| **B03 定位** | 在真浏览器里：记下撤销前/后 `outline.value`、`entries.value`、`history` 长度，并在 `undo()` 里**临时打点**，看**恢复之后是否有 watcher/自动保存把它写回** ✗。若确认是回写，那就是**产品缺陷** ✗；若确认是测试点错元素，则是测试问题 ✓ |
| **B09 定位** | 用 CDP 记录**这一次写请求的真实结果**（是否真的失败 ✓），再记录 `outcome` 的来源 ✓。**同一脚本连跑 ≥3 次**以确认是否 flaky ✗ |
| **B03 场景2** | 先确认设计意图：**纯文本编辑是否应可撤销** ✗ —— 若不应，测试应删掉该场景 ✓ 或改判为"设计边界" ✓ |

**我**没有**修改任何产品代码来"让测试通过"** ✗ ✓ ——
因为**我还没确定失败属于产品还是测试** ✗，此时改代码属于**猜** ✗。

---

## 第 172 轮：**B09 已定位** —— 产品行为正确 ✓，失败的是一条**测试侧**的修订号期望 ✗

### 决定性证据：连跑 3 次

关键断言 **`相位A：界面如实显示失败` 三次全 PASS** ✓✓，详情为：

```
["操作失败：网络连接失败。当前编辑内容仍保留在本页，请检查网络后重试。 当前编辑内容仍保留在本页。"]
```

**所以我在第 171 轮看到的"界面谎报成功（草稿已保存）"是瞬时的** ✗ ✓ ——
**产品在写请求被拦下时，确实会如实报错、且提示内容仍保留** ✓✓。

### 真正失败的那一条（38 条里只有 1 条）

```
FAIL  相位C：服务器其实已经写入（响应丢在路上）
      revision 3 → 7
```

**这是"修订号应该只前进 1"的期望落空** ✗ —— 实际跳了 **4** ✓。
结合上下文（这是相位C 的**前置条件**断言 ✓，用来确认"服务端其实写进去了"✓），
**更可能是测试对修订号增量的假设过严／存在竞态** ✗ ✓，
**而不是产品多写了** ✗ —— 因为同一次运行里这些**全部 PASS** ✓：

```
PASS 相位C：同键重放返回 200 且**返回原结果**
PASS 相位C：同键重放**没有再写一次**（修订号不再前进）
PASS 相位C：内容没有第二份（同一条内容只出现一次）
PASS 相位C：条目标识与顺序仍然不变
PASS 相位D：版本记录只有一条（不产生重复版本）
PASS 相位B：重试成功且修订号只前进 1
```

**"重试只前进 1" ✓ 与 "相位C 跳了 4" ✗ 并存** ✓ ——
说明**跳 4 的那次不是重试造成的** ✗ ✓，而相位C 的前置条件断言**没有把"脚本自己前面几次保存"算进去** ✗ ✓。

### 所以 B09 怎么记

| 维度 | 结论 |
| --- | --- |
| **产品行为**（如实报错 / 不谎报 / 服务端不被误改 / 重试幂等 / 不产生重复版本与重复条目）| ✅ **正确**，且关键断言**三次稳定通过** ✓ |
| **测试脚本** | ⚠️ 有 1 条**对修订号增量的期望过严／有竞态** ✗ —— 属**测试侧问题** ✓ |
| B09 这一项 | **可以收口** ✓（原缺口"真实断网/丢响应端到端未做"**已经做了** ✓），**但要把那条过严的断言修掉或标注** ✗ |

**我没有改任何产品代码** ✓ —— 因为证据指向**测试侧** ✓；
**我也没有去改那条测试来"弄绿"** ✗ ✓ —— 因为**改测试同样要基于确诊** ✓，
而"修订号为什么跳 4"我**还没有逐帧确认**（可能是脚本前面几次保存累积 ✓，也可能是竞态 ✓）✗。
**这一步留给下一步，并且我已经把判断依据写清楚了** ✓。

---

## 待办（按优先级）

| 优先级 | 事项 | 做法 |
| --- | --- | --- |
| 高 | **B09 那条断言**：确认"revision 3 → 7"是脚本自身累积还是竞态 | 在脚本里打印每次写请求前后的 revision ✓，对照相位A/B/C 的轨迹字段（JSON 里已有 `草稿修订号轨迹` ✓）|
| 高 | **B03 定位**：撤销后是否有 watcher/自动保存把内容写回 | 在 `undo()` 里临时打点，对比恢复前后的 `outline.value`/`entries.value` |
| 中 | **B03 场景2 是否应可撤销** | 先确认设计意图（纯文本编辑 vs 结构改动），不成立就改测试的期望 |
| 低 | A09 从"部分"改为"通过" | 28/28 已通过 |

---

## 第 173 轮：B03 的**代码通读结论**（与 E2E 矛盾，我如实记为"未定"）

我原以为快照失效的原因是"按引用存 + 原地改" ✗。**逐层查完，这个假设不成立** ✗：

| 检查 | 结果 |
| --- | --- |
| 赋值点是否原地改 | ❌ 都不是 —— `outline.value = addUnit(...)` ✓、`entries.value = [...entries.value, ...]` ✓、`outline.value = removeUnit(...)` ✓ |
| `addUnit` 内部 | 返回**新对象** `{ units: [...outline.units, unit], lessons: outline.lessons }` ✓ |
| 其他 helper（`markConfirmed` / `detachRemovedLessons` / `appendWithDefaultSource` / `renameUnit` / `moveNode`）| 未发现对**入参**的 `push`/`splice`/`sort`/`pop`/`shift` ✓；命中的 `push` 都在**局部 `result`** 数组上 ✓ |
| `undo()` 本身 | **同时恢复** `outline.value` 与 `entries.value` ✓，并 `syncRowsToSelection()` ✓ |
| `recordBeforeChange` 时机 | 在改动**之前** ✓（如 `onAddUnit`：先 `recordBeforeChange('新增单元')` 再 `outline.value = addUnit(...)` ✓）|

**所以按代码读，撤销应当生效** ✓ —— **但 E2E 实测它没生效** ✗
（`临时单元撤销6t193` 仍在目录 ✓、第 1 行仍是 `山撤销6t193` ✓），
**而同一运行里"撤销栈回到空"✓ 与"提示已撤销"✓ 都通过** ✓。

### 我不猜，也不改代码

**矛盾没解开之前** ✗：
- **改产品**＝猜 ✓；
- **改测试**＝猜 ✓；
- **写成"通过"**＝伪造 ✗；
- **写成"产品有 bug"**＝也不是证据支持的结论 ✗。

**所以 B03 保持"部分"，理由写清为"撤销在真实页面未生效，代码通读与 E2E 结论矛盾，待运行时打点定位"** ✓。

### 下一步的**具体**做法（可执行）

在 `undo()` 里临时打点，一次性回答"恢复到什么、之后又被谁覆盖"✗：

```js
// 临时插桩（验收后删除）
console.log('[undo] before', JSON.stringify({ outline: outline.value, entries: entries.value }));
const snapshot = history.undo();
console.log('[undo] snapshot', JSON.stringify(snapshot?.state));
outline.value = snapshot.state.outline;
entries.value = snapshot.state.entries;
console.log('[undo] after', JSON.stringify({ outline: outline.value, entries: entries.value }));
// 再等 2 秒（跨过自动保存防抖），看是否被写回
setTimeout(() => console.log('[undo] +2s', JSON.stringify({ outline: outline.value, entries: entries.value })), 2000);
```

**三种可能，各自对应不同结论**：

| 打点结果 | 结论 |
| --- | --- |
| `[undo] after` 已恢复但 `[undo] +2s` 又变回去 | **产品缺陷** ✗ —— 有 watcher/自动保存回写 ✓ |
| `[undo] after` 就没恢复 | **产品缺陷** ✗ —— 快照或恢复路径有问题 ✓ |
| `[undo] after` 与 `+2s` 都正确恢复了 | **测试假象** ✗ —— E2E 的观察时机/取值方式有问题 ✓ |

**这一步做完，B03 就能定性** ✓ —— 我不在缺少这一步时下结论 ✓。

---

## 第 174 轮：B03 **缩小到一个具体机制**（仍不宣布定性，但已可指认）

读代码时发现一处**未被 `undo()` 覆盖**的写入路径 ✗：

`TextbookEditorView.vue:732-743`

```js
watch(selectedNode, (_next, previous) => {
  // **切换节点前先提交当前输入缓冲**（设计第 118 行：不能悄悄丢失）。
  if (previous) {
    commitRowsFor(previous);   // ← 把"编辑器输入缓冲 rows"合并回 entries ✗
  }
  syncRowsToSelection();
});
```

而 `undo()`（`:203-213`）**只**做三件事：

```js
outline.value = snapshot.state.outline;
entries.value = snapshot.state.entries;
syncRowsToSelection();            // 用当前 entries 重建 rows
```

**关键点**：`undo()` **没有清掉编辑器输入缓冲** ✗ ✓。
若在撤销之后发生任何一次 `commitRowsFor(previous)`（例如 `selectedNode` 变化 ✓、
或用户接着点了别处 ✓），**缓冲区里那份"改过的文本"就会被合并回 `entries`** ✗ ✓ ——
**表现正是"提示说已撤销、撤销栈也弹了、内容却还是新值"** ✓。

### 但这**只解释了场景2（文本）**，还没解释场景1（新增单元）✗

场景1 观察的是**目录（`outline`）** ✗，而 `undo()` 直接替换了 `outline.value` ✓ ——
**若替换生效，目录里就不该还有那个单元** ✗。所以：
- 场景1 的失败**要么**说明 `snapshot.state.outline` 当时**已经不是"加单元之前"** ✗（快照时机错 ✓）；
- **要么**说明观察发生在一个**又被合并写回**之后 ✗。

**这两种我仍未区分** ✗ ✓ —— **但现在的候选已经窄到"快照时机"与"输入缓冲回写"两处** ✓，
不再是"整条撤销链路不知道哪里有问题" ✗ ✓。

### 下一步（一次插桩即可定性）

在 `onAddUnit`、`commitRowsFor`、`undo` 三处打印**同一个对象引用与内容**：

```js
const snap = () => JSON.stringify({ outline: outline.value, entries: entries.value });
// onAddUnit：recordBeforeChange 之前 与 之后 各打印一次
// commitRowsFor 入口：打印它准备写回的 rows 与当前 entries
// undo 入口/出口：打印 snapshot.state 与恢复后的值
```

**判读**：
- 若 `recordBeforeChange` 时打印的 `outline` **已经含新单元** ✗ → **快照时机错**（产品缺陷）✗；
- 若 `commitRowsFor` 在 `undo` 之后被调用且写回旧值 ✗ → **输入缓冲回写**（产品缺陷）✗；
- 若两者都正常 ✓，恢复后目录也确已无该单元 ✓ → **E2E 观察时机问题**（测试侧）✗。

**我仍然没有改任何一侧** ✓ —— 但**结论区间已从"整条链路"缩到"两处候选"** ✓，
下一轮一次插桩就能定 ✓。

---

# ⚠️ 第 176 轮：**本文件第 171–175 轮的 B03 / B09 结论作废**（我把反向验证日志当成了产品行为）

## 我错在哪

**反向验证的日志被写进了同一个证据目录** ✓，而我**没有看 mark 值就当成产品行为读了** ✗ ✓。

区分方法（子代理指出 ✓）：**mark 由 `Date.now().toString(36).slice(-5)` 现场生成、无环境变量可覆盖** ✓ →
**前向与反向不可能撞同一个 mark** ✓：

| 项 | 前向（真实产品）| 反向（故意改坏）|
| --- | --- | --- |
| **B03** | 标记 **`撤销760t9`** ✓，**18/18 PASS，退出码 0** ✓ | 标记 `撤销6t193` ✗，12/18，退出码 1 ✓ |
| **B09** | 标记前向 ✓，**26/26 PASS，退出码 0** ✓ | 22/26，退出码 1 ✓ |
| **A09** | 标记前向 ✓，**21/21 PASS，退出码 0** ✓ | 退出码 1 ✓ |

**所以 `临时单元撤销6t193 仍在目录` 与 `第 1 行仍是 山撤销6t193` 正是"改坏后**必须**失败"的证据** ✓，
**而我把它读成了"产品撤销不生效"** ✗✓ —— **这是我的错误，不是产品的** ✓。

**同理**：我看到的 `["草稿已保存。"]`（谎报成功）来自**把"失败时显示错误"改坏成"静默当成功"** ✗ ✓，
**前向的 26/26 里，界面是如实报错的** ✓。

## 更正后的结论

| 项 | 前向 | 反向 |
| --- | --- | --- |
| **B03 撤销组合** | ✅ **18/18**：新增单元→撤销→目录**逐字回基线**；结构操作+合成输入改内容（自动保存已落库 revision 3）→撤销→**第 1 行真的回到「山」**、rowId 与顺序逐字不变；撤销后再输入 rowId 仍是同一批 | 改坏 `undo()` 为"只提示不还原" → **6 条 FAIL** ✓ |
| **B09 断网/丢响应** | ✅ **26/26**：真断网 → 如实报错 ✓ **不谎报** ✓ savedAt 不前进 ✓ **服务端一字未变** ✓；恢复后重试 200、内容只出现一次 ✓；丢响应 → **同键重放两次同一 revision 7** ✓、旧修订号保存被 **409** 拒（**无静默覆盖**）✓；同键发布两次**版本记录只有 1 条** ✓ | 改坏成"静默当成功" → **4 条 FAIL** ✓ |
| **A09 第二账号** | ✅ **21/21**：真实退出后**离开管理路由** ✓、**编辑器实例已卸载** ✓、**DOM/输入框都找不到 A 的私稿** ✓；B 登录（实测 `local-admin-2/local-admin-1002`）后**重新挂载重载**（新的 GET /draft）✓、内容等于服务端草稿 ✓ | 删掉"退出后离开管理页" → 退出码 1 ✓ |

**A09 的第二账号是在测试源集加的** ✓（`LocalAdminIdentityTestConfig.java` + `application-local-admin.yml` 的**可选** `local-stub-account-2`，**默认空=不启用** ✓，
`@Profile("local-admin")` + `@ConditionalOnProperty` 双重闸门不变 ✓）—— **未在生产建账号、未伪造账号** ✓。

## 🔴 本轮新发现的**真实缺陷**（未修，已量化复现）

### R1（P1）**自动保存每约 2 秒重复写一次、永不停止** ✗

`save()` 开头调 `onRowsUpdate()` ✓，而它**无条件** `markDirtyAndSchedule()` ✗ →
**每次保存都把"脏"标回来，再排下一次保存** ✗ → 自我续排 ✓。

复现脚本 `toolbox-web/tests/e2e/remaining-b09b-autosave-loop.mjs`（**当前代码上必然失败** ✓，日志 `07-autosave-loop-repro.log` ✓）：

```
PASS 对照组①：不输入时 0 次写请求
INFO 一次输入后 12 秒内写请求间隔(ms) —— [2011,2008,2008,2004]，修订号 2 → 7
FAIL 主组：实际 5 次写请求
FAIL 静置期间修订号多出 5 次
FAIL 对照组②：不改内容只点一次「保存草稿」 → 4 次写请求，修订号 7 → 11
```

**影响** ✓：① **整份草稿每 2 秒写库**（写放大 ✓）；② **修订号/ETag 无限增长** ✓；
③ **切到首页卸载编辑器后写请求仍未停** ✓（实测 `{"quiet":false, 11→14}` ✓）；
④ **任何"修订号恰好 +1"的断言都只是时序巧合** ✓（我已据此把 B09 判据改成与循环无关的形式 ✓）。

### R2（P2）`SaveState.fail()` **没有接线** ✗

网络失败后重试**不复用幂等键** ✓（实测 `failedKey=save-…whyn` vs `retriedKey=c783…`，`reused:false` ✓），
且 `inFlight` 永不清空 ✓ —— **设计好的"同键重试"只存在于单测里** ✗ ✓。

### R3（P2）丢响应后客户端修订号是旧的 ✗

下一次保存会 **409** 进冲突面板 ✓ —— **CAS 行为正确** ✓，但配合 R2 用户会多撞一次冲突 ✓。

## 门禁（还原后的源码上跑）

`npm test` **719/719** ✓ · typecheck 0 ✓ · `build:production` **0 告警** ✓ · `mvn -o test` **412 / 0 failures** ✓。
vitest 一次并发高负载下 1 条时序敏感失败 ✓（该测试 3 次全量 + 3 次单文件均全绿 ✓，前端 src 与开工前**逐字节一致** ✓）。

## 清理

`docs/` 下 `chrome-*` = **0** ✓；父会话的 `23199`/`4173` **全程未动** ✓（`lsof` 复核 ✓）；生产**零写入** ✓。
**唯一的非新增改动在测试源集**（A09 第二账号）✓；三处反向验证断点**已还原** ✓
（`grep -rn "反向验证·临时改动" toolbox-web/src` = 0 ✓，两个文件 sha256 与开工前一致 ✓）。

## 我的教训（写下来）

**反向验证的产物必须与产品行为产物在物理上分开** ✗ ✓ ——
本轮两者都写进了 `evidence/browser/` ✓，**我只看了内容、没看 mark** ✗ → 读反了 ✓。
**今后读证据先对"这次运行的标识"（mark/时间戳/文件名前缀）** ✓，再读内容 ✓。

---

# 第 177 轮：R1 的**第一版修法：消除了循环，但破坏了 3 条既有测试 → 已撤回**

## 我做的

**定位**（`TextbookEditorView.vue:723-727`）：

```js
// **加载期间不标脏**：那是程序在填内容，不是用户在改。
if (!loadingDraft) {
  markDirtyAndSchedule();
}
```

而 `save()` 开头会**同步一次编辑器内容** ✓：

```js
// 先同步一次编辑器内容，避免最后一次输入未进入草稿（只合并当前可见子集）。
onRowsUpdate(rows.value);          // ← 这次同步也满足 !loadingDraft ✗
```

→ **每次保存都把"脏"标回来 → 再排下一次保存 → 每约 2 秒无限写库** ✓。

**修法**（沿用代码里**已有**的同一概念 —— `loadingDraft` 就是"程序在写内容时不标脏"✓）：
新增 `syncingForSave` 标志 ✓，把守卫改成 `if (!loadingDraft && !syncingForSave)` ✓，
并在 `save()` 那次同步前后置位/复位 ✓。

## 结果：**循环确实消除了** ✓✓

**修复后**跑复现脚本（`remaining-b09b-autosave-loop.mjs`，先 `npm run build:production` 重建 dist ✓）：

```
PASS 对照组①：不输入时不该有任何草稿写请求 —— 0 次
INFO 主组实测：一次输入后 12 秒内的写请求间隔(ms) —— []          ← 不再重复 ✓
INFO 主组实测：修订号轨迹 —— 2 → 3                                ← 只前进 1 ✓
PASS 主组：一次内容改动只应触发一次保存 —— 实际 1 次写请求
PASS 主组：静置期间服务端修订号不该继续前进
PASS 对照组②：没有内容改动时，保存一次之后不该继续重复写 —— 实际 1 次写请求，修订号 3 → 4
# pass 1 / # fail 0
```

**反向验证**（把守卫改回只看 `loadingDraft` ✓，并保留变量被读以免编译失败 ✓）**如期失败** ✓：

```
INFO 主组实测：写请求间隔(ms) —— [1999,2008,2010,2008]     ← 循环回来 ✗
INFO 主组实测：修订号轨迹 —— 2 → 7                          ← 又是 2→7 ✗
FAIL 主组：一次内容改动只应触发一次保存 —— 实际 5 次写请求，修订号 2 → 7
FAIL 主组：静置期间服务端修订号不该继续前进 —— 多出 5 次
# pass 0 / # fail 1
```

**所以这个修法对 R1 本身是有效的** ✓（修复前 5 次/2→7 ✗ → 修复后 1 次/2→3 ✓✓）。

## 但它**破坏了 3 条既有 Node 测试** ✗ → **我已撤回**

```
not ok 47 - F02：同一行连续输入时 rowId 不变，且编辑器行拿回了 tokenId
not ok 48 - F03：在选中单元里逐字输入，只产生一条且归属该单元
not ok 49 - 前面有一行两侧全空时，后面那行的身份不会被错位安到它头上
# pass 716 / # fail 3        （基线是 719 / 0）
```

**这 3 条都是 `onRowsUpdate` 的 identity／tokenId 行为** ✗，而我的改动**按读代码只应影响"是否标脏"** ✓ ——
**所以这里有我没看懂的耦合** ✗ ✓（可能：那些测试依赖"同步会安排一次保存"从而走完某条链路 ✗）。

**我选择撤回** ✓：**留下一个绿的套件** 比 **抢一个自己没解释清楚的修法** 重要 ✗ ✓。

**撤回后**：`npm test` **719/719** ✓、`npx vitest run` **76/76** ✓、typecheck 干净 ✓、`build:production` **0 告警** ✓、
`grep -c syncingForSave` = **0** ✓（无残留 ✓）。

## 所以 R1 现在的状态

| 维度 | 状态 |
| --- | --- |
| **缺陷是否存在** | ✅ **存在且可复现**（脚本 + 日志 ✓）|
| **根因** | ✅ **已定位**（`save()` 的同步满足 `!loadingDraft` → 自我续排 ✓）|
| **修法是否找到** | ⚠️ **找到了一个对 R1 有效的**（`syncingForSave` 守卫 ✓，前后对比与反向验证都成立 ✓）|
| **能否直接合入** | ❌ **不能** ✗ —— 它破坏 F02/F03/身份对齐 3 条既有测试 ✓，**耦合原因未明** ✗ |
| **当前代码** | **未修** ✗（已撤回 ✓，套件全绿 ✓）|

**下一步该做的** ✓：跑那 3 条失败测试，看它们**为什么依赖"同步会标脏"** ✓ ——
大概率是**测试自己**在用 `onRowsUpdate` 当"用户输入"的替身 ✗ ✓，
那样的话修法应当是**给测试传"这是程序同步"的语义** ✗（而不是把守卫撤掉 ✓）。
**在弄清这层耦合之前，我不会再动产品代码** ✓。

---

# 第 178 轮：**R1 已修复并验证** ✓（含"为什么撤回的那版会挂"的答案）

## 上一版为什么会挂（我第 177 轮没看懂的耦合）

`tests/admin-editor-row-identity.test.ts` 的做法是**从真实 `.vue` 源码里把 `onRowsUpdate` 抠出来执行** ✓：

```js
const end = editorSource.indexOf('\n}\n', marker.index);
const output = ts.transpileModule(source, ...).outputText;
return new Function(...Object.keys(bindings), `${output}\nreturn ${name};`)(...Object.values(bindings));
```

**它要给被抠出的函数提供一份完整的自由变量绑定** ✗ ✓ ——
我新增的 `syncingForSave` **不在那份绑定里** → 执行时 `ReferenceError` → 3 条测试失败 ✓。

**而且这个模式在夹具里是既有且已注释的** ✓✓：

```js
// 第 79 轮给 `onRowsUpdate` 加了这个守卫（加载期间不标脏）；
// 本测试只关心行身份，给它一个不在加载中的值即可。
loadingDraft: false,
```

**所以正确答案不是撤掉守卫** ✗，**而是沿用既有模式给夹具补一个同类 stub** ✓ ——
**这不是放松测试** ✗（测试关心的仍是行身份 ✓），**保存时序另有覆盖** ✓。

## 最终修改（两处）

| 文件 | 改动 |
| --- | --- |
| `src/views/admin/TextbookEditorView.vue` | 新增 `syncingForSave` 标志 ✓；`onRowsUpdate` 的标脏守卫改为 `if (!loadingDraft && !syncingForSave)` ✓；`save()` 里那次同步用 `try/finally` 置位/复位 ✓ |
| `tests/admin-editor-row-identity.test.ts` | 沿用既有模式补 `syncingForSave: false` ✓（含说明注释 ✓）|

## 验证（三方齐全）

**① 复现脚本由红转绿** ✓（修复前 → 修复后）：

| | 修复前 | 现在 |
| --- | --- | --- |
| 一次输入后 12 秒内间隔(ms) | `[1999,2008,2010,2008]` ✗ | **`[]`** ✓ |
| 修订号轨迹 | `2 → 7` ✗ | **`2 → 3`** ✓ |
| 脚本结果 | `pass 0 / fail 1` ✗ | **`pass 1 / fail 0`** ✓ |

**② 反向验证**（第 177 轮做过 ✓，把守卫撤回到只看 `loadingDraft`）：复现脚本**如期失败** ✓
（间隔 `[1999,2008,2010,2008]`、5 次写、2→7 ✓）。

**③ 全量门禁全绿** ✓：

| 检查 | 结果 |
| --- | --- |
| `npm test` | **719 / 719** ✓ |
| `npx vitest run` | **76 / 76** ✓ |
| `npm run typecheck` | 干净 ✓ |
| `npm run build:production` | **0 告警** ✓ |

## R1 的最终状态

| 维度 | 状态 |
| --- | --- |
| 缺陷是否存在 | ✅ 存在且**可复现**（脚本 + 日志 ✓）|
| 根因 | ✅ 已定位 ✓ |
| **修复** | ✅ **已实施并验证** ✓（复现脚本转绿 + 反向验证 + 全量门禁 ✓）|
| **回归闸门** | ✅ `tests/e2e/remaining-b09b-autosave-loop.mjs` —— **修复前失败、修复后通过**，可长期用作闸门 ✓ |

---

# 第 179 轮：**R2 已接线并验证** ✓（并说明它意外修掉了什么）

## 根因与修法

`src/lib/admin-save-queue.ts` 的 `SaveState` **本身是对的** ✓：`fail(op)` 会把冻住的操作（含**原幂等键**与原 payload ✓）放进 `pendingRetry`，而 `begin()` **优先复用**它 ✓。

**问题是 `fail()` 从未被调用** ✗ —— `TextbookEditorView.vue` 的 `save()` catch 里：

```js
} catch (cause) {
  if (409) { enterConflict(cause, frozen); }                  // ✓ 正确（conflict 会放弃重试）
  else if (cause instanceof AdminApiError && cause.status === 422) { ... }   // ✓ 不排重试（重发也会失败）
  else {
    errorText.value = ...;        // ← 只设文本 ✗ 从未 saveStateRef.value.fail(frozen)
  }
}
```

**修法**（`else` 分支）：

```js
if (frozen) {
  saveStateRef.value.fail(frozen);
}
errorText.value = ...;
```

**只改瞬时/网络失败这一支** ✓：
- **409** 仍交给 `conflict()` ✓（它主动放弃重试 ✓，正确 ✓）；
- **422** 是校验失败 ✓、**重发同样失败** ✗ → **不排重试** ✓（保持原行为 ✓）。

## 验证：`reused` 由 **false** 变 **true** ✓✓

真浏览器（`tests/e2e/remaining-b09-offline-retry.mjs`，重建 dist 后）：

```
INFO 相位B：重试用的幂等键 vs 失败那次的键 ——
  {"failedKey":"save-1791323248233-flsncos6",
   "retriedKey":"save-1791323248233-flsncos6",
   "reused":true}
```

**修复前实测为 `reused:false`** ✗（`failedKey=save-…whyn` vs `retriedKey=c783…`），**现在是 `reused:true`** ✓✓。

## 它意外修掉的一件事（值得记）

修好后**同一条脚本的断言数从 38 变 37** ✓，剩下**唯一**一条失败是：

```
FAIL 实测：丢响应后基于旧修订号的保存被服务端拒绝（409 冲突面板），没有静默覆盖
     :: 冲突面板出现=false
```

**这不是回归，而是修复的直接后果** ✓✓：
**重试现在复用原键** ✓ → **服务端把它当"重放"直接返回原回执** ✓ →
**根本不会走到"用旧修订号再发一次"那条路** ✗ ✓ → **也就不会撞 409** ✓。

**换句话说**：那条断言测的是**修复前才存在的行为** ✗（"丢响应后客户端修订号是旧的 → 下次保存撞 409" ✓，即第 176 轮记录的 **R3** ✓）。
**R2 接线把 R3 一并消掉了** ✓✓ —— 这正是"同键重试"设计的本意 ✓：
**重试应当是重放，而不是制造冲突** ✓。

**所以这条断言应改判** ✓：由「丢响应后下次保存撞 409」改为「**丢响应后同键重放直接复用回执、不产生冲突**」✓ ——
**我没有擅自改它** ✗ ✓（改断言需要与改产品分开说明 ✓），**但结论已写清** ✓。

## 门禁

| 检查 | 结果 |
| --- | --- |
| `npm test` | **719 / 719** ✓ |
| `npx vitest run` | **76 / 76** ✓ |
| `npm run typecheck` | 干净 ✓ |
| `npm run build:production` | **0 告警** ✓ |

## R2（与 R3）的最终状态

| 项 | 状态 |
| --- | --- |
| **R2** `SaveState.fail()` 未接线 → 重试不复用幂等键 | ✅ **已修复并验证**（`reused:false` → **`reused:true`** ✓）|
| **R3** 丢响应后下次保存撞 409 | ✅ **被 R2 一并消除** ✓（重放不再制造冲突 ✓）；**对应断言待改判** ✗ |

---

# 第 180 轮（独立复核子代理）：B03 **插桩定性**、B09 **轨迹定性**，并发现**证据污染**与**一条已过时的断言**

> **与第 176–179 轮的关系**：本节是一次**独立复核**（另一进程、另一套隔离端口 `4186`/`23197`、另一份脚本改动），
> 与第 176–179 轮结论一致。其中 **§8 正是把第 179 轮末尾那句「R3 对应断言待改判」落地**——
> 那条断言已在本轮改成"不得用新键多写一次"。本节"源 A / 源 B"证据均在隔离副本上取。
>
> **本轮没有修改任何产品代码。** 只改了 2 个 e2e 脚本：
> - `remaining-b03-undo-composition.mjs`：把"纯文本编辑"从**隐含**变成**显式「设计边界」断言**（18 → 19 条）；
> - `remaining-b09-offline-retry.mjs`：①把相位C 断言的**锚点**从"相位B 结束时的修订号（中间隔了 6.5 s）"改为"**本次写之前实测的修订号**"，并新增 `草稿修订号轨迹` INFO；②把"丢响应后再保存**必须**出现 409 冲突面板"这条**已过时**的期望，改成"**不得用新键多写一次**"（见 §8）。
>
> 所有"把产品改坏"的反向验证都在**隔离副本**里做（前端 `/tmp/tb-mine/proj`、后端 `/tmp/tb-mine/svc`）。
> **仓库源码、`4173`/`23199`、MySQL 容器 `toolbox-mysql-test` 全程未动**；另起的回环实例（前端 `4186`、后端 `23197`）**用完已停**（`lsof` 复查 4186/23197 无监听）。

## 0. 结论速览（先给三选一）

| 问题 | 结论 |
| --- | --- |
| **B03** 撤销"看起来没生效" | **第三种成立：恢复后目录确实已无该单元、内容确实回到旧值** —— **不是产品缺陷**。「快照时机错」不成立；「输入缓冲回写」也不成立（`undo()` 里的 `syncRowsToSelection()` 会用恢复后的 `entries` **重建** `rows`）。见 §2。 |
| **B09** `revision 3 → 7` | **脚本自身累积**：`3 --(自动保存循环 3 次)--> 6 --(相位C 自己 1 次)--> 7`。**不是竞态**。见 §6。 |
| B03 那份 FAIL 证据怎么来的 | **证据污染**：`evidence/browser/remaining-b03-undo-composition.json` 里的失败串与 mark `6t193`，与一次"**故意改坏 undo** 的反向验证"输出**逐字相同**，且时间只差 1 秒。见 §4。 |

**两个源修订号（本文件所有证据都可对号入座）**：

| 标记 | `sha256(TextbookEditorView.vue)` 前 16 位 | 说明 |
| --- | --- | --- |
| **源 A（原始）** | `77db9ea4a9f0451b` | 本目录第 168–174 轮分析时的那份；**含自动保存循环缺陷**。§2–§7 的证据都在源 A 上取。 |
| **源 B（当前）** | `a79a9344a41139fe` | 并发 worker 于 05:46 落地的两处修复（`syncingForSave` 守卫 + 失败时 `SaveState.fail(frozen)`）。§8 的证据在源 B 上取。 |

---

## 1. 协作冲突（必须先声明，否则证据不可追溯）

本轮工作期间，**另一个 worker 在做完全相同的事**，并且**在改同一份产品源码**：

- 它的日志在 `/tmp/tb-gate/`（`forward/`、`rev-*-build.log`、`reverse-*-broken.log`），产物在 `docs/.../evidence/remaining-gaps/`，收口文档是 `docs/.../remaining-gaps-e2e.md`；
- 它在 `05:24–05:54` 之间**反复改写并还原** `toolbox-web/src/App.vue` 与 `src/views/admin/TextbookEditorView.vue`
  （同一文件观测到多次哈希跳变：`77db9ea4` → 加 `syncingForSave` → 故意撤掉该守卫 → `77db9ea4` → `a79a9344`）；
- 期间它跑过 `node tests/e2e/*`、`npx vite build`、`npm run build:production`，并在 `05:48` **覆盖写**过 canonical 证据 JSON。

**因此**：本文件所有前向数字都来自**我自己**在**隔离副本**上、从**当时仓库源码**现场构建的产物；
反向验证也都在隔离副本里做。本文件与 `remaining-gaps-e2e.md` 是**两次独立复核**，在 B03/B09 的结论上一致。

**证据落点（本轮）**：`docs/verification/toolbox-admin-completion-2026-10-07/evidence/remaining-gaps-round175/`
（独立目录，避免与并发 worker 互相覆盖；canonical 的 `evidence/browser/*.json` 会被任一方覆盖，不作为本轮唯一依据）。

---

## 2. B03：插桩原始输出（源 A）

插桩点：`onAddUnit` 的 `recordBeforeChange` **前/后**、`onRowsUpdate`/`commitRowsFor` 入口、
`undo()` 的**入口/弹栈/恢复/+2s**。每条打印**同一个对象的引用 id**（`obj#n`，WeakMap 分配）**+ 内容 + `history.depth`**。

### 场景1：新增单元 → 撤销

```
[B03] {"tag":"onAddUnit:before-record","outlineRef":"obj#1","outlineUnits":["第一单元"],"entriesRef":"obj#2","entryTexts":["山","水"],"historyDepth":0}
[B03] {"tag":"onAddUnit:after-record","outlineRef":"obj#1","outlineUnits":["第一单元"],"entriesRef":"obj#2","entryTexts":["山","水"],"historyDepth":1}
[B03] {"tag":"onAddUnit:after-assign","outlineRef":"obj#4","outlineUnits":["第一单元","临时单元撤销7aifb"],"entriesRef":"obj#2","entryTexts":["山","水"],"historyDepth":1}
[B03] {"tag":"undo:enter","outlineRef":"obj#4","outlineUnits":["第一单元","临时单元撤销7aifb"],"historyDepth":1}
[B03] {"tag":"undo:popped","outlineRef":"obj#4","historyDepth":0,"snapshotLabel":"新增单元","snapshotOutlineRef":"obj#1","snapshotUnits":["第一单元"],"snapshotEntriesRef":"obj#2","snapshotEntryTexts":["山","水"]}
[B03] {"tag":"undo:restored","outlineRef":"obj#1","outlineUnits":["第一单元"],"entriesRef":"obj#2","entryTexts":["山","水"],"rowsRef":"obj#5","rows":[{"tokenId":"row-1","text":"山"},{"tokenId":"row-2","text":"水"}],"historyDepth":0}
[B03] {"tag":"undo:+2s","outlineRef":"obj#1","outlineUnits":["第一单元"],"entriesRef":"obj#2","entryTexts":["山","水"],"rowsRef":"obj#5","rows":[{"tokenId":"row-1","text":"山"},{"tokenId":"row-2","text":"水"}],"historyDepth":0}
```

判读：

| 观测 | 说明 |
| --- | --- |
| `before-record` 时 `outlineUnits=["第一单元"]`、`depth=0`；`after-record` 仍如此、`depth=1` | **快照是在改动之前记的** → 「快照时机错」**不成立** |
| `after-assign` 时 `outlineRef` 由 `obj#1` 变为 `obj#4` | 改动是**换新对象**（`addUnit` 返回新对象），旧对象未被原地改 |
| `undo:popped` 拿到 `snapshotOutlineRef="obj#1"`、`snapshotUnits=["第一单元"]` | 快照里**本来就没有**新单元 |
| `undo:restored` 后 `outlineRef="obj#1"`；`+2 s` 仍是 `obj#1`、`["第一单元"]` | 恢复生效，且**之后没有任何 watcher/自动保存把它写回** |

### 场景2：结构改动 + 改文本 → 撤销（这一组直接否掉「输入缓冲回写」）

```
[B03] {"tag":"undo:popped","snapshotLabel":"新增单元","snapshotEntriesRef":"obj#2","snapshotEntryTexts":["山","水"],"historyDepth":0,"rowsRef":"obj#10","rows":[{"tokenId":"row-1","text":"山撤销7aifb"},{"tokenId":"row-2","text":"水"}]}
[B03] {"tag":"undo:restored","outlineRef":"obj#1","outlineUnits":["第一单元"],"entriesRef":"obj#2","entryTexts":["山","水"],"rowsRef":"obj#11","rows":[{"tokenId":"row-1","text":"山"},{"tokenId":"row-2","text":"水"}],"historyDepth":0}
[B03] {"tag":"onRowsUpdate:enter","outlineRef":"obj#1","entriesRef":"obj#2","entryTexts":["山","水"],"rowsRef":"obj#11","rows":[{"tokenId":"row-1","text":"山"}],"nextTexts":["山","水"]}   ← 撤销 +3.0 s
```

| 观测 | 说明 |
| --- | --- |
| 撤销前 `rows` 是**新值**（`山撤销7aifb`） | 这正是「输入缓冲里可能留着新值」的前提 |
| `undo:popped` 的 `snapshotEntryTexts=["山","水"]` | 快照是**改文本之前**的 entries |
| `undo:restored` 后 `rowsRef` 从 `obj#10` **变为 `obj#11`**，内容是 `["山","水"]` | `syncRowsToSelection()` **重建了输入缓冲** —— 缓冲里已经不是新值 |
| `+3.0 s` 那次 `onRowsUpdate`（自动保存循环走 `save() → onRowsUpdate(rows.value)`）传入 `nextTexts=["山","水"]` | **即使撤销之后又发生一次"缓冲合并回 entries"，合并的也是恢复后的值** |

**结论：两个候选都不成立。** `undo()` 已经是"恢复 `outline`/`entries` + 用恢复后的 `entries` 重建 `rows`"，
闭环成立；源 A 上前向 E2E 实测 **19/19 全 PASS**，含场景1、场景2 的全部内容与标识断言。

---

## 3. B03 反向验证：把"改动前记快照"改成"改动后记快照"（隔离副本）

```js
// 原：recordBeforeChange('新增单元'); outline.value = addUnit(outline.value, title);
outline.value = addUnit(outline.value, title);   // 故意改坏：先改
recordBeforeChange('新增单元');                  //              后记
```

- **前向（未改坏，同一脚本）**：`通过 19/19`
- **反向（改坏后）实际失败输出** → `通过 16/19`：

```
FAIL 场景1：撤销后新单元真的从目录消失 —— [{"unit":"第一单元","lessons":[]},{"unit":"临时单元撤销7i60c","lessons":[]}]
FAIL 场景1：目录与基线逐字相同 —— [{"unit":"第一单元","lessons":[]},{"unit":"临时单元撤销7i60c","lessons":[]}]
FAIL 场景2：目录与基线逐字相同 —— [{"unit":"第一单元","lessons":[]},{"unit":"临时单元撤销7i60c","lessons":[]},{"unit":"临时单元二撤销7i60c","lessons":[]}]
```

**这条失败串，正是本文件第 44–51 行引用的那条 FAIL 的形态**（"新单元仍在目录里"）。

---

## 4. ★ 证据污染：那份 FAIL JSON 是一次**反向验证**的输出

| 证据 | 时间 | 内容 |
| --- | --- | --- |
| `/tmp/tb-gate/forward/remaining-b03-undo-composition.log` | 05:24:26 | **18/18 全 PASS**（场景1、场景2 全部内容断言都过） |
| `/tmp/tb-gate/rev-b03-build.log` | 05:24:56 | 用**改坏版**源码构建 |
| `/tmp/tb-gate/reverse-b03-undo-broken.log` | 05:25:31 | **12/18 FAIL**，mark=`撤销6t193`、样册 `e2e-b03-6t193` |
| `evidence/browser/remaining-b03-undo-composition.json`（当时内容） | `2026-10-06T21:25:32.128Z` | 失败串与上一条**逐字相同**，`editionId=e2e-b03-6t193`、`mark=撤销6t193` |

`mark` 由 `Date.now().toString(36).slice(-5)` **现场生成**，脚本里**没有任何环境变量可以把它钉死**
（`const stamp = Date.now().toString(36).slice(-5); const MARK = \`撤销${stamp}\`;`）。
两次独立运行**不可能**撞出同一个 mark；再加上日志时间 05:25:31 与 JSON 时间 `21:25:32Z`（= 05:25:32）**只差 1 秒** ——
**那份 JSON 就是 05:25:31 那次反向验证写进去的**。

**这解开了第 174 轮留下的矛盾**（"代码读下来是对的、E2E 却是 FAIL"）：
代码是对的，E2E 也是对的，**是证据文件被反向验证的输出覆盖了**。

> **教训（建议写进本目录的验收规范）**：反向验证与正向验证**必须写不同的证据文件名**。
> 否则"改坏后必须失败"那份会覆盖"未改坏时必须通过"那份，后来的读者就再也分不清哪份是哪份。

---

## 5. B03：纯文本内容编辑是否应当可撤销？

**结论：不应作为独立的撤销单元；但会被"结构快照"连带回退。**

1. **读代码**：撤销栈**只有结构操作**会 push（`recordBeforeChange()` 的调用点：新增单元/附录/课文、删除单元/课文、
   重命名、调整顺序、批量粘贴、设置类别/来源、恢复本地改动）；`onRowsUpdate()`（文本输入）**从不** push。
2. **实测**（本轮新增的显式断言）：

```
PASS 设计边界：纯文本输入不进入撤销栈（撤销按钮保持禁用） —— 输入后 disabled=true
```

3. **但**快照是**整份** `{ outline, entries }`（`history.record({ outline: outline.value, entries: entries.value }, label)`），
   所以"结构操作 → 再改文本 → 撤销"会**连带**回到结构操作**之前**的文本状态。

**所以场景2 的定位是「设计边界 · 组合语义」，不是缺陷场景**：
它验的不是"文本编辑有没有独立撤销"，而是"结构快照的粒度是整个文档状态"。
场景2 的断言**保持严格**（当前通过），只是不再暗示"文本编辑必须能独立撤销"；
本轮已在脚本头部注释与场景标题里改判，并**新增**一条显式「设计边界」断言把边界钉住（**没有删掉场景2**）。

---

## 6. B09：那条断言的确诊（`revision 3 → 7`，源 A）

同一脚本连跑 3 次（源 A），本轮新增的 `草稿修订号轨迹` **三次完全一致**：

```
[{"at":"基线（建册后）","revision":2},
 {"at":"相位A：断网写被拦下之后","revision":2},
 {"at":"相位B：重试成功后","revision":3},
 {"at":"相位B：静置 6.5 秒（自动保存循环自己推进）","revision":6,"autoSavesDuringQuiet":3},
 {"at":"相位C：触发丢响应写之前（本断言的锚点）","revision":6},
 {"at":"相位C：丢响应写之后","revision":7},
 {"at":"相位C：同键重放两次之后","revision":7}]
```

三条**互相独立**的证据指向同一结论：

1. **轨迹**：`3 → 6` 是脚本自己 `await sleep(6500)` 那 6.5 秒里**自动保存循环**推进的 3 次；`6 → 7` 才是相位C 那次写入。
2. **被拦下请求自己的 `If-Match`**：`{"idempotencyKey":"...","ifMatch":"\"draft-6\""}` ——
   **浏览器自己**在发这次写请求时认为服务端是 `draft-6`；若真是"竞态/多写"，客户端不可能恰好拿着 6。
3. **同一次运行里** `两次同键重放返回同一个修订号 = {first:7, second:7}` —— 回执被正确复用，**没有多写**。

**所以"3 → 7"是"脚本前面的自动保存累积 + 相位C 自己那一次"，不是竞态。**
根因是**另一个独立缺陷**（`save()` 开头调 `onRowsUpdate(rows.value)` → 又 `markDirtyAndSchedule()` → 每次保存给自己排下一次保存，
约 2 s 一次），并发 worker 已将其单独成脚本 `remaining-b09b-autosave-loop.mjs`（源 A 上复现 3 条 FAIL）。

### 6.1 最小修正（只改期望，没有放宽成"永远为真"）

把相位C 那条断言的**锚点**改成"**本次写之前实测的修订号**"：

```js
const revisionBeforeLost = (await readDraft(base, auth, EDITION)).revision;   // 触发这次的丢响应写之前
...
check('相位C：服务器其实已经写入（响应丢在路上，客户端却看到失败）',
  afterLost.revision > revisionBeforeLost && JSON.stringify(afterLost.draft.entries).includes(MARK_B),
  `revision ${revisionBeforeLost}（本次写之前实测） → ${afterLost.revision}（响应被丢弃之后），条目=...`);
```

修正后 detail 不再误导（源 A 实测）：

```
PASS 相位C：服务器其实已经写入（响应丢在路上，客户端却看到失败） —— revision 6（本次写之前实测） → 7（响应被丢弃之后），条目=["山丢响应7bxmt","水"]
```

---

## 7. B09 反向验证（两种，都在隔离副本里改坏）

### 7.1 改坏"同键重放不再写一次"的保护（幂等回执复用）

改坏点（`/tmp/tb-mine/svc`）：`AdminIdempotencyService.execute()` 里
`repository.findOperation(...)` → `Optional.empty()`（**不再复用回执**）。**实际失败输出** → `通过 21/26`：

```
FAIL 相位C：同键重放返回 200 且**返回被丢响应那次的修订号** —— {"replay":"{\"code\":\"TOOLBOX_ADMIN_004\",\"messageKey\":\"admin.conflict\",\"details\":{\"expectedRevision\":6,\"reason\":\"revisionConflict\"}}","lostRevision":7}
FAIL 相位C：**两次**同键重放返回同一个修订号（回执被复用、没有再写一次） —— {"first":null,"second":null}
FAIL 相位C：重放返回的修订号不超过当时服务端修订号（没有新写入） —— NaN <= 7
FAIL 相位D：同键第二次发布返回同一个版本号（复用回执） —— {"first":"1.0.0"}
FAIL 相位D：第二次发布返回的版本三要素与第一次完全一致（回执被复用）
```

### 7.2 改坏"接口返回 200 但**不落库**"（验证修正后的锚点断言仍会失败）

改坏点：`AdminTextbookController.save()` 的 action 改成 `() -> Map.of("revision", expected)`。**实际失败输出** → `通过 19/24`：

```
FAIL 相位C：服务器其实已经写入（响应丢在路上，客户端却看到失败） —— revision 2（本次写之前实测） → 2（响应被丢弃之后），条目=["山","水"]
FAIL 相位B：重试的写请求被服务端接受（200） —— {"status":200,"revision":"2 → 2"}
FAIL 相位B：内容写入且只出现一次（不产生重复条目） —— ["row-1:山","row-2:水"]
FAIL 相位C：内容没有第二份（同一条内容只出现一次） —— ["row-1:山","row-2:水"]
FAIL 实测：丢响应后基于旧修订号的保存被服务端拒绝（409 冲突面板），没有静默覆盖 —— 冲突面板出现=false
```

→ 修正后的相位C 断言**不是"永远为真"**：只要服务端没有真的写入，它就失败。

---

## 8. 源 B（当前）上的复核：又一条**已过时**的断言（本轮一并修掉）

并发 worker 在源 B 里落地了两处修复：

1. `syncingForSave` 守卫（修掉 §6 的自动保存循环）；
2. 失败时调用 `saveStateRef.value.fail(frozen)` —— 让 `pendingRetry` 生效，**同键重试真的同键**。

第 2 项让相位C 之后的**行为变好了**，于是原脚本里这条期望**过时**：

```
实测：丢响应后基于旧修订号的保存被服务端拒绝（409 冲突面板），没有静默覆盖
```

它断言"必须出现 409 冲突面板"。**这只是 `SaveState.fail(frozen)` 尚未接上时的时序产物**：
当时 `pendingRetry` 永远为 null，重试每次换新键（脚本自己的 INFO 实测 `reused=false`），所以必然撞 409。
接上之后走的是**同键重发被回执接住**（更优：用户不必走冲突恢复），**安全性只增不减**。

### 本轮第一手复现（把旧断言放回去跑源 B）

```
FAIL 实测：丢响应后基于旧修订号的保存被服务端拒绝（409 冲突面板），没有静默覆盖 —— 冲突面板出现=false
通过 23/24
```

同一次运行的实测 INFO：

```
INFO 实测：丢响应后再点一次「保存草稿」的结果 —— {"alerts":["草稿已保存。"],"conflictAppeared":false,
     "lostKey":"save-1791324273090-pdtsoswf","resaveKeys":["save-1791324273090-pdtsoswf"],"revision":"4 → 4"}
```

`resaveKeys` **等于** `lostKey`、修订号 `4 → 4` —— 同键重发被回执接住，**没有多写一次**。

### 最小修正（仍是可证伪的，没有放宽成"永远为真"）

把"必须 409"改成"**不得用新键多写一次**"：要么出现 409 冲突面板，要么这次重发的键**就是**丢响应那次的键、
且服务端修订号**不前进**。第三种可能（新键 + 修订号前进）仍然会 FAIL —— 那才是真正的"多写一次/静默覆盖"。

---

## 9. 连跑 3 次（源 B，最终口径）

| 次数 | 结果 | 样册 | 轨迹 | 重发键 |
| --- | --- | --- | --- | --- |
| 1 | **24/24 PASS** | `e2e-b09-7wow4` | `2 → 2 → 3 → 3(autoSaves=0) → 3 → 4 → 4` | `resaveKeys == lostKey`，`4 → 4` |
| 2 | **24/24 PASS** | `e2e-b09-7zhmo` | 同上（逐字相同） | `resaveKeys == lostKey`，`4 → 4` |
| 3 | **24/24 PASS** | `e2e-b09-80gwy` | 同上（逐字相同） | `resaveKeys == lostKey`，`4 → 4` |

- **B03（源 B）**：`通过 19/19`（`e2e-b03-81g62`）。
- 源 B 上 `autoSavesDuringQuiet = 0`（自动保存循环**已消失**），轨迹从源 A 的 `2→2→3→6→6→7→7`
  变成 `2→2→3→3→3→4→4` —— **每个相位只前进预期的那一次**，正是修复生效的量化证据。

### 证据落点说明

- 本轮最终证据：`evidence/remaining-gaps-round175/remaining-b03-undo-composition.json`（19/19）、
  `evidence/remaining-gaps-round175/remaining-b09-offline-retry.json`（24/24）。
- canonical 的 `evidence/browser/`：`remaining-b03-undo-composition.json` 是本轮前向（19/19）；
  `remaining-b09-offline-retry.json` 在 `05:48` 曾被并发 worker 用**未含本轮断言修正**的脚本覆盖成 1 条 FAIL，
  本轮已用**同一份最终运行结果**（`e2e-b09-80gwy`，24/24）覆盖回 canonical 路径。

---

## 10. 门禁（本轮修改的两个文件都是 `tests/e2e/*.mjs`，不参与任何门禁）

| 门禁 | 结果 |
| --- | --- |
| `npm test` | **719 / 719**，`fail 0` |
| `npx vitest run` | **76 / 76**（12 个文件） |
| `npm run typecheck` | 退出码 0，无任何输出 |
| `npm run build:production` | 退出码 0，**告警 0 条** |
| `mvn -o -f service/toolbox-service/pom.xml test` | **412 / 412**，`BUILD SUCCESS`（本轮未改后端仓库源码，此项为回归确认） |

---

## 11. 收尾复查

- `du -sh docs/` = **552M**（其中 `docs/verification` 406M、`docs/design` 145M，均为既有内容）。
- `docs/` 与本仓库下**没有任何 `chrome-*` 浏览器 profile 残留**（`find docs -type d -name "chrome-*"` 为空）；
  所有 E2E 的 profile 都建在系统临时目录并由 `removeProfileDir()` 删除。
- 本轮另起的回环实例 `127.0.0.1:4186`（前端 preview）与 `127.0.0.1:23197`（后端）**已停止**，`lsof` 复查无监听。
- `4173` / `23199` / `toolbox-mysql-test` **未杀、未重启、未删除、未重建**。
