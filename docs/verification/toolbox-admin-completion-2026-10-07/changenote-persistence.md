# 发布修改摘要（`changeNote`）持久化与版本记录返回 —— 验收证据

- 日期：2026-10-07
- 范围：`service/toolbox-service`（后端）、`toolbox-web`（前端）、隔离 MySQL 容器 `toolbox-mysql-test`
- 目的：把发布请求体里的 `changeNote` **真正持久化**，并由 `GET /admin/v1/curriculum/versions/{editionId}` 返回，
  让版本记录抽屉从"未记录"变为显示真实摘要；不填时保持原样、不编造
- 生产边界：全程只访问本机回环（`127.0.0.1:23200` / `127.0.0.1:13316`）。
  **未对任何线上地址发起请求**，未创建线上账号、未发布任何线上教材

---

## 0. 结论一览

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 发布带 `changeNote` → 审计 `summary` 含它 | ✅ | §3.1、§5.1 |
| 发布带 `changeNote` → `VersionSummary.changeNote` 返回它 | ✅ | §3.1、§5.1 |
| 不填 → 审计摘要保持原文本、无空括号 | ✅ | §3.2、§5.2 |
| 不填 → 版本记录 `changeNote` 为 `null`（界面显示"未记录"） | ✅ | §3.2、§5.2 |
| 同键重放 → 审计行数不变、返回原摘要 | ✅ | §3.3、§5.1 |
| 同键**不同摘要** → 409 `idempotencyKeyReused`（不静默复用回执） | ✅ | §5.3 |
| 反向验证：写摘要改坏 → 后端用例失败 | ✅（2 条失败） | §4.1 |
| 反向验证：不返回时显示空 → 前端用例失败 | ✅（2 条失败） | §4.2 |
| 后端全量 | ✅ 412（基线 409 + 新增 3） | §6 |
| 前端 Node 测试 | ✅ 715 | §6 |
| 前端组件测试 | ✅ 71（基线 70 + 新增 1） | §6 |
| `typecheck` | ✅ 退出码 0 | §6 |
| `build:production` | ✅ 退出码 0、0 告警 | §6 |

---

## 1. 改动文件

| 文件 | 改动 |
| --- | --- |
| `service/toolbox-service/.../controller/admin/curriculum/AdminTextbookController.java` | 发布接口新增 `@RequestBody(required=false) Map<String,Object> body`，只从中读 `changeNote`；`draftRevision` 仍从查询串读（既有调用不变）；`changeNote` 进入幂等意图载荷 |
| `service/toolbox-service/.../service/admin/curriculum/AdminTextbookService.java` | `publish(...)` 新增 4 参重载（3 参旧签名保留并委托）；审计摘要改为 `withChangeNote(...)`；新增 `CHANGE_NOTE_MARKER` / `CHANGE_NOTE_MAX_LENGTH` / `normalizeChangeNote` / `changeNoteFromAuditSummary`；`VersionSummary` 新增 `changeNote` 字段；`listVersions` 按关联键取回摘要 |
| `service/toolbox-service/.../mapper/admin/AdminTextbookRepository.java` | 新增 `findPublishAuditSummary(editionId, contentVersion)`（参数化 SQL，只读） |
| `service/toolbox-service/src/test/java/.../AdminTextbookApiTest.java` | 新增 3 条接口级用例 + 1 个 `versionRow` 辅助 |
| `toolbox-web/src/services/admin-curriculum-api.ts` | `AdminVersionSummary.changeNote` 文档更新（字段声明此前已在，属可选字段） |
| `toolbox-web/src/components/admin/VersionHistoryDrawer.vue` | 顶部注释与底部缺口说明更新（缺口已闭合，保留"未填写→未记录"的如实说明）；`changeNoteOf` 逻辑**未改**（原有实现已正确） |
| `toolbox-web/tests/component/admin-version-history-drawer.test.ts` | 更新两条用例的说明与断言文本；新增"空串摘要按未记录处理"用例 |

