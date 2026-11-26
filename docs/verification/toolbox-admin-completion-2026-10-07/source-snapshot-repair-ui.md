# 来源快照补录的管理端界面（设计第 297 行：来源元数据随不可变版本保存）

本轮目标：为**已存在的**后端接口
`POST /admin/v1/curriculum/versions/{editionId}/{contentVersion}/repair-source-snapshot?basis=`
补一个最小可用的管理端入口，并用可重跑的证据证明它不会误伤。

- 日期：2026-10-07
- 范围：`toolbox-web/`（前端）＋组件测试＋浏览器只读验收；**未改后端、未加依赖**
- 目标环境：本地回环（preview `http://127.0.0.1:4173`、后端 `http://127.0.0.1:23199`、
  隔离 MySQL 容器 `toolbox-mysql-test` 仅回环 `13316`）。未访问生产、未部署、未在生产建账号／发布教材／补录快照。

## 0. 结论摘要

| 项 | 结果 |
| --- | --- |
| 补录入口只出现在 `hasSourceSnapshot === false` 的版本 | 实现 + 组件测试 + 反向验证 M7（改坏后 3 条失败） |
| 依据必填且**闸门在会发请求的函数里**（绕过 `disabled` 直接触发也发不出去） | 实现 + 组件测试 + 反向验证 M1（实际失败输出见 §5） |
| 二次确认（写操作） | 实现 + 组件测试 + 反向验证 M2 |
| 提交后展示 `applied` / `overwroteOriginal` / `reason` 并**刷新历史** | 实现 + 组件测试 + 反向验证 M4 |
| `overwroteOriginal: true` 显示为**异常**（不提供覆盖路径） | 实现 + 组件测试 + 反向验证 M6 |
| 服务层报错显示原文（不静默） | 实现 + 组件测试 + 反向验证 M5 |
| 只补空（有快照的版本即使被程序化调用也拒绝） | 实现 + 组件测试 + 反向验证 M3 |
| 组件测试 | **9/9 通过** |
| 反向验证（7 个突变） | **7/7 全部如预期失败**，实际输出见 §5 |
| 真实浏览器只读验收 | **12/12 通过**（真实后端 + 真实 preview 构建），见 §6 |
| 门禁 | `npm test` **715/715**、`npx vitest run` **70/70**（基线 61＋新增 9）、`npm run typecheck` 干净、`npm run build:production` **0 告警**，见 §7 |
| 明确不做 | 下架／回滚／恢复旧版为新版；**覆盖已有快照**的路径 |

## 1. 实现位置

| 文件 | 位置 | 内容 |
| --- | --- | --- |
| `toolbox-web/src/services/admin-curriculum-api.ts` | `:181-227` | 新增 `AdminSourceSnapshotRepairResult`（与后端 `SourceSnapshotRepairService.RepairResult` 一一对应）与 `repairVersionSourceSnapshot(editionId, contentVersion, basis, signal?)`：`POST` + `csrf: true` + `basis` 经 `encodeURIComponent`；**发请求前再拦一次空依据**并抛出明确原因 |
| `toolbox-web/src/components/admin/VersionHistoryDrawer.vue` | `:60-73` | 补录状态：展开的版本、依据、二次确认、依据错误、在途版本、错误原文、结果 |
| 同上 | `:196-274` | `toggleRepair()` 与 `submitRepair()`：**三道闸门都在会发请求的函数里**（只补空 → 依据非空 → 已勾选确认），成功后展示结果并 `await load()` 刷新历史；`defineExpose({ submitRepair })` 只为让"程序化调用也必须拒绝"可验证 |
| 同上 | `:436-493` | 版本行内的入口：`v-if="!version.hasSourceSnapshot"` 才渲染"补录来源快照"；表单含依据输入（必填）、二次确认复选框、确认补录／取消；`disabled` 只是提示 |
| 同上 | `:511-557` | 抽屉级结果区：异常（`overwroteOriginal`）／成功（`applied`）／未应用三分支，并原样展示 `applied`、`overwroteOriginal`、`reason`、快照 SHA-256 |
| `toolbox-web/tests/component/admin-source-snapshot-repair.test.ts` | 新文件（9 条） | 挂载真实组件、只 mock 服务层 |
| `toolbox-web/tests/e2e/local-admin-source-snapshot-repair.mjs` | 新文件 | 真实浏览器 + 真实后端；强制回环（`target-guard.mjs`） |

