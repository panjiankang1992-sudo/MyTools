# 剩余缺口端到端收口（B03 / B09 / A09）

- 日期：2026-10-07
- 范围：`toolbox-web`（前端）+ `service/toolbox-service`（后端，**只改测试源集**）+ 隔离 MySQL 测试容器 `toolbox-mysql-test`
- 目的：把状态矩阵里**三条仍然"部分"、且本环境完全做得出来**的项，用**真实浏览器 + 真实后端 + 真实数据库**的可重跑证据收掉，并对每一条做**反向验证**（把产品改坏 → 断言必须失败）
- 生产边界：全程只访问回环（`127.0.0.1:4175` / `127.0.0.1:23198` / `127.0.0.1:13316`），**未对 `toolbox.yuyutian.top` 发起任何请求**，未创建线上账号、未发布任何线上教材

---

## 0. 结论一览

| 项 | 本轮结论 | 前向证据 | 反向验证（改坏后必须失败） |
| --- | --- | --- | --- |
| **B03** 撤销与 ID 稳定 | ✅ 真实页面组合已验收（18/18） | `01-b03-forward.log` | ✅ 改坏 `undo()` → **6 条 FAIL**（`02-b03-reverse-undo-broken.log`） |
| **B09** 断网重试与幂等 | ✅ 真实断网 + 丢响应 + 同键重放已验收（26/26） | `03-b09-forward.log` | ✅ 改成"静默当成功" → **4 条 FAIL**（`04-b09-reverse-silent-success.log`） |
| **A09** 退出／换账号（第二账号） | ✅ 真实浏览器换账号已验收（21/21） | `05-a09-forward.log` | ✅ 改坏"退出时离开管理页" → **3 条 FAIL**（`06-a09-reverse-logout-broken.log`） |
| ⚠️ **新发现缺陷 R1**：自动保存每 ~2 秒重复写一次 | ❌ **未修复**，已复现并单独成脚本 | `07-autosave-loop-repro.log`（3 条 FAIL，**当前代码上应当失败**） | — |

三条缺口**都做到了**，没有一条降级为"需要外部条件"：第二账号的缺口通过**在测试源集里给本地假身份加一个可选第二账号**解决（见 §4），没有在生产建账号、也没有伪造账号。

同时本轮验收**额外发现 3 个真实问题**（R1/R2/R3，见 §5），其中 R1 是独立缺陷，**没有修**（超出本次授权范围），但提供了可复跑的复现脚本与量化数据。

---

## 1. 环境（隔离、可复跑）

父会话常驻的 `23199`（后端）与 `4173`（前端 preview）**全程未杀、未重启**。本轮另起一套隔离栈：

| 组件 | 地址 | 说明 |
| --- | --- | --- |
| 隔离后端 | `127.0.0.1:23198` | 同一份代码 + 同一个隔离库 `toolbox_test`；`profile=local,local-admin`；`TOOLBOX_PUBLIC_ORIGIN=http://127.0.0.1:4175` |
| 隔离前端 | `127.0.0.1:4175` | **从当前源码现场构建**（不是复用 4173 的旧 dist）：`npx vite build --outDir /tmp/tb-e2e/dist-base` + `vite preview --outDir ... --port 4175`，代理指向 `23198` |
| MySQL | `127.0.0.1:13316` | 用户提供的 `toolbox-mysql-test`，**未创建/未删除/未重建** |
| 浏览器 | 系统临时 profile | `tests/e2e/cdp.mjs` 的 `createProfileDir()`；跑完删净（`docs/` 下 `chrome-*` 目录数 = 0） |

为什么**自己构建 dist**：反向验证要求"被测产物 == 当前源码"，而 4173 的 dist 是更早构建的。构建只写到 `/tmp/tb-e2e/dist-base`（不进仓库）。

复跑命令（三条主脚本 + 缺陷复现）：

```bash
# 需要：隔离后端 23198 在跑；隔离 preview 4175 指向它
cd /Users/pankang/mycode/MyTools/toolbox-web
E2E_TARGET=http://127.0.0.1:4175 node tests/e2e/remaining-b03-undo-composition.mjs
E2E_TARGET=http://127.0.0.1:4175 node tests/e2e/remaining-b09-offline-retry.mjs
E2E_TARGET=http://127.0.0.1:4175 node tests/e2e/remaining-a09-account-switch.mjs   # 需要第二账号，见 §4
E2E_TARGET=http://127.0.0.1:4175 node tests/e2e/remaining-b09b-autosave-loop.mjs   # 缺陷复现：当前代码上必然失败
```