改动前的原始文件备份（`service/` 与 `toolbox-web/` 在 git 里未被跟踪，无法回退）：
`/tmp/changenote-backup-20261007-042944/`。

---

## 2. 关联键与理由（明确了才写）

**选定关联键：`(edition_id, action='textbook.publish', to_version = content_version)`。**

- `to_version` 写的是 `published.contentVersion()`，也就是 `repository.nextContentVersion(editionId)` 服务端分配的
  内容版本号；版本表 `toolbox_textbook_version.content_version` 就是同一个值。
  同一次发布只写**一条** `textbook.publish` 审计，因此一一对应且稳定。
- **为什么不用 `to_revision`**：它是**草稿**修订号（发布写回 ID 映射之后的值），与版本号没有派生关系；
  同一册连续发布时它持续变化，且历史版本行里并不保存它。用它关联等于猜。
- **为什么必须按 `action` 过滤**：`textbook.confirm` 审计行复用**同一个 `to_version`**（见
  `AdminTextbookService.publishLocked`），只按 `to_version` 查会命中两行。
- 查询取最近一条（`ORDER BY id DESC LIMIT 1`）：正常只有一条，历史脏数据也不会取到旧摘要。
- 取不到（本次改造之前发布的历史版本，或发布时未填写）→ `changeNote` 为 `null`，界面显示"未记录"。

**摘要文本格式**（`summary` 列，VARCHAR(512)）：

```
发布时填写了摘要：发布修订 2，1 条内容（修改摘要：补充附录来源并核对第一单元）
未填写：          发布修订 2，1 条内容
```

标记 `（修改摘要：` 只由生成方写入一次，基础摘要里不含它，所以 `indexOf` 命中的一定是生成的那一处；
末尾的 `）` 也是生成的那个，摘要正文里出现 `）` 不会截错。格式不认识时返回 `null`——**不猜**。

**一个需要说明的判断**：摘要超长时后端按 **200 字符**（与前端 `CHANGE_NOTE_MAX_LENGTH` 一致）截断。
设计未规定上限；这条截断**不是校验**，只是保证 `summary`（VARCHAR(512)）一定写得下，
避免一条超长摘要让整次发布在落审计时失败回滚。截断时不会切坏代理对（emoji）。

---

## 3. 接口级用例（`AdminTextbookApiTest`，H2 + Flyway）

新增 3 条：

| 用例 | 断言 |
| --- | --- |
| `publishPersistsChangeNoteIntoAuditAndVersionHistory` | 审计 `summary` 含 `changeNote`；`/curriculum/versions/{id}` 对应行 `changeNote` 等于它 |
| `publishWithoutChangeNoteKeepsPlainAuditSummary` | `summary` 以 `发布修订 ` 开头、以 ` 条内容` 结尾，且**不含** `修改摘要`／`（`／`）`；版本行 `changeNote` 缺席或为 `null`（**不是空串**） |
| `idempotentPublishReplayKeepsOriginalChangeNote` | 重放返回同一 `contentVersion`；`textbook.publish` 审计只有 1 条；版本记录仍是**第一次**的摘要 |