模板与实现的对应：`data-repair-open="<版本>"`（入口）、`data-repair-form="<版本>"`（表单）、
`data-testid="repair-basis"`（依据容器）、`data-testid="repair-confirm"`（二次确认）、
`data-testid="repair-submit"`（确认补录）、`data-testid="repair-result|repair-applied|repair-overwrote|repair-reason"`、
`data-testid="repair-overwrite-anomaly"`（异常）、`data-testid="repair-error"`、`data-testid="repair-basis-error"`。

## 2. 五条要求逐条落地

1. **只在 `hasSourceSnapshot === false` 时出现**：入口包在 `v-if="!version.hasSourceSnapshot"` 分支里；
   有快照的版本仍走原来的"查看来源快照"，且**没有**任何补录控件。
2. **依据必填、空依据不得提交**：`submitRepair()` 的闸门二 `if (!basis) { …; return; }`（`basis` 已 `trim()`）；
   服务层再拦一次。按钮的 `:disabled="!repairConfirmed || !repairBasis.trim()"` 只是提示。
   组件测试用 `$emit('click')` 直接触发 NButton 的 click（**绕过它内部的 `disabled` 判断**）来证明闸门在函数里。
3. **二次确认**：沿用仓库既有约定（`CurriculumRestoreView` 的确认复选框），未勾选时函数直接返回。
4. **展示返回值并刷新**：结果区显示 `applied` / `overwroteOriginal` / `reason` / 快照 SHA-256；
   成功后 `await load()` 重新拉历史（测试断言 `fetchVersionHistory` 被调用 2 次，且入口消失、换成"查看来源快照"）。
5. **不提供覆盖路径**：界面上没有"覆盖"分支；`overwroteOriginal === true` 走**异常**告警，
   同时**不出现**成功或"未应用"结论。真实后端实测该字段为 `false`（见 §6）。
6. **不做**下架／回滚／恢复旧版为新版：本轮未新增任何此类控件（既有只读断言继续通过，测试名已改为不再自称"只读抽屉"）。

## 3. 组件测试

```bash
cd /Users/pankang/mycode/MyTools/toolbox-web
npx vitest run tests/component/admin-source-snapshot-repair.test.ts
```

```
 ✓ tests/component/admin-source-snapshot-repair.test.ts (9 tests) 385ms
 Test Files  1 passed (1)
      Tests  9 passed (9)
```

9 条分别是：入口只挂在无快照版本；空依据（含纯空白）绕过 `disabled` 也不发请求（含"填了依据必须发得出去"的正向对照）；
未勾选二次确认不发请求；提交后展示 `applied`/`reason` 并刷新历史；`overwroteOriginal: true` 显示异常；
`applied: false` 显示"未应用"；服务层报错显示错误码/状态码并保留表单；
有快照版本即使程序化调用补录函数也被拒绝；控件里没有覆盖／恢复／回滚／下架／删除。

## 4. 反向验证（逐条改坏 → 真实失败输出）

做法：先备份实现（改动前 `md5 a2f9f953…` / `bf638c53…`），每个突变只改**一处**，
运行 `npx vitest run tests/component/admin-source-snapshot-repair.test.ts`，记录输出，然后**恢复 golden 实现**
（恢复后 `md5 e43a7628…` / `4c10bd1c…`，与当前工作树一致）。原始日志：
`evidence/source-snapshot-repair-reverse-M*.log`。

| 突变 | 改坏点 | 期望失败 | 实际 |
| --- | --- | --- | --- |
| M1 | 删掉 `submitRepair` 里的依据校验（闸门二） | 空依据用例 | ✅ 失败 |
| M2 | 删掉二次确认闸门（闸门三） | 未勾选用例 | ✅ 失败 |
| M3 | 删掉"只补空"闸门（闸门一） | 程序化调用用例 | ✅ 失败 |
| M4 | 删掉提交后的 `await load()` | 刷新历史用例 | ✅ 失败 |
| M5 | `catch` 里把 `repairError` 清空（静默） | 报错显示用例 | ✅ 失败 |
| M6 | 把异常分支改成恒 false（`overwroteOriginal` 当成普通成功） | 异常显示用例 | ✅ 失败 |
| M7 | 入口改成对所有版本渲染（`v-if="true"`） | 入口可见性等 3 条 | ✅ 失败 |

### M1 删掉依据校验

```
FAIL  … > 空依据：绕过 disabled 直接触发也不发请求，并显示必填提示
AssertionError: expected "vi.fn()" to not be called at all, but actually been called 1 times
Received:
  1st vi.fn() call:
    Array [
      "demo-book",
      "1.0.0",
      "",
    ]
Number of calls: 1
 ❯ tests/component/admin-source-snapshot-repair.test.ts:214:45
```

结论：空依据的请求**确实**会带着 `basis=""` 发出去——闸门就在这个函数里，不在 `disabled` 上。