脚本均满足本次的手法与门禁要求：`requireLoopbackTarget()` 强制回环；`el.click()` 合成点击（不用真实鼠标）；原生 setter + `input` 事件驱动 Vue；等待条件而非固定 sleep；每个脚本自带看门狗超时（默认 240 s，到点退出码 3）；profile 放系统临时目录。

---

## 2. B03：撤销在真实页面里的组合

缺口原文：`{ id: 'B03', note: 'ID 不按位置生成有反向验证；撤销在真实页面的组合未测' }`

### 2.1 先读代码确认"手势"（本项判据的前提）

- `src/views/admin/TextbookEditorView.vue`：撤销**只有按钮**一条入口（`:disabled="history.depth === 0" @click="undo"`，文本「撤销」）；
- `src/main.ts` / `src/App.vue`：**没有注册任何 `Cmd+Z`／`Ctrl+Z` 快捷键**（`grep -n "undo\|metaKey\|ctrlKey" src/main.ts` 无命中）；
- 撤销栈是快照式（`src/lib/admin-textbook-history.ts`），`recordBeforeChange()` 只在**结构操作**前调用（新增单元/附录/课文、删除单元/课文、重命名、调整顺序、批量粘贴、设置类别/来源、恢复本地改动）；**纯内容输入不记录快照**。

因此脚本按**真实手势（按钮）**验收，并把"没有快捷键"作为**实测记录**（不计成败）：

```
INFO 实测：Cmd+Z（keyDown/keyUp + Meta） —— 聚焦=true 输入框值 "山撤销760t9撤销760t9" → "山撤销760t9撤销760t9"；Vue 状态(.metadata-text)="山撤销760t9撤销760t9"；撤销按钮 disabled=true
INFO 实测：撤销入口 —— 本页没有注册 Cmd+Z 快捷键（main.ts/App.vue 无 undo/ctrlKey/metaKey 命中），唯一入口是「撤销」按钮
```

### 2.2 三个场景与断言（18/18 通过）

样册由脚本自己用管理 API 建（`e2e-b03-<stamp>`，2 条内容：山／水），编辑器在真实浏览器里打开：

1. **结构操作后撤销**：合成输入「新单元名称」→ 合成 click「新增单元」→ 单元进目录、撤销按钮由禁用变可用 → 合成 click「撤销」→ 新单元消失、目录/条目/`rowId` 与基线**逐字相同**、页面提示「已撤销：新增单元」、撤销栈回到空；
2. **组合场景（本项的正题）**：先做结构操作（记快照）→ 再合成输入把第 1 行改成 `山<标记>`（证明自动保存真的把它写进了服务端）→ 点「撤销」→ **第 1 行回到原值「山」**（DOM 实测）、`rowId` 与顺序逐个未变、目录回到基线；
3. **撤销之后再输入一次**：`rowId` 仍是同一批 `["row-1","row-2"]`、顺序不变（原缺陷 F04 的症状是"同一行两次输入之间换了身份"，这里直接盯这一点）。

关键原始输出：

```
PASS 场景2：自动保存把新内容真的写到服务端 —— {"revision":3,"rowId":"row-1","text":"山撤销760t9"}
PASS 场景2：内容真的回到原值（页面 DOM） —— 实际="山"
PASS 场景2：rowId 与顺序逐个未变 —— [{"rowId":"row-1","text":"山"},{"rowId":"row-2","text":"水"}]
PASS 场景3：撤销后再输入，rowId 仍是同一批、顺序不变 —— ["row-1","row-2"]
通过 18/18
```

**实测记录（本轮新观察到，不是本项的判据）**：撤销直接改 `entries`／`outline`，**不走** `onRowsUpdate`／`markDirtyAndSchedule`，因此撤销结果不会立刻落库——服务端在撤销后仍停在被撤销前的那一版：

```
INFO 实测：撤销后服务端草稿第 1 行 —— ["row-1:山撤销760t9","row-2:水"]
INFO 实测：撤销后服务端 revision —— 3
```

即"页面已回到原值、服务端还是新值"，直到下一次内容改动才会把撤销结果一起写上去。这条与 R1（§5）是相邻问题，但**不在原缺口范围内**，故只记录。

### 2.3 反向验证：把撤销路径改坏 → 断言必须失败