运行结果：

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.yuyutian.mytools.toolbox.AdminTextbookApiTest
[INFO] BUILD SUCCESS
```

---

## 4. 反向验证（真实失败输出）

### 4.1 把"写摘要"那一步改坏 → 后端必须失败

改动：把 `withChangeNote("发布修订 …")` 换回固定文本（即不写摘要，等价于改造前），然后运行 3 条新用例。

真实输出（`/tmp/changenote-reverse/reverse-break-write.log`）：

```
[ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 2.123 s <<< FAILURE! -- in com.yuyutian.mytools.toolbox.AdminTextbookApiTest
[ERROR] com.yuyutian.mytools.toolbox.AdminTextbookApiTest.publishPersistsChangeNoteIntoAuditAndVersionHistory -- Time elapsed: 0.027 s <<< FAILURE!
java.lang.AssertionError:
[审计摘要必须含 changeNote]
Expecting actual:
  "发布修订 2，0 条内容"
to contain:
  "补充附录来源并核对第一单元"
	at com.yuyutian.mytools.toolbox.AdminTextbookApiTest.publishPersistsChangeNoteIntoAuditAndVersionHistory(AdminTextbookApiTest.java:657)

[ERROR] com.yuyutian.mytools.toolbox.AdminTextbookApiTest.idempotentPublishReplayKeepsOriginalChangeNote -- Time elapsed: 0.029 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
expected: "第一次填写并已生效的摘要"
 but was: "null"
	at com.yuyutian.mytools.toolbox.AdminTextbookApiTest.idempotentPublishReplayKeepsOriginalChangeNote(AdminTextbookApiTest.java:730)
```

说明：第 2 条失败**不是**"同键重放"本身坏了——重放仍返回原版本；失败点是"版本记录里取不到摘要"，
即"写坏 → 读不到"这条链断了。第 3 条（不填）在破坏下**依然通过**，这是正确性质：
不填摘要的路径本就不依赖写入逻辑。恢复改动后 3 条重新全绿（§6）。

### 4.2 把"后端不返回时显示未记录"改成"显示空" → 前端必须失败

改动（`VersionHistoryDrawer.vue` 的 `changeNoteOf`）：

```ts
// 原：return typeof note === 'string' && note.trim() ? note.trim() : '未记录';
return typeof note === 'string' ? note.trim() : '';
```

真实输出（`/tmp/changenote-reverse/reverse-break-fallback.log`）：

```
 × 修改摘要后端未返回时显示"未记录"，不得编造 21ms
 × 空串摘要按"未记录"处理：不把"填了空"画成一条摘要 17ms
 ⎯⎯⎯⎯⎯⎯⎯ Failed Tests 2 ⎯⎯⎯⎯⎯⎯
 FAIL  tests/component/admin-version-history-drawer.test.ts > 版本记录抽屉（设计 3.6 第 166 行） > 修改摘要后端未返回时显示"未记录"，不得编造
AssertionError: expected '' to be '未记录' // Object.is equality
 FAIL  tests/component/admin-version-history-drawer.test.ts > 版本记录抽屉（设计 3.6 第 166 行） > 空串摘要按"未记录"处理：不把"填了空"画成一条摘要
AssertionError: expected '' to be '未记录' // Object.is equality
      Tests  2 failed | 8 passed (10)
```

恢复后 10 条全绿。

---

## 5. 真实 MySQL 端到端（独立实例 `127.0.0.1:23200`）

**为什么不复用 23199**：23199 是已由上游/父会话启动的常驻实例，跑的是**改造前**的构建；
我没有结束它（`lsof -iTCP:23199` 在验证前后都仍有 1 个监听），而是用同一套
`local,local-admin` 配置与隔离 MySQL 另起一个实例（`--server.port=23200`），验证后已停止
（`lsof -iTCP:23200` 无监听）。身份用 `local-admin / local-admin`（测试替身），只访问回环。

### 5.1 带摘要发布 + 同键重放

命令与输出（`/tmp/changenote-live/run1.log`）：

```
===== 4) PUBLISH WITH changeNote =====
HTTP 200
{"code":"OK","data":{"editionId":"changenote-live","contentVersion":"1.0.0",...,"catalogVersion":"catalog-7","catalogEpoch":"6"}}

===== 5) VERSION HISTORY（期望 changeNote=补充附录来源并核对第一单元）=====
HTTP 200
changeNote=补充附录来源并核对第一单元
fullRow={...,"hasSourceSnapshot":true,"sourceSnapshotSha256":"4600aaaa…","changeNote":"补充附录来源并核对第一单元"}

===== 6) REPLAY SAME KEY + SAME BODY =====
HTTP 200
replayVersion=1.0.0