### M2 删掉二次确认闸门

```
FAIL  … > 未勾选二次确认：绕过 disabled 直接触发也不发请求
AssertionError: expected "vi.fn()" to not be called at all, but actually been called 1 times
  Array [ "demo-book", "1.0.0", "sources/cn-1-first-2024.json" ]
```

### M3 删掉"只补空"闸门

```
FAIL  … > 有快照的版本：即使程序化调用补录函数也被拒绝（只补空，不发请求）
AssertionError: the given combination of arguments (undefined and string) is invalid for this assertion …
 ❯ tests/component/admin-source-snapshot-repair.test.ts:363:64   // query('[data-testid="repair-error"]') 为 null
```

（`repairVersionSourceSnapshot` 未被调用的断言先通过了，失败在"必须显示已有快照的拒绝原因"这一条——
改坏后函数既不拒绝也不报错，界面没有任何说明。）

### M4 删掉历史刷新

```
FAIL  … > 提交后展示 applied / overwroteOriginal / reason，并刷新历史（入口随之消失）
AssertionError: expected "vi.fn()" to be called 2 times, but got 1 times
 ❯ tests/component/admin-source-snapshot-repair.test.ts:272:33
```

### M5 静默吞掉服务层错误

```
FAIL  … > 服务层报错时显示错误原文（不能静默），并保留表单便于重试
AssertionError: expected null not to be null
 ❯ tests/component/admin-source-snapshot-repair.test.ts:337:23   // [data-testid="repair-error"]
```

### M6 把 `overwroteOriginal` 当成普通成功

```
FAIL  … > 后端返回 overwroteOriginal:true 时必须显示为异常，不得当成成功
AssertionError: expected null not to be null
 ❯ tests/component/admin-source-snapshot-repair.test.ts:290:67   // [data-testid="repair-overwrite-anomaly"]
```

### M7 入口改为对所有版本渲染

```
FAIL  … > 有来源快照的版本不出现补录入口（来源元数据事后不可修改）
AssertionError: expected [ '2.0.0', '1.0.0' ] to deeply equal [ '1.0.0' ]
+   "2.0.0",
  ❯ tests/component/admin-source-snapshot-repair.test.ts:193:8
```

另有 2 条连带失败（刷新后入口不消失、控件清单出现补录入口），日志见
`evidence/source-snapshot-repair-reverse-M7-entry-shown-for-all-versions.log`。

## 5. 真实浏览器验收（只读，12/12）

```bash
cd /Users/pankang/mycode/MyTools/toolbox-web
ADMIN_COOKIE="toolbox_admin_local=$(grep toolbox_admin_local /tmp/cj.txt | awk '{print $7}')" \
  E2E_TARGET=http://127.0.0.1:4173 \
  node tests/e2e/local-admin-source-snapshot-repair.mjs
```

脚本强制回环（复用 `tests/e2e/target-guard.mjs`，非回环退出码 2）。
会话 Cookie 由真实登录取得：`/admin/v1/auth/csrf` → `/admin/v1/auth/login`（`local-admin`/`local-admin`，
nonce 走 `X-Toolbox-Admin-Csrf`，`Origin: http://127.0.0.1:4173`）→ `toolbox_admin_local`。
打开抽屉用**合成 `el.click()`**（已知 CDP 真实鼠标点击不稳）。

```
PASS 进入管理编辑页（注入会话 Cookie 后直达）
PASS 找到并点击「版本记录」触发按钮
PASS 版本记录抽屉已打开
PASS 页面会话可读到该册历史（对照数据源） —— HTTP 200 code=OK 版本数=3
  后端报告：有快照=["1.0.0","1.0.1","1.0.2"]；无快照=[]
  ⚠ 本册没有"无来源快照"的版本：补录入口的**出现**分支在本次真实数据下未被触发，仅组件测试覆盖（不写库、不制造快照）。
  补录入口=[] 快照开关=["1.0.2","1.0.1","1.0.0"]
PASS 补录入口恰好只出现在"没有来源快照"的版本上（有快照的版本不显示，事后不可修改） —— DOM 入口=[] 后端无快照=[]
PASS 每行的 hasSourceSnapshot 标记与后端一致（对照不是空集） —— 行数=3/3
PASS 抽屉控件里没有覆盖／恢复／回滚／下架／删除（本界面不提供覆盖路径） —— 违禁控件=[]
PASS 页面会话持有可用的管理 CSRF 令牌
PASS 缺 basis 的补录请求被后端拒绝（422）——界面"依据必填"与后端一致
     {"version":"1.0.0","status":422,"code":"TOOLBOX_ADMIN_005","details":{"reason":"repairBasisRequired"}}
PASS 对已有快照的版本补录：applied=false、overwroteOriginal=false、reason=snapshotAlreadyPresent（不写库）
     {"status":200,"data":{"applied":false,"overwroteOriginal":false,"reason":"snapshotAlreadyPresent",…}}
PASS 期间无渲染进程异常
PASS 期间无非预期异常响应（4xx/5xx）

通过 12/12
```