改动（临时、已还原）：`undo()` 里不再还原快照，只给提示。

```diff
-  outline.value = snapshot.state.outline;
-  entries.value = snapshot.state.entries;
-  syncRowsToCompletion…
+  // 【反向验证·临时改动】故意把撤销路径改坏：只提示，不还原任何状态。
+  void snapshot.state;
+  notice.value = `已撤销：${snapshot.label}。`;
```

**真实失败输出**（`02-b03-reverse-undo-broken.log`，退出码 1）：

```
FAIL 场景1：撤销后新单元真的从目录消失 —— [{"unit":"第一单元","lessons":[]},{"unit":"临时单元撤销6t193","lessons":[]}]
FAIL 场景1：目录与基线逐字相同 —— [{"unit":"第一单元","lessons":[]},{"unit":"临时单元撤销6t193","lessons":[]}]
FAIL 场景2：撤销后第 1 行真的回到原值 —— 实际="山撤销6t193"
FAIL 场景2：内容真的回到原值（页面 DOM） —— 实际="山撤销6t193"
FAIL 场景2：rowId 与顺序逐个未变 —— [{"rowId":"row-1","text":"山撤销6t193"},{"rowId":"row-2","text":"水"}]
FAIL 场景2：目录与基线逐字相同 —— [{"unit":"第一单元","lessons":[]},{"unit":"临时单元撤销6t193","lessons":[]},{"unit":"临时单元二撤销6t193","lessons":[]}]
通过 12/18
```

还原后重跑：**18/18 通过**（`01-b03-forward.log`）。

---

## 3. B09：真实断网／丢响应的端到端 + 幂等

缺口原文：`{ id: 'B09', note: '同键重试有测试；真实断网/丢响应端到端未做' }`

### 3.1 选了哪条写路径、为什么

**保存草稿 `PUT /admin/v1/textbooks/{editionId}/draft`**。理由（先读代码）：

- 它是编辑器**每次内容变化约 2 秒后**自动触发的唯一写路径（`markDirtyAndSchedule()` → `save()` → `saveDraft`），断网最可能真实命中的就是它；
- 它同时携带 `If-Match`（CAS）与 `Idempotency-Key`（幂等回执）——正是本缺口要验的两套机制（`AdminTextbookController.save`，`AdminIdempotencyService`）；
- 发布路径更重（会重建目录快照），因此只在末相用**同键重放**验证"版本不重复"。

### 3.2 四个相位（26/26 通过）

| 相位 | 手法 | 断言（全部 PASS） |
| --- | --- | --- |
| A 真断网 | CDP `Fetch` **请求阶段** `failRequest(InternetDisconnected)`（请求根本没到服务器） | 界面如实显示「操作失败」；**没有**谎报成功；保存时间戳（`savedAt`）不前进；服务端草稿内容与修订号**一个字节都没变** |
| B 恢复网络重试 | 关掉拦截，合成 click「保存草稿」 | 该写请求被服务端接受（**200**，按 requestId 与请求正文配对）；内容只出现一次、条目数与 `rowId` 不变；教材总数不变（**不产生重复草稿**） |
| C 丢响应 | CDP `Fetch` **响应阶段** `failRequest`（服务器已写、响应丢在路上） | 服务端确实写入了、客户端却看到失败；**同键 + 同正文 + 同 `If-Match` 重放两次，两次返回同一个修订号**（回执被复用、没有第二次写入）；内容没有第二份；随后拿到旧修订号的保存被服务端**409 拒绝并进入冲突面板**（没有静默覆盖），并走真实恢复路径「读取最新 → 确认读取最新」 |
| D 发布幂等 | 同键发布两次（真实端点） | 第一次 200；第二次返回**同一个** `contentVersion`／`versionId`／`contentSha256`；版本记录**只有一条** |

关键原始输出：

```
PASS 相位A：界面如实显示失败 —— ["操作失败：网络连接失败。当前编辑内容仍保留在本页，请检查网络后重试。 当前编辑内容仍保留在本页。"]
PASS 相位A：服务端草稿**一个字节都没变** —— revision 2 → 2，条目=["山","水"]
PASS 相位B：重试的写请求被服务端接受（200） —— {"status":200,"revision":"2 → 3"}
PASS 相位B：教材总数不变（不产生重复草稿） —— total 35 → 35
INFO 相位C：同键重放第一次的响应 —— {"status":200,"body":"{\"code\":\"OK\",\"data\":{\"revision\":7},...}"}
INFO 相位C：同键重放第二次的响应 —— {"status":200,"body":"{\"code\":\"OK\",\"data\":{\"revision\":7},...}"}
PASS 相位C：**两次**同键重放返回同一个修订号（回执被复用、没有再写一次） —— {"first":7,"second":7}
PASS 相位D：版本记录只有一条（不产生重复版本） —— [{"contentVersion":"1.0.0","versionId":"e2e-b09-…@1.0.0","contentSha256":"773bb097…","changeNote":"B09 幂等验收 …"}]
通过 26/26
```