===== 7) AUDIT ROWS IN DB =====
1	发布修订 2，1 条内容（修改摘要：补充附录来源并核对第一单元）
```

数据库侧原文：

```
$ docker exec toolbox-mysql-test mysql --default-character-set=utf8mb4 -uroot -p*** -N -e \
  "SELECT id, action, from_revision, to_revision, to_version, summary FROM toolbox_test.toolbox_curriculum_audit
   WHERE edition_id='changenote-live' ORDER BY id;"
18	draft.save	1	2	NULL	修订 1 → 2
19	textbook.publish	2	3	1.0.0	发布修订 2，1 条内容（修改摘要：补充附录来源并核对第一单元）
```

即：带摘要发布后审计**只有 1 条** `textbook.publish`，`summary` 含摘要；同键重放**没有**新增审计
（仍是 18/19 两条），版本记录也能按 `to_version=1.0.0` 取回摘要。

### 5.2 不带摘要发布

```
===== A) PUBLISH WITHOUT changeNote（无正文）=====
HTTP 200
changeNoteField=null
发布修订 2，1 条内容
```

即：`VersionSummary.changeNote` 为 `null`（界面显示"未记录"），审计摘要保持原文本、**没有空括号**。

### 5.3 同键不同摘要

```
===== 第一次：key=intent-52552A93-… note=第一次意图 =====
HTTP 200 version=1.0.0
===== 第二次：同一个 key，但 note=第二次意图（必须 409 idempotencyKeyReused）=====
HTTP 409
{"code":"TOOLBOX_ADMIN_004","messageKey":"admin.conflict","details":{"reason":"idempotencyKeyReused"},...}
===== 审计条数与摘要（必须只有 1 条、且是第一次的摘要）=====
1	发布修订 2，1 条内容（修改摘要：第一次意图）
versionsChangeNote=第一次意图
```

---

## 6. 门禁五项（本机实测）

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `mvn -o test` | `Tests run: 412, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（基线 409 + 新增 3） |
| 前端 Node 测试 | `npm test` | `# tests 715` / `# pass 715` / `# fail 0` |
| 前端组件测试 | `npx vitest run` | `Test Files 12 passed (12)` / `Tests 71 passed (71)`（基线 70 + 新增 1） |
| 类型检查 | `npm run typecheck` | 退出码 0，无输出 |
| 生产构建 | `npm run build:production` | 退出码 0；真正的告警/错误 **0** 条（对 `warn|error|deprecat` 的唯一命中是产物名 `dist/assets/admin-error-messages-*.js`）。产物 `TextbookEditorView-*.js` 里可搜到新增的"发布时未填写"文案，证明构建确实包含了本次改动 |

原始日志：`/tmp/changenote-reverse/{backend-full,web-node-tests,web-vitest,web-typecheck,web-build}.log`。

---

## 7. 未达成项与真实问题（不掩饰）

1. **23199 常驻实例仍是改造前构建**：它需要重启才会返回 `changeNote`。
   我没有结束父会话的进程（避免打断共享环境），改用 23200 独立实例完成端到端验证后已停止。
   若需要在 23199 上看到新行为，请按 `local-environment.md` §3.1 重启后端。
2. **`baseContentVersion` 仍未从正文读取/绑定**：设计第 362 行的请求体含它，但当前发布只用
   `stored.baseContentVersion`（库里的草稿基线）做服务端基线校验，正文里的该字段被忽略。
   本次任务只要求 `changeNote`，未扩大范围。这是 C03 清单里"发布绑定 `baseContentVersion`"的剩余缺口。
3. **`draftRevision` 仍从查询串读**（正文里的同名字段被忽略）。前端**两处都发**，因此线上链路正确；
   但"只发正文"的第三方调用方仍会被静默忽略修订绑定。改成"正文优先、查询串兼容"属于接口行为调整，
   按边界要求未做。