证据：`evidence/browser/source-snapshot-repair-ui.json`、
`screenshots/source-snapshot-repair-drawer.png`。

**这一段的诚实边界**：`local-acceptance-2026-10-07` 的 3 个版本**全部已有快照**，所以浏览器只验证了
"有快照 → 不出现入口""无覆盖控件""后端 422 与 `overwroteOriginal=false` 契约"这三件能由现有数据表达的事；
"无快照 → 入口出现 → 真实提交 → `applied=true`"这一支**没有**在真实数据上跑过（见 §8）。

## 6. 门禁四项

| 门禁 | 基线（改动前） | 本轮（改动后） | 结果 |
| --- | --- | --- | --- |
| `npm test` | `# tests 715` / `# pass 715` / `# fail 0` | 同：`# tests 715` / `# pass 715` / `# fail 0` | 未回退 |
| `npx vitest run` | `Test Files 11 passed` / `Tests 61 passed` | `Test Files 12 passed` / `Tests 70 passed`（+新增 9） | 全绿 |
| `npm run typecheck` | 无输出（干净） | 无输出（干净） | 通过 |
| `npm run build:production` | `✓ built` | `✓ built in 1.47s`，`EXIT=0`，日志中 `warn` 出现 **0** 次 | 0 告警 |

命令：`cd /Users/pankang/mycode/MyTools/toolbox-web && npm test && npx vitest run && npm run typecheck && npm run build:production`

## 7. 未达成项、边界与真实错误

1. **本轮没有真实错误/失败**：组件测试 9/9、反向验证 7/7 按预期失败、浏览器 12/12、四项门禁全绿。
2. **`applied=true` 的界面端到端没有在隔离库上跑**：需要先有一个"没有来源快照"的版本。
   现有隔离库没有这样的版本；要造出来必须写库（新建册／清空某版的快照），
   会污染其它工作线共用的隔离数据与对账基线，因此**刻意不做**。
   该分支由组件测试覆盖（成功展示 + 历史刷新），后端 `applied=true` 由既有
   `SourceSnapshotRoundTripTest` 的 `repairAddsProvenanceWithoutChangingBytes` 覆盖。
3. `defineExpose({ submitRepair })` 是为可验证性加的（界面不渲染有快照版本的入口，
   那条"程序化调用也必须拒绝"的闸门无法靠点击证明）。它不扩大真实可达路径。
4. 既有缺口不变：修改摘要（`changeNote`）后端不返回，界面仍如实显示"未记录"；内容对比本期不做。
5. 反向验证的突变只作用于实现文件，**未改测试**；脚本运行后已恢复 golden 实现并核对 md5。
6. 变更前备份：`/tmp/ssr-backup/{VersionHistoryDrawer.vue,admin-curriculum-api.ts}`
   （`md5 a2f9f953b4ceacff79ea6434797f569d` / `bf638c53ed51ef7ca3b67ff9010b2ce5`）。
   `toolbox-web/` 在 git 中未跟踪，回退只能靠这两份备份。

## 8. 对矩阵 `D02` / `F14` 相关行的建议

- **`status-matrix.md` D02（导出与回退，当前"部分"）**：备注里的"补录无界面"这一项**已补齐**。
  建议在 D02 备注中把该项从缺口改为已完成，并保留整体"部分"：
  "补录入口 UI 已实现（仅无快照版本可见；依据必填的闸门写在发请求的函数内、绕过 disabled 直接触发也不发请求；
  二次确认；展示 `applied`/`overwroteOriginal`/`reason` 并刷新历史；`overwroteOriginal=true` 显示为异常）。
  9 条组件测试 + 7 个反向突变（全部按预期失败）+ 12/12 真实浏览器只读验收。
  仍未在隔离库上执行 `applied=true` 的真实写入（不制造/不清理共享数据，见 §7.2）。"
- **`source-snapshot-chain.md` §8 的 F14 行（"恢复 UI/运维入口未做"）**：建议把"入口未做"更新为
  "恢复入口（前序工作线）与补录入口（本轮）均已实现"；F14 本体的 `catalogVersion` 保真状态不受本轮影响。
  若矩阵里另有一行按 `F14` 编号跟踪"导出与回退"，其判据应与 D02 一致，避免两处口径不同。