> 判据为什么是"两次重放返回同一修订号"而不是"修订号恰好 +1"：见 §5 R1——当前代码存在**每 ~2 秒重复自动保存**的缺陷，任何"恰好 +1"的断言都只是时序巧合。上面这条判据与后台有没有别的写**无关**：回执若没被复用，第二次重放必然真写一次、修订号必然前进。

### 3.3 反向验证：把"失败时显示错误"改坏成"静默当成成功" → 断言必须失败

改动（临时、已还原，只改 `save()` 的非 409/422 分支）：

```diff
     } else {
-      errorText.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
+      // 【反向验证·临时改动】把"失败时如实显示错误"改坏成"静默当成成功"。
+      void cause;
+      message.value = '草稿已保存。';
+      savedAt.value = new Date().toLocaleTimeString();
     }
```

**真实失败输出**（`04-b09-reverse-silent-success.log`，退出码 1）：

```
FAIL 相位A：界面如实显示失败 —— ["草稿已保存。"]
FAIL 相位A：**没有**谎报成功（页面没有「已保存」提示） —— ["草稿已保存。"]
FAIL 相位A：保存时间戳没有前进（savedAt 不变） —— "修订 2" → "修订 2 · 保存于 05:34:03"
PASS 相位A：服务端草稿**一个字节都没变**（内容与修订号都未动） —— revision 2 → 2，条目=["山","水"]
...
FAIL 相位C：界面看到的是失败（不谎报成功） —— ["草稿已保存。"]
通过 22/26
```

还原后重跑：**26/26 通过**（`03-b09-forward.log`）。

---

## 4. A09：第二账号的真实浏览器端到端

缺口原文：`{ id: 'A09', note: '…仍缺真实浏览器端到端与第二账号' }`

### 4.1 第二账号怎么来的（**只在测试源集**，没有在生产建账号）

先读代码：本地假身份由 `service/toolbox-service/src/test/java/.../config/LocalAdminIdentityTestConfig.java`（`@Profile("local-admin")` + `@ConditionalOnProperty(toolbox.admin.local-identity-stub=true)` **双重闸门**）提供，配置在 `src/test/resources/application-local-admin.yml`；原实现从 `local-stub-account/password/user-id` 读**一个**账号，`validate()` 只看令牌前缀，于是**所有令牌都解析成同一个管理员**——"换账号"无从验收。

处理：在**同一个测试专用文件**里加上**可选的第二账号** `local-stub-account-2/password-2/user-id-2`（默认留空 = 不启用，行为与扩展前完全一致），并用"令牌 → 身份"的内存表让**每个令牌解析回自己的账号**；`validate()` 对"前缀正确但不在本进程内存表里"的令牌仍按主账号处理（保持不变，进程重启后旧 Cookie 继续可用）。

边界：文件仍在 `src/test/java`（不进 `target/classes`）、仍由 profile + 配置键双重闸门控制、默认关闭；没有新增或修改任何 `src/main` 代码。

```bash
# 启动隔离后端时开启第二账号（口令只由环境变量注入，不落仓库）
TOOLBOX_ADMIN_STUB_ACCOUNT_2=local-admin-2 \
TOOLBOX_ADMIN_STUB_PASSWORD_2=local-admin-2 \
TOOLBOX_PUBLIC_ORIGIN=http://127.0.0.1:4175 TOOLBOX_PORT=23198 \
mvn -o spring-boot:run -Dspring-boot.run.profiles=local,local-admin \
  -Dspring-boot.run.additional-classpath-elements="$PWD/target/test-classes"
```

实测两个账号确实是两个身份：

```
账号 A=local-admin(local-admin-1001) 账号 B=local-admin-2(local-admin-1002)
```

### 4.2 场景与断言（21/21 通过，全部走真实界面）