4. **`CurriculumPublisher.publish` / `publishDraft` 的 `changeNote` 形参依旧是死参数**：
   审计由服务层写（`AdminTextbookService.publishLocked`），发布器根本没有写审计的职责，
   所以本次没有把它接进去。删除这两个参数会改动调用点与既有测试，属于独立的清理动作，未做。
5. **摘要 200 字符**：见 §2 末。这不是设计条款，是我为保证审计列可写而引入的上限，需要业务线确认是否保留。
6. **H2 覆盖不到真实列宽**：`local-environment.md` §6.1 已记录过同类机制性事实。
   本次摘要落在 `summary VARCHAR(512)`，H2 不强制截断，因此额外做了 §5 的真实 MySQL 端到端。

---

## 8. 对状态矩阵/清单相关行的建议（未改动这些文件）

- `status-matrix.json` 项 **C03「草稿与版本分离」**：note 里"缺口：后端 `VersionSummary` 不返回 `changeNote`，
  界面显示未记录而不编造"**已不成立**，建议改为：
  "`changeNote` 已写入发布审计 `summary` 并由版本记录返回（关联键 `(edition_id, action='textbook.publish', to_version)`，
  见 `changenote-persistence.md`）；剩余缺口：设计 166 行「内容对比」本期不做、
  发布正文的 `baseContentVersion` 仍未绑定。"
  是否把 status 从「部分」改为「完成」取决于"内容对比"是否计入 C03。
- `final-classification.md` 第 48 行「**`changeNote` 后端不返回**」：建议标注为**已修复**（证据指向本文件）。
- `admin-editor-ui.md` 第 132–133 行（`changeNote` "未接受/需要后端写入"）：建议标注为**已完成**，
  并把剩余项收敛到 `baseContentVersion` 绑定与"正文 vs 查询串"读法。
- `needs-decision.md`：无需新增条目——本次没有触碰 `catalogVersion`/`epoch`、`order` 基数、
  `null` vs `""`、草稿 `reviewStatus` 语义。唯一接近"约定"的是 §7.5 的 200 字符上限，
  若业务线认为需要拍板，可考虑单列一条。

---

## 附：`baseContentVersion` 的查证（第 153 轮，父会话）

实现者报告里把「`baseContentVersion` 仍未从正文读取/绑定」列为 C03 剩余缺口 ✓。我查了代码，**结论比"缺口"更准确**：

| 事实 | 位置 |
| --- | --- |
| 发布请求的正文里**确实不读** `baseContentVersion`（只有 `draftRevision` 走查询串）| `AdminTextbookController` 的 publish 方法只声明 `@RequestParam draftRevision` |
| 服务端用的是**草稿在库里的基线值**，不是请求体里的 | `AdminTextbookService`：`String baseline = stored.baseContentVersion();` |
| 该基线在保存草稿时由**服务端**写入 | `base_content_version` 列（V5 迁移把宽度从 32 改到 64）|

**所以这不是"没实现"，而是"实现方式与设计第 362 行不同"** ✓：
设计把 `baseContentVersion` 列在**请求体**里 ✓；实现把它当作**服务端持有的状态** ✓。

**哪个更安全**：服务端持有 ✓ —— **客户端无法通过伪造正文来绕过并发校验** ✓
（如果从正文读，客户端可以传一个过期的基线来"制造"一次成功的发布 ✓ —— 实现反而更严 ✓）。

**但它确实带来一个真实的行为差异** ✓：设计里"客户端应在正文里声明基线"这一条**没有被校验** ✗ ——
若将来有调用方**只发正文**（不发查询串的 `draftRevision`）✓，会**被静默忽略** ✗ ✓
（实现者自己在未达成项 3 里也指出了同类问题 ✓）。

**我把它归入"约定不一致"而不是"缺实现"** ✓ —— 与本文件列的 `order` 基数、`null` vs `""` 同类 ✓：
**两套做法都能自洽，需要拍板统一** ✗ ✓。