1. 匿名直达编辑页 → 被路由守卫挡回首页（带 `redirect`）；
2. 真实登录弹窗登录 **A** → 自动回到编辑页 → 用 `Fetch` 把草稿保存请求拦掉（模拟"断网未保存"）→ 合成输入一段 `A私稿<标记>`：断言它**在编辑器里**且**没有落库**；
3. 右上角菜单**真实退出**（`.admin-name` 上派发 `mouseenter`/`click` → 点「退出管理员模式」）→ 断言：**离开管理路由**、编辑器实例**已卸载**、A 的私稿在 DOM 与所有输入框里都找不到、右上角不再显示账号；
4. 真实登录弹窗登录 **B**（`local-admin-2`）→ 用 `/admin/v1/session` 断言浏览器会话**确实是第二个账号**（`userId=local-admin-1002`）；
5. B 进入同一册编辑页 → 断言：**观察到新的 `GET /draft`**（说明是重新挂载并重新加载，而不是复用缓存实例）、内容逐条等于服务端草稿、页面修订号等于服务端修订号、**看不到 A 的私稿**。

关键原始输出：

```
PASS A09-①：退出后**离开了管理路由**（没有停在编辑页） —— {"editors":0,"hash":"#/","path":"/","loginEntry":true}
PASS A09-②：退出后 A 的私稿在 DOM 与所有输入框里都找不到 —— {"inBodyText":false,"inInputs":false,"editors":0,...}
PASS B：浏览器会话确实是第二个账号（不是 A） —— {"username":"local-admin-2","userId":"local-admin-1002"}
PASS B09/A09-③：B 进入编辑页时**重新挂载并重新加载**（观察到新的 GET /draft） —— draftLoads 1 → 2
PASS A09-④：B 看不到 A 的私稿（DOM 与输入框都没有） —— {"inBodyText":false,"inInputs":false,"editors":1,"adminName":"管理员 · local-admin-2",...}
PASS A09-⑤：B 看到的内容逐条等于服务端草稿 —— [{"rowId":"row-1","text":"山"},{"rowId":"row-2","text":"水"}] vs ["row-1:山","row-2:水"]
PASS A09-⑥：页面修订号就是服务端修订号（不是缓存实例的旧状态） —— "修订 2" vs 服务端 2
通过 21/21
```

### 4.3 反向验证：把"退出时清理状态"改坏 → 断言必须失败

改动（临时、已还原）：`App.vue` 的退出分支**不再离开管理页**（`if (route.path.startsWith('/admin')) await router.push('/')` 删掉）。

**真实失败输出**（`06-a09-reverse-logout-broken.log`，退出码 1）：

```
FAIL 退出后编辑器实例已卸载（管理页不在 KeepAlive 里） —— {"editors":1,"hash":"#/admin/textbooks/e2e-a09-72ivp/edit","path":"/admin/textbooks/e2e-a09-72ivp/edit","loginEntry":true}
FAIL A09-①：退出后**离开了管理路由**（没有停在编辑页） —— {"editors":1,"path":"/admin/textbooks/e2e-a09-72ivp/edit",...}
FAIL 脚本执行完成 —— 等待「B 的编辑页加载出条目」超时（最后一次取值 false）
```

**如实说明这条反向验证的边界**：这次改动下，`A09-②`（A 的私稿找不到）**仍然通过**——因为 `TextbookEditorView` 里还有一条独立防线（`watch(canWrite)`：鉴权失效即清空编辑缓冲），它把缓冲清了。也就是说"离开管理页"与"清空编辑缓冲"是**两道互相独立**的防线，本轮浏览器脚本同时断言了两者；要复现"下一个账号看到上一个人的内容"需要同时改坏两条（其中一条的组合已由前一轮 App 层测试覆盖）。这不是判据失效，而是防线确实有冗余。

还原后重跑：**21/21 通过**（`05-a09-forward.log`）。

---

## 5. 本轮额外发现的真实问题（**未修复**，供决策）

### R1（P1）自动保存**每约 2 秒重复写一次**，永不停止

- **机理**（读代码 + 实测）：`TextbookEditorView.save()` 一开始调用 `onRowsUpdate(rows.value)`，而 `onRowsUpdate()` 无条件调用 `markDirtyAndSchedule()`——**每保存一次就把"脏"标回来并再排一次 2 秒后的保存**：

  ```text
  save() → onRowsUpdate() → markDirtyAndSchedule() → 2s 后 save() → …
  ```

- **实测**（`07-autosave-loop-repro.log`，脚本 `tests/e2e/remaining-b09b-autosave-loop.mjs`）：

  ```
  PASS 对照组①：不输入时不该有任何草稿写请求 —— 0 次
  INFO 主组实测：一次输入后 12 秒内的写请求间隔(ms) —— [2011,2008,2008,2004]
  INFO 主组实测：修订号轨迹 —— 2 → 7
  FAIL 主组：一次内容改动只应触发一次保存（允许 1 次，不该每 2 秒重复） —— 实际 5 次写请求，修订号 2 → 7
  FAIL 主组：静置期间服务端修订号不该继续前进 —— 多出 5 次
  FAIL 对照组②：没有内容改动时，保存一次之后不该继续重复写 —— 实际 4 次写请求，修订号 7 → 11
  ```

  即：**不改任何内容、只点一次「保存草稿」，也会启动无限写循环**（写请求间隔稳定 2000±10 ms）。B09 主脚本里也顺带量到："重试成功后静置 6.5 秒，修订号 3 → 6"。
- **影响**：整份草稿每 2 秒写一次库（写放大）、修订号／`ETag` 无限增长（稀释"修订号"语义）、"有新改动待保存"永远不安定；并且它会让**任何"修订号恰好 +1"的断言变成时序巧合**（本轮 B09 的判据已相应改成与循环无关的形式）。
- **同源线索**：全量 `vitest` 在一次**并发高负载**运行中出现过 1 条失败——`tests/component/admin-editor-preview-publish.test.ts:378` `expect(saveDraft).toHaveBeenCalledTimes(1)` 实际 2 次（`09-gate-vitest-flake.log`）；随后 3 次全量运行与 3 次单文件运行均全绿（`10-gate-vitest-rerun-pass.log`）。形态与 R1 同源（多出来的一次保存），但**不能**据此宣称该 flaky 由 R1 引起。
- **为什么没修**：改 `save()`／`onRowsUpdate()` 的脏标记时机属于产品行为变更，超出本次"补齐验收证据"的授权；按边界要求只记录 + 给出可复跑复现。

### R2（P2）网络失败后没有接线 `SaveState.fail()`：重试**不复用**幂等键，且状态机卡住

- 实测：相位 A 失败的幂等键 `save-…-9b40whyn`，相位 B 重试用的键 `c783029b-…`，`reused: false`。
- 代码：`SaveState` 提供了 `fail()`（"重试复用同一个键与同一份 payload"），但 `TextbookEditorView.save()` 的 `catch` **从不调用它**——于是 `inFlight` 永不清空，后续 `begin()` 恒返回 `null`，`savedSequence` 也不再前进（`dirty` 永远为真）。
- 影响：产品**设计好的"同键重试"没有接到页面上**；真正被这次断网"卡住"的语义（待重试操作）只存在于单元测试里。§3.2 相位 C 用"同键重放两次"证明的是**服务端回执机制正确**，不是"页面会复用键"。

### R3（P2）丢响应之后的下一次保存会撞 409（行为正确，但用户路径值得注意）

- 实测：丢响应后客户端手里的修订号仍是旧的，再点一次「保存草稿」得到
  `操作失败：这份内容已被其他人修改过。请在下方冲突面板查看差异或读取最新，以免覆盖对方的改动。` 并进入冲突面板（`04`/`03` 两份日志都有）。
- 这是**正确的 CAS 行为**（不清静覆盖），脚本随后走真实恢复路径「读取最新 → 确认读取最新」并继续通过。记录它是为了说明：R2 不修的话，"丢响应"这类**服务端已成功**的失败，用户会额外撞一次冲突。

---

## 6. 门禁结果（全部在**还原后的源码**上跑）

| 门禁 | 基线 | 本轮实测 | 结果 |
| --- | --- | --- | --- |
| `npm test` | 719 | **719 passed / 0 failed** | ✅ `08-gate-npm-test.log` |
| `npx vitest run` | 76 | 一次并发高负载下 75+1 failed；随后**3 次全量 76/76**、单文件 3 次 10/10 | ⚠️ 判为并发负载下的固有 flaky，见 R1 线索（`09`/`10`） |
| `npm run typecheck` | 干净 | 退出码 0、无输出 | ✅ `11-gate-typecheck.log` |
| `npm run build:production` | 0 告警 | 退出码 0、**无任何 warning/error**、`✓ built in 1.29s` | ✅ `12-gate-build-production.log` |
| `mvn -o test`（后端有改动，故必跑） | 412 | **412 passed / 0 failures / 0 errors** | ✅ `13-gate-mvn-test.log` |

补充：唯一"改动"的仓库文件是**测试源集**的 `LocalAdminIdentityTestConfig.java` 与 `application-local-admin.yml`（A09 第二账号）；前端 `toolbox-web/src` 在本轮结束时与开工前**逐字节一致**（`sha256` 比对：`TextbookEditorView.vue 77db9ea4…`、`App.vue e5238e91…`），三处反向验证断点均已还原（`grep -rn "反向验证·临时改动" toolbox-web/src` = 0）。

## 7. 复跑与清理

- 隔离后端（23198）与隔离 preview（4175）**验收后已停**；父会话的 `23199` / `4173` **全程未动**（已用 `lsof` 复核）。
- 临时构建产物在 `/tmp/tb-e2e/dist-base`（仓库外）；浏览器 profile 全部在系统临时目录且已删除。
- 观察（未处理）：系统里存在**别的会话遗留**的 headless Chrome（`--user-data-dir=/tmp/tb-a04d`、`/tmp/tb-chain`）。按 `cdp.mjs` 的约定只清理"本次 profile"，因此**没有**去杀它们，避免误伤其它会话。
- 本轮在隔离测试库 `toolbox_test` 里**真实写入**了验收样册：`e2e-b03-*`/`e2e-b09-*`/`e2e-a09-*`/`e2e-loop-*`/`e2e-probe-*`，其中 `e2e-b09-*` 各发布了一个 `1.0.0` 版本（相位 D 需要），因此隔离库的 `catalogVersion` 序号会因此前进（`catalog-…`）。**没有**触碰生产、也没有新建任何线上账号。

## 8. `docs/` 体积复查（要求项）

```
$ du -sk docs/            → 552.309 MB   （开工前同为 552 MB；本轮新增证据 0.46 MB）
$ find docs -type d -name 'chrome-*' | wc -l   → 0
$ find docs -type d -name 'tb-*'     | wc -l   → 0
```

本轮新增/改动的证据文件（**未修改 10-05 / 10-06 或本目录其它既有文件**）：

- 本文件：`remaining-gaps-e2e.md`
- 原始日志：`evidence/remaining-gaps/01…13-*.log`
- 脚本产出的结构化结果：`evidence/browser/remaining-{b03-undo-composition,b09-offline-retry,b09b-autosave-loop,a09-account-switch}.json`
- 新脚本：`toolbox-web/tests/e2e/remaining-lib.mjs`、`remaining-b03-undo-composition.mjs`、`remaining-b09-offline-retry.mjs`、`remaining-b09b-autosave-loop.mjs`、`remaining-a09-account-switch.mjs`

## 9. 状态矩阵建议（**矩阵文件本身未改**，交由上层更新）

- `B03` → `通过`：note 建议写「真实页面撤销组合已验收（18/18）：结构操作与'结构+合成输入'组合撤销后内容回到原值、`rowId` 与顺序逐字不变；**反向验证**：改坏 `undo()` 还原逻辑 → 6 条断言失败。另实测该页**没有** Cmd+Z 快捷键（唯一入口是「撤销」按钮），且撤销结果不安排保存（下次内容改动才落库）。」
- `B09` → `通过`：note 建议写「真实断网（`Fetch` 请求阶段 `failRequest`）→ 界面如实报错、不谎报成功、服务端零变化；恢复后重试被 200 接受、内容只出现一次、教材总数不变；丢响应（响应阶段 `failRequest`）后**同键重放两次返回同一修订号**、版本记录只有一条。**反向验证**：改成'静默当成功' → 4 条断言失败。⚠️ 同时新发现 R1（自动保存每 ~2 s 重复写）另案处理。」
- `A09` → `通过`：note 建议写「真实浏览器 + **第二本地假账号**（测试源集新增可选 `local-stub-account-2`，双重闸门不变）：A 编辑未保存内容 → 真实退出（编辑器卸载、离开管理路由、DOM/输入框均无 A 内容）→ B 登录（会话确为另一账号）→ 重新挂载并重新加载（新 `GET /draft`）、逐条等于服务端草稿。**反向验证**：删掉'退出后离开管理页' → 3 条断言失败（另一道 `watch(canWrite)` 防线仍拦住内容泄漏，已在文中说明）。」
