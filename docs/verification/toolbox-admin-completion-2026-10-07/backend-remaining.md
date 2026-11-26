# 后端剩余三项：发布响应目录字段、预览真范围过滤、真实 MySQL 复核

- 日期：2026-10-07
- 范围：`service/toolbox-service`（后端），隔离 MySQL 容器 `toolbox-mysql-test`
- 本次负责：任务 1（发布响应带 `catalogVersion`/`catalogEpoch`）、任务 2（预览真范围过滤 + `draftRevision` 回显/校验）、任务 3（真实 MySQL 四条复核）
- 生产边界：**只读**。全程只访问回环地址（`127.0.0.1:13316` MySQL / `127.0.0.1:23211`、`23212`、`23213` 本地实例）。
  未对 `toolbox.yuyutian.top` 发起任何请求，未部署、未写入生产，未动前端。
- 未改动：`docs/design/` 下任何设计条款、`ErrorCode.java` 的错误码集合、公开接口（`/public/v1/**`）行为、
  `toolbox-web/`、`common/ErrorCode.java`、历史验证目录（10-05/10-06）。

---

## 0. 结论一览

| 任务 | 状态 | 关键证据 |
| --- | --- | --- |
| 1. 发布响应返回 `catalogVersion` 与 `catalogEpoch` | ✅ 已实现 + 已验证 + 反向验证 | §2；`remaining-01/02/10` |
| 2. 预览真范围过滤（+ `draftRevision` 回显与服务端校验） | ✅ 已实现 + 已验证 + 反向验证 | §3；`remaining-01/03/09/10` |
| 3. 真实 MySQL 复核四条 | ✅ 四条全部跑通，其中 2 条带附加发现 | §4；`remaining-task3-*` |
| 全量测试无回归 | ✅ **403 通过 / 0 失败**（基线 395，新增 8） | §5；`remaining-10-full-suite-final.log` |

**新发现的缺陷（未修，超出本次授权范围，见 §6）**：

| 编号 | 缺陷 | 严重度 |
| --- | --- | --- |
| D1 | `catalog_version` 与 `catalog_epoch` 的拼装关系在 MySQL 与 H2 上**不一致**（MySQL 首次发布得 `catalog-2`/`epoch=1`） | 中（对外版本号与设计示例不符；H2 用例给出虚假信心） |
| D2 | `toolbox_curriculum_audit.from_version/to_version` 只有 `VARCHAR(32)`，长 `editionId` 教材**第二次发布**（以及迁移来的长标识教材**首次发布**）必然 503 | 高（教材发不出新版本，且只在真实 MySQL 暴露） |
| D3 | 发布失败回滚后，**内存目录快照没有回退**：数据库已回滚，公开接口却继续提供那个从未提交的版本 | 高（对外可见的幽灵版本） |

---

## 1. 改动文件清单

`service/toolbox-service/` 在 git 中**未跟踪**（`git ls-files` 计数 0），因此无法用 git 回退或生成 diff；
下面是本次触碰的全部文件（其余文件未改动）：

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `src/main/java/.../service/admin/curriculum/CurriculumPublisher.java` | 改 | `PublishResult` 增加 `catalogVersion`/`catalogEpoch`；两条发布路径（`publish`、`publishDraft`）读列取回 |
| `src/main/java/.../controller/admin/curriculum/AdminTextbookController.java` | 改 | 发布响应的 `data` 增加两个字段；预览端点增加 `nodeKind`/`nodeKey`/`draftRevision` 三个**可选**查询参数与 `draftRevision`/`scope` 回显 |
| `src/main/java/.../service/admin/curriculum/AdminTextbookService.java` | 改 | `preview(...)` 支持范围过滤与修订号校验；新增 `PreviewScope`；`PreviewPayload` 增加 `draftRevision`/`scope` |
| `src/test/java/.../AdminTextbookPublishAndPreviewScopeTest.java` | 新增 | 8 个用例，覆盖两项功能与三条反向验证锚点 |

未新增依赖、未新增错误码、未改 `pom.xml`、未改任何迁移脚本、未改 `ErrorCode`。

---

## 2. 任务 1：发布响应带 `catalogVersion` 与 `catalogEpoch`

### 2.1 设计依据与实现

设计 `docs/design/2026-10-05-toolbox-admin-textbooks/design-and-implementation.md` 第 391-392 行规定发布响应里两者**并存**：

```json
"catalogVersion": "catalog-42",
"catalogEpoch": "42"
```

实现（沿用现有 `ApiResponse` 信封与 `PublishResult` 风格，公开接口不变）：

```java
// CurriculumPublisher.publish / publishDraft
String catalogVersion = repository.currentCatalogVersion();   // 读 toolbox_curriculum_catalog.catalog_version
long catalogEpoch = repository.currentEpoch();               // 读 toolbox_curriculum_catalog.epoch
boolean reloaded = reloadSnapshot(catalogVersion);
return new PublishResult(editionId, contentVersion, versionId, sha256, reloaded,
        catalogVersion, catalogEpoch);
```

```java
// AdminTextbookController.publish 的 data 里追加
value.put("catalogVersion", published.catalogVersion());
value.put("catalogEpoch", String.valueOf(published.catalogEpoch()));  // 设计示例里是字符串 "42"
```

两点刻意选择：

1. **读列，不由 epoch 现算**——沿用上一条线把 `catalog_version` 改为读列的决定（`AdminTextbookRepository.currentCatalogVersion()`），
   这样初始迁移保留的旧 `catalogVersion`（如 `catalog-2026-10-05.7`）不会被计数器改写。
2. `catalogEpoch` 按设计示例用**字符串**（`"42"`），避免大整数在前端 JSON 里失真；测试里用 `isTextual()` 锁定这一形态。

### 2.2 测试（真实 HTTP + 真实库列）

`AdminTextbookPublishAndPreviewScopeTest`：

| 用例 | 断言 |
| --- | --- |
| `publishResponseCarriesCatalogVersionAndEpoch` | 两个字段都存在；`catalogEpoch` 是字符串；两者与 `toolbox_curriculum_catalog` 的列**逐字一致**；一次发布 `epoch+1`；新版本名以 `catalog-` 开头 |
| `eachPublishAdvancesEpochAndCatalogVersion` | 连续两次发布：`epoch` 各 +1，`catalog_version` 必须变化，且等于库中值 |
| `catalogVersionComesFromStoredColumn` | 把库改成 `epoch=7 / catalog_version='catalog-77'`（无派生关系），`currentCatalogVersion()` 必须原样返回 `catalog-77` 而不是 `catalog-7` |

> **不断言 `catalog_version == "catalog-" + epoch`**：该关系在 H2 与真实 MySQL 上不一致（见 §6 D1），
> 把它写进用例等于把 H2 的行为当成契约。用例只锁定"确实生成了新的目录版本名"与"与库列一致"。

### 2.3 反向验证（撤掉字段返回 → 测试失败）

撤掉控制器里两行 `value.put(...)`，再跑两个用例：

```
$ mvn -o test -Dtest='AdminTextbookPublishAndPreviewScopeTest#publishResponseCarriesCatalogVersionAndEpoch+eachPublishAdvancesEpochAndCatalogVersion'
[ERROR] Tests run: 2, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 2.078 s <<< FAILURE!
org.opentest4j.AssertionFailedError: 
[发布响应必须带 catalogVersion] 
Expecting value to be true but was false

[ERROR] com...AdminTextbookPublishAndPreviewScopeTest.eachPublishAdvancesEpochAndCatalogVersion -- Time elapsed: 0.038 s <<< ERROR!
java.lang.NumberFormatException: For input string: ""

[ERROR]   AdminTextbookPublishAndPreviewScopeTest.publishResponseCarriesCatalogVersionAndEpoch:107 [发布响应必须带 catalogVersion] 
[ERROR]   AdminTextbookPublishAndPreviewScopeTest.eachPublishAdvancesEpochAndCatalogVersion:131 » NumberFormat For input string: ""
[ERROR] Tests run: 2, Failures: 1, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

原文见 `evidence/remaining-02-reverse-task1-fields-removed.log`；还原后同一组用例 8/8 通过
（`evidence/remaining-08-targeted-final.log`）。

---

## 3. 任务 2：预览的真范围过滤

### 3.1 现状与选择

现状（已复核）：`AdminTextbookController.preview` 只接受 `editionId`，`CurriculumEntry` 没有 `rowId`/`clientUnitId`，
前端 `useAdminDraftPreview.generate()` 也只能整册生成，并**如实标注**"仍按全册生成"。

**最小改动选择**：在预览端点上加三个**可选**查询参数，缺省时行为与改造前完全一致。

- 为什么选 `nodeKind`/`nodeKey`：前端 `TextbookEditorView.vue` 已有 `selectedNode = { kind: 'unit' | 'lesson' | 'all', id: clientUnitId|clientLessonId }`
  模型（第 259/313/596 行），参数名与它一一对应，前端改动只需把已有值传出去，不需要新状态。
- 为什么在**映射之后**过滤：条目沿用与发布完全相同的正式 ID 与顺序号，"预览里选中的这条"在发布后还是同一条。

### 3.2 接口契约（全部为**新增可选**，缺省不变）

```
GET /admin/v1/textbooks/{editionId}/draft/preview
      ?nodeKind=unit|lesson        可选：范围类型
      &nodeKey=<标识>              可选：clientUnitId/clientLessonId 或 unitId/lessonId
      &draftRevision=<n>           可选：调用方声称的草稿修订号
```

行为：

| 输入 | 行为 |
| --- | --- |
| 三个参数都不给 | **整册**，与改造前逐字一致（`scope.filtered=false`） |
| `nodeKind=unit` + `nodeKey` | 只含该单元 + 它名下的课文 + 属于它的条目 |
| `nodeKind=lesson` + `nodeKey` | 只含该课文 + 它名下的条目 + 它所属的父单元 |
| 只给一个参数 / `nodeKind` 不是 `unit`/`lesson` / 标识在草稿里找不到 | **404** `TOOLBOX_ADMIN_006`，`details.reason=previewScopeNotFound` |
| `draftRevision` 与库中当前修订不符 | **409** `TOOLBOX_ADMIN_004`，`details.reason=revisionMismatch`（与 `/validate` 同一语义） |

**为什么"范围不存在"选 404 而不是空列表**：返回空会被前端当成"这个单元没有内容"，
与"范围写错了"是两件事；把两者混在一起会让管理员以为内容丢了。这里让调用方**必定能区分**。

响应新增字段（原有字段全部保留）：

```json
{ "edition": {...}, "units": [...], "lessons": [...], "entries": [...],
  "draftPreview": true,
  "draftRevision": 2,
  "scope": { "nodeKind": "unit", "nodeKey": "cu2", "filtered": true } }
```

带范围时还有一处**语义修正**：`edition.availableEntryCount` 按**范围内**的条目数统计
（否则响应会声称"含 318 条"却只返回 12 条）；整册时与改造前一致。

### 3.3 测试

| 用例 | 断言 |
| --- | --- |
| `previewWithoutScopeReturnsWholeBook` | 4 条/2 单元/2 课文；`scope.filtered=false`；`draftRevision` 等于草稿当前修订 |
| `previewWithUnitScopeReturnsOnlyThatUnit` | 只返回 `["火","土"]`，1 单元 1 课文，`availableEntryCount=2`；**正式标识**同样可作范围键；预览后修订号不变（只读） |
| `previewWithLessonScopeReturnsOnlyThatLesson` | 只返回 `["山","水"]`，课文 `-l01`、父单元 `-u01` |
| `unknownPreviewScopeIs404` | 标识不存在 / 类型不认识 / 只给一半参数，四种输入全部 404 + `previewScopeNotFound` |
| `previewChecksExpectedDraftRevision` | 相符 200 且回显；旧修订 409 `revisionMismatch`；范围与修订号可同用 |

### 3.4 反向验证

**(a) 撤掉范围过滤**（`applyPreviewScope` 直接 `return mapped`）：

```
$ mvn -o test -Dtest='...#previewWithUnitScope...+previewWithLessonScope...+unknownPreviewScopeIs404+previewWithoutScopeReturnsWholeBook'
[ERROR] Tests run: 4, Failures: 3, Errors: 0, Skipped: 0
org.opentest4j.AssertionFailedError: 
Expecting actual:
  ["山", "水", "火", "土"]
to contain exactly (and in same order):
  ["山", "水"]
but some elements were not expected:
  ["火", "土"]

org.opentest4j.AssertionFailedError: 
[query=?nodeKind=unit&nodeKey=nope] 
expected: 404
 but was: 200

[ERROR]   ...previewWithLessonScopeReturnsOnlyThatLesson:227 
[ERROR]   ...previewWithUnitScopeReturnsOnlyThatUnit:200 
[ERROR]   ...unknownPreviewScopeIs404:244->assertScopeNotFound:259
[ERROR] Tests run: 4, Failures: 3, Errors: 0, Skipped: 0
```

即：**3 个带范围的用例失败，整册那条仍通过**（4 跑 3 败）——正是"过滤逻辑被撤掉"应有的形状。
原文：`evidence/remaining-03-reverse-task2-filter-removed.log`。

**(b) 撤掉修订号服务端校验**（`if (false)`）：

```
$ mvn -o test -Dtest='AdminTextbookPublishAndPreviewScopeTest#previewChecksExpectedDraftRevision'
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
expected: 409
 but was: 200
[ERROR]   ...previewChecksExpectedDraftRevision:277 
```

原文：`evidence/remaining-09-reverse-task2-revision-check-removed.log`。

---

## 4. 任务 3：真实 MySQL 复核四条

### 4.0 环境与可重跑脚本

| 项 | 值 |
| --- | --- |
| 容器 | `toolbox-mysql-test`（**未创建、未删除、未改动**），`mysql:8.4` / 实测 `8.4.11`，卷 `toolbox-mysql-test-data` |
| `sql_mode` | `ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION` |
| 本次专用临时库 | `toolbox_verify_a`（任务 1/2/3）、`toolbox_verify_b`（任务 4 的目标空库）、`toolbox_verify_c`（附加探针） |
| **未触碰** | `toolbox_test`（复核前后都是 3 个版本行）、容器、数据卷、以及其它验收线的 `toolbox_roundtrip` |
| 本地实例 | A=`23211`（库 a）、B=`23212`（库 b，空库）、C=`23213`（库 c） |
| 端口说明 | 任务给的实例仍在 `23199`（未重启、未打扰）；`23201` 当时已被**另一条**验收线的 roundtrip 实例占用，因此本次改用 `23211/23212/23213` |

启动命令（三条实例同一套，只换 `TOOLBOX_DB_NAME` / `TOOLBOX_PORT` / `TOOLBOX_CATALOG_PATH`）：

```bash
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
export TOOLBOX_DB_PASSWORD=toolbox-test-only
export TOOLBOX_DB_NAME=toolbox_verify_a TOOLBOX_PORT=23211
export TOOLBOX_CATALOG_PATH=/tmp/toolbox-remaining/task3/content
cd /Users/pankang/mycode/MyTools/service/toolbox-service
mvn -o spring-boot:run -Dspring-boot.run.profiles=local,local-admin \
  -Dspring-boot.run.additional-classpath-elements="$PWD/target/test-classes"
```

可重跑脚本（本目录 `scripts-remaining/`，全部幂等）：

| 脚本 | 作用 |
| --- | --- |
| `10-setup.sh` | 重置两个临时库、从 `toolbox_test` 按 HEX 逐字节导出 `book.json`、拼迁移清单 |
| `login.sh` / `stop-instance.sh` | 登录取 CSRF/Cookie；按 `TOOLBOX_DB_NAME` 核对后停止**本任务自己的**实例 |
| `20-item1-migration.sh` | 任务 3-1 |
| `30-item2-rollback.sh` / `31-item2-compare-states.sh` | 任务 3-2（含附加发现）；状态比对用 python 实现，不依赖本机 `diff` |
| `40-item3-unique-keys.sh` | 任务 3-3 |
| `50-item4-export.sh` / `55-item4-restore.sh` / `60-item4-after-restart.sh` | 任务 3-4 |
| `70-*/75-*` | 附加探针（D2 的适用范围） |

---

### 4.1 ① catalogVersion 的迁移保留与首次真实发布

**结论：迁移保留清单值 ✅；首次真实发布产生新的 `catalog-*` ✅；但两者的拼装关系在 MySQL 上与 H2 不同（D1）。**

原始输出（`evidence/remaining-task3-20-item1.log`，节选）：

```
===== a. 迁移前的目录世代行（空库由 Flyway 建表后的初始值） =====
current	0	catalog-0

===== b. 登录并调用 POST /admin/v1/curriculum/import-from-files（清单 catalogVersion=catalog-2026-10-05.7） =====
HTTP 200
{"code":"OK","data":{"outcomes":[{"editionId":"lab-a","contentVersion":"1.0.0","state":"INSERTED",
 "storedSha256":"f6668c373dade85a186daaf7da59bc0b3e82985bc8c1a07c057fa9ce5fa19915"}],
 "conflicts":[],"ok":true},...}

===== b2. 迁移后：epoch 必须仍为 0，catalog_version 必须是**清单里的值** =====
current	0	catalog-2026-10-05.7            <-- 清单值被保留，epoch 未被推进
lab-a@1.0.0	1.0.0	f6668c373dade85a
lab-a	lab-a@1.0.0
draft-lab-a	1	lab-a@1.0.0

===== c/d. 首次真实发布：POST /admin/v1/textbooks/lab-a/publish?draftRevision=2 =====
HTTP 200
{"code":"OK","data":{"editionId":"lab-a","contentVersion":"1.0.1","versionId":"lab-a@1.0.1",
 "contentSha256":"29680ce4321affd42e151892adefc4782bcbb369abc93f35bdf3d24efb569d6c",
 "snapshotReloaded":true,"catalogVersion":"catalog-2","catalogEpoch":"1"},...}

===== d2. 发布响应里的两个字段 vs 库中的列 =====
响应 catalogVersion=catalog-2  catalogEpoch=1
库中  epoch=1  catalog_version=catalog-2
断言 catalogVersion == 库中 catalog_version：通过
断言 catalogEpoch == 库中 epoch：通过
断言 首次真实发布后 epoch=1：通过
断言 首次真实发布生成了新的 catalog-* 版本名：通过
断言 新版本名已不再是清单值：通过
```

补充说明：迁移来的草稿由正文重建，**没有 `sourceNote`**（该字段只存在于草稿，不属于已发布正文），
而发布校验要求来源说明必填，因此脚本如实走了管理员会走的那一步（补一句来源说明再发布）；
这不是本次改动引入的行为。

---

### 4.2 ② 事务/回滚：发布失败不留痕迹

**结论：数据库层面的回滚是干净的（7 项状态逐项一致）✅；但内存快照没有回退（D3）❌。**

触发方式：用 31 字符的 `editionId` 发布两次。第二次发布时 `toolbox_curriculum_audit.from_version`
是 `VARCHAR(32)`，放不下 37 字符的 `editionId@1.0.0`，异常发生在**版本行已插入、current 指针已移动、
目录世代已递增之后**，因此正好检验回滚。

原始输出（`evidence/remaining-task3-30-item2.log`，节选）：

```
===== 1. 第一次发布（应当成功，建立 current 指针） =====
HTTP 200
{"code":"OK","data":{"editionId":"verify-rollback-2026-10-07-d134","contentVersion":"1.0.0",
 "versionId":"verify-rollback-2026-10-07-d134@1.0.0","snapshotReloaded":true,
 "catalogVersion":"catalog-4","catalogEpoch":"3"},...}

===== 2. 第一次发布后的状态（S1） =====
editionId长度=31
-- version 行
verify-rollback-2026-10-07-d134@1.0.0	1.0.0
-- textbook 指针
verify-rollback-2026-10-07-d134	verify-rollback-2026-10-07-d134@1.0.0
-- 草稿修订与基线
draft-verify-rollback-2026-10-07-d134	3	verify-rollback-2026-10-07-d134@1.0.0
-- 目录世代
3	catalog-4
-- 该册审计行数与版本数
2	1
-- 幂等回执行数（该册发布作用域）
1

===== 3. 第二次发布（预期失败：审计表 from_version 放不下 37 字符） =====
HTTP 503
{"code":"TOOLBOX_008","messageKey":"service.unavailable","details":{}}
--- 本次失败在应用日志里的真实栈（节选） ---
ERROR ... GlobalExceptionHandler : unexpected request failure,
  path=/admin/v1/textbooks/verify-rollback-2026-10-07-d134/publish
org.springframework.dao.DataIntegrityViolationException: PreparedStatementCallback; SQL [INSERT INTO toolbox_curriculum_audit (... from_version ...)];
  Data truncation: Data too long for column 'from_version' at row 1
	at ...AdminTextbookRepository.recordAudit(AdminTextbookRepository.java:776)
	at ...AdminTextbookService.publishLocked(AdminTextbookService.java:564)
Caused by: com.mysql.cj.jdbc.exceptions.MysqlDataTruncation: Data truncation: Data too long for column 'from_version' at row 1
--- 事务内已推进到 catalog-3 的证据（回滚前快照重载日志） ---
catalogVersion=catalog-5, editions=3, entries=4        <-- 事务内已经把世代推到了下一个值

===== 4. 失败后的状态（S2）——必须与 S1 逐项一致 =====
editionId长度=31
-- version 行
verify-rollback-2026-10-07-d134@1.0.0	1.0.0
-- textbook 指针
verify-rollback-2026-10-07-d134	verify-rollback-2026-10-07-d134@1.0.0
-- 草稿修订与基线
draft-verify-rollback-2026-10-07-d134	3	verify-rollback-2026-10-07-d134@1.0.0
-- 目录世代
3	catalog-4
-- 该册审计行数与版本数
2	1
-- 幂等回执行数（该册发布作用域）
1

===== 5. 逐项比对 =====
比对文件：out/item2-state-before.txt out/item2-state-after.txt（逐行，13 行）
比对结果：**完全一致**（发布失败没有改变任何一项状态）
状态完全一致：发布失败没有留下任何痕迹（version 行 / current 指针 / 草稿基线 / 草稿修订 / 世代 / 审计 / 回执）
```

> **一处工具坑与更正（如实记录）**：第一次跑这项比对时用的是 PATH 上的 `diff`。
> 本机 `diff` 解析到 `/Applications/DevEco-Studio.app/.../openharmony/toolchains/diff`——
> 一个**精简实现**：不支持 `-u`/`-q`，而且**报 `illegal option` 时退出码仍是 0**，
> 于是 `if diff a b` 会把"根本没比较"当成"一致"。
> 现在改用 `scripts-remaining/31-item2-compare-states.sh`（python 逐行比对，退出码由 python 决定），
> 并对该比对工具做了**阴性对照**：把 `current_version_id` 人为改成 `…@1.0.1` 后，
> 比对必须报差异并退出 1（实测 `exit=1`，输出见
> `evidence/remaining-task3-31b-item2-compare-negative-control.log`）。
> 本节结论以上述更正后的比对为准；S1/S2 的原始状态输出也逐行列在上面的日志里，可人工复核。

七项对照（S1 = S2）：版本行数（1）、`current_version_id`（保持原值 `…@1.0.0`）、
草稿 `base_content_version`、草稿 `revision`（3）、目录 `epoch`/`catalog_version`（3/catalog-4）、
审计行数（2）、幂等回执行数（1）。**没有任何一项变化。**

---

### 4.3 ③ 唯一键：重复 `version_id` / `(edition_id, content_version)` 被拒

**结论：两条插入都被拒（ERROR 1062）✅，且没有留下探针行。**

原始输出（`evidence/remaining-task3-40-item3.log`）：

```
===== 当前表定义（主键与唯一键） =====
  PRIMARY KEY (`version_id`),
  UNIQUE KEY `uk_version_edition_content` (`edition_id`,`content_version`),

===== 探针前该表行数 =====
2

===== a. 重复 version_id（与既有 lab-a@1.0.0 相同） =====
ERROR 1062 (23000) at line 1: Duplicate entry 'lab-a@1.0.0' for key 'toolbox_textbook_version.PRIMARY'
mysql 退出码=1（非 0 表示插入被拒）
结论：被拒（ERROR 1062 Duplicate entry）

===== b. 同 (edition_id, content_version) 但不同 version_id（lab-a + 1.0.0） =====
ERROR 1062 (23000) at line 1: Duplicate entry 'lab-a-1.0.0' for key 'toolbox_textbook_version.uk_version_edition_content'
mysql 退出码=1（非 0 表示插入被拒）
结论：被拒（ERROR 1062 Duplicate entry）

===== 探针后该表行数（必须与探针前一致） =====
2
lab-a@1.0.0
lab-a@1.0.1
```

---

### 4.4 ④ 从导出包恢复到空库并逐项对账

**结论：包级摘要独立重算一致 ✅；恢复到空库后 48/48 项逐项一致 ✅；重复恢复幂等 ✅；
恢复后公开目录需要**一次重启**才会重建快照（已在文档中写明流程）。**

#### (a) 导出并**独立重算**包摘要

```
===== 3. 导出完整包 GET /admin/v1/curriculum/export-package =====
{"code":"OK","format":"toolbox-curriculum-package","versionCount":6,
 "packageSha256":"ee1ae7b4f8b509d99892f8ba65b77b1fe06a45ada77da1d3dd5539a3a85c720e"}

===== 4. 独立重算包级摘要与逐条哈希（只看包内字节，不采信接口自报） =====
OK lab-a@1.0.0 bytes=1470 sha=f6668c373dade85a
OK lab-a@1.0.1 bytes=1466 sha=29680ce4321affd4
OK restore-src-a@1.0.0 bytes=1153 sha=2a70518054dac759
OK restore-src-a@1.0.1 bytes=1651 sha=8edf9721511b0c6f
OK verify-rollback-2026-10-07-d134@1.0.0 bytes=1205 sha=9915e5fd2739b975
OK verify-rollback-2026-10-07-long@1.0.0 bytes=1205 sha=39607619e20ca953
独立重算 packageSha256=ee1ae7b4f8b509d99892f8ba65b77b1fe06a45ada77da1d3dd5539a3a85c720e
接口自报 packageSha256 =ee1ae7b4f8b509d99892f8ba65b77b1fe06a45ada77da1d3dd5539a3a85c720e
包级摘要一致：True
```

其中 `restore-src-a` 是**专门为本项造出来**的：带 3 条手工来源与**非空条目级 `sourceRefs`**
（1.0.0 有 1 条、1.0.1 有 2 条），否则"对账 sourceRefs"只是空对空。

#### (b) 恢复到空库

```
===== 0. 恢复前：目标库（toolbox_verify_b）必须为空 =====
0
0
current	0	catalog-0

===== 1. 登录实例 B 并 POST /admin/v1/curriculum/restore-package =====
HTTP 200
{"code":"OK","restoredEditions":4,"restoredVersions":6,"skippedVersions":0,"conflicts":[],"ok":true}
--- 服务端逐册对账报告 ---
{"editionId":"lab-a","currentVersionId":"lab-a@1.0.1","currentPointerRestored":true,"historyCount":2,
 "catalogVersionBaseline":"catalog-2026-10-05.7","contentSha256Matches":true,"sourceSnapshotMatches":true}
{"editionId":"restore-src-a","currentVersionId":"restore-src-a@1.0.1","currentPointerRestored":true,"historyCount":2,
 "catalogVersionBaseline":null,"contentSha256Matches":true,"sourceSnapshotMatches":true}
{"editionId":"verify-rollback-2026-10-07-d134",...,"historyCount":1,...}
{"editionId":"verify-rollback-2026-10-07-long",...,"historyCount":1,...}
```

> 契约提示：恢复接口的请求体是**导出包本身**（`data` 里的对象），不是 `ApiResponse` 信封。
> 本服务 `fail-on-unknown-properties=true`，把整个信封发过去会被 422 `TOOLBOX_001` 拒绝（已实际踩到）。

#### (c) 逐项对账：48/48 一致

对账口径全部由**包内字节**独立算出（python），与库里读出的值逐项比较（SQL）：
原 bytes 长度、原 bytes SHA-256、来源快照 SHA-256、声明元数据 SHA-256、
`entryLocators` 数、`sourceRefs` 条数、手工来源数、current 指针。

```
===== 4. 逐项对账（期望 vs 实际） =====
OK   lab-a@1.0.0                        bytes            期望=1470                       实际=1470
OK   lab-a@1.0.0                        content_sha256   期望=f6668c373dade85a186daaf7…  实际=f6668c373dade85a186daaf7…
OK   lab-a@1.0.0                        snapshot_sha256  期望=00d7795b5902133dcc3ccb06…  实际=00d7795b5902133dcc3ccb06…
OK   lab-a@1.0.0                        metadata_sha256  期望=ca5be3c61f4a2a41ad05e78a…  实际=ca5be3c61f4a2a41ad05e78a…
OK   lab-a@1.0.0                        locators         期望=2                          实际=2
OK   lab-a@1.0.0                        sourceRefs       期望=0                          实际=0
OK   lab-a@1.0.0                        manualSources    期望=0                          实际=0
OK   lab-a@1.0.0                        current          期望=0                          实际=0
... （6 个版本 × 8 项，共 48 行，全部 OK） ...
OK   restore-src-a@1.0.1                sourceRefs       期望=2                          实际=2
OK   restore-src-a@1.0.1                manualSources    期望=3                          实际=3
OK   restore-src-a@1.0.1                current          期望=1                          实际=1
OK   verify-rollback-2026-10-07-long@1.0.0  metadata_sha256  期望=35075a02bd89fb171b9bbbec…  实际=35075a02bd89fb171b9bbbec…
OK   verify-rollback-2026-10-07-long@1.0.0  current          期望=1                          实际=1

逐项对账结论：全部一致
```

历史版本数与 current 指针（每册）：

```
===== 5. 每册历史版本数与 current 指针 =====
lab-a	2	lab-a@1.0.1
restore-src-a	2	restore-src-a@1.0.1
verify-rollback-2026-10-07-d134	1	verify-rollback-2026-10-07-d134@1.0.0
verify-rollback-2026-10-07-long	1	verify-rollback-2026-10-07-long@1.0.0
--- 包中声明的每册版本数与 current ---
lab-a	2	lab-a@1.0.1
restore-src-a	2	restore-src-a@1.0.1
verify-rollback-2026-10-07-d134	1	verify-rollback-2026-10-07-d134@1.0.0
verify-rollback-2026-10-07-long	1	verify-rollback-2026-10-07-long@1.0.0
```

#### (d) 幂等复核 + 重启后对外可读

```
===== 7. 幂等复核：同一包再恢复一次，必须全部跳过且无冲突 =====
HTTP 200
{"code":"OK","restoredVersions":0,"skippedVersions":6,"conflicts":[],"ok":true}
6
```

```
===== 1. 重启后实例 B 的公开目录 =====
{"code":"OK","catalogVersion":"catalog-0","editionCount":4}
--- 目录行的值（恢复**不**改动 epoch/catalog_version，与恢复前一致） ---
current	0	catalog-0

===== 2. 逐册读回（内容来自恢复到空库的那一份） =====
lab-a@1.0.1 HTTP=200 包内sha=29680ce4321affd4 服务端sha=29680ce4321affd4 一致=yes
restore-src-a@1.0.1 HTTP=200 包内sha=8edf9721511b0c6f 服务端sha=8edf9721511b0c6f 一致=yes
verify-rollback-2026-10-07-d134@1.0.0 HTTP=200 包内sha=9915e5fd2739b975 服务端sha=9915e5fd2739b975 一致=yes
verify-rollback-2026-10-07-long@1.0.0 HTTP=200 包内sha=39607619e20ca953 服务端sha=39607619e20ca953 一致=yes
```

两个流程事实（**不是**缺陷，但必须写进演练步骤）：

1. 恢复**只写数据库**，不重建内存目录快照；空库恢复后必须**重启一次**服务，公开接口才能读到恢复内容
   （重启日志：`curriculum catalog reloaded from database, catalogVersion=catalog-0, editions=4, entries=6`）。
2. 恢复**不改** `epoch`/`catalog_version`（这是服务自身的既定设计：目录版本号只由真实发布推进）。
   包内快照记录的目录基线通过 `catalogVersionBaseline` 如实上报（本例 `catalog-2026-10-05.7`），
   但库里的目录行仍是恢复前的 `catalog-0`。

---

## 5. 全量测试

```bash
$ export DEVELOPER_DIR=/Library/Developer/CommandLineTools
$ cd /Users/pankang/mycode/MyTools/service/toolbox-service
$ mvn -o test
[INFO] Tests run: 403, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 基线：**395 通过 / 0 失败**
- 本次：**403 通过 / 0 失败**（+8，全部来自新增的 `AdminTextbookPublishAndPreviewScopeTest`）
- 原文：`evidence/remaining-10-full-suite-final.log`
- 针对性用例先跑（8/8）：`evidence/remaining-08-targeted-final.log`
- 过程中还跑过一次中间态全量 **402/0**（那时用例里还有一条后来被收紧的、依赖引擎的断言）：
  `evidence/remaining-05-full-suite-intermediate.log`。最终以 403/0 那一版为准。

关于"新发现缺陷"的暴露顺序（如实记录）：D1 是在把同一套代码接到真实 MySQL 后才发现的——
H2 上 `catalog_version` 恰好等于 `catalog-<epoch>`，MySQL 上却等于 `catalog-<epoch+1>`；
发现后我把那条断言收紧成引擎无关的写法，否则用例会在真实数据库上给出**错误的通过**。

---

## 6. 未达成项与本次新发现的缺陷（均**未**修改）

### D1（中）`catalog_version` 与 `epoch` 的拼装关系在 MySQL 与 H2 上不一致

`AdminTextbookRepository.storeVersion` 的推进语句：

```sql
UPDATE toolbox_curriculum_catalog
SET epoch = epoch + 1, catalog_version = CONCAT('catalog-', epoch + 1), updated_at = ?
WHERE catalog_key = 'current'
```

MySQL 的 `SET` 赋值**从左到右**求值：第二个表达式里的 `epoch` 已经是**更新后**的值，于是
`catalog_version = catalog-<新 epoch + 1>`。实测证据：

| 引擎 | 首次真实发布后 | 证据 |
| --- | --- | --- |
| 真实 MySQL 8.4 | `epoch=1`, `catalog_version=catalog-2` | `remaining-task3-20-item1.log` |
| H2（测试档） | `epoch=1`, `catalog_version=catalog-1` | `remaining-10-full-suite-final.log` 的 `catalogVersion=catalog-1..3` 日志行 |

MySQL 侧的同语义最小复现（临时表与用户变量都给出同一结果）：

```
CREATE TEMPORARY TABLE t (epoch BIGINT, catalog_version VARCHAR(32));
INSERT INTO t VALUES (0,'catalog-0');
UPDATE t SET epoch = epoch + 1, catalog_version = CONCAT('catalog-', epoch + 1);
SELECT new_epoch, new_catalog_version FROM t;
1	catalog-2
```

影响：设计第 391-392 行的示例里 `catalogVersion` 与 `catalogEpoch` 是同一个数字（`catalog-42` / `42`），
MySQL 上对不上；H2 用例给出的"一致"是**引擎特有的假象**。

**未修原因**：修法（`CONCAT('catalog-', epoch)`）会让已经按 `catalog-<epoch+1>` 写过的库
（例如 `toolbox_test` 现在就是 `epoch=3 / catalog-4`）在下一次发布产出**重名**的目录版本号，
需要一次数据迁移决策；这超出本次三项任务与"不改设计条款"的授权。建议单独立项，
并让用例断言只落在"生成了新版本名"与"与库列一致"上（本次已这样处理）。

### D2（高）审计表 `from_version`/`to_version` 只有 VARCHAR(32)

`toolbox_curriculum_audit.from_version` / `to_version` 是 `VARCHAR(32)`，而写进去的是
`versionId = editionId + "@" + contentVersion`，而 `edition_id` 允许 128 字符。后果：

- **同一册第二次发布**必然失败：`from_version = <editionId>@1.0.0`，`editionId` ≥ 26 字符即超长 → 503，
  事务回滚（这也正是 §4.2 的触发方式）。
- **迁移来的长标识教材连第一次发布都发不出去**：文件迁移会立刻把基线写成 versionId，
  管理员补全来源说明后（校验报告 `publishable=true`）发布仍是 503。实测（附加探针，`remaining-task3-75-extra-publish.log`）：

```
===== 3. 补全来源说明后发布（本探针的关键一步） =====
PUT draft：HTTP 200
校验报告（应 publishable=true）：
{"draftRevision":2,"publishable":true,"issues":[]}
发布 HTTP 503
{"code":"TOOLBOX_008","messageKey":"service.unavailable","details":{}}
--- 应用日志里的真实栈 ---
]; Data truncation: Data too long for column 'from_version' at row 1
	at ...AdminTextbookRepository.recordAudit(AdminTextbookRepository.java:776)
	at ...AdminTextbookService.publishLocked(AdminTextbookService.java:564)
```

**未修原因**：`V5__widen_draft_base_content_version.sql` 上一条线只加宽了 `base_content_version`，
加宽审计列需要**新的 V6 迁移**（改 schema）——超出本次"三项任务 + 不加依赖 + 不改设计条款"的授权范围，
且可能与正在改发布路径的其它线冲突。建议：新增 V6 把两列加宽到与 `version_id` 一致的 `VARCHAR(64)`（或 160），
并补一条真实 MySQL 用例。

### D3（高）发布失败回滚后，内存目录快照没有回退

`CurriculumPublisher.publishDraft` 在**外层事务提交之前**就调用 `reloadSnapshot(...)` 交换内存快照；
当同一事务稍后失败回滚时，数据库回滚了，**内存快照没有**。实测（`remaining-task3-30-item2.log` §6）：

```
===== 6. 【附加发现】失败发布在**内存目录快照**里留下的痕迹（数据库已回滚，但快照没有） =====
--- 库里到底有没有 1.0.1 ---
0
--- 公开接口读被回滚的 1.0.1 ---
GET /public/v1/textbooks/<edition>/versions/1.0.1 -> HTTP 200      <-- 幽灵版本
--- 公开目录此刻声明的目录版本号 vs 库中列 ---
{"servedCatalogVersion":"catalog-5","servedEditionVersion":"1.0.1"}
db: epoch=3 catalog_version=catalog-4
```

也就是说：失败发布之后，公开接口会**持续提供**一个数据库里并不存在的版本
（读取 1.0.0 反而可能 404），直到下一次成功发布或重启。

**未修原因**：正确的修法是把快照重建挪到**事务提交之后**（如 `TransactionSynchronization.afterCommit`），
或失败时回滚快照——这会改动发布主路径，属于本次三项任务之外的修改，且另一条线正在同一条路径上工作，
贸然改动会冲突。建议单独立项并补一条"提交后才交换快照"的用例。

### 其它

- 任务 2 **没有改前端**（按边界要求）。前端当前仍会整册预览并如实标注；需要的字段清单见 §7。
- 任务 3 的三个临时库（`toolbox_verify_a/b/c`）**保留**在容器里供人工核查；`10-setup.sh` 每次运行会重置它们。
  本次未删除任何容器/卷，未触碰 `toolbox_test` 与其它线的 `toolbox_roundtrip`。

---

## 7. 需要前端配合的字段清单（`toolbox-web/`，本次未改）

### 7.1 预览请求（新增**可选**参数，缺省行为不变）

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `nodeKind` | `unit` \| `lesson` | 与编辑器现有 `selectedNode.kind` 直接对应；`all` 时**不要**传 |
| `nodeKey` | `clientUnitId` / `clientLessonId`（或正式 `unitId` / `lessonId`） | 与 `selectedNode.id` 直接对应 |
| `draftRevision` | 数字 | 调用方在 flush 自动保存后拿到的修订号；服务端不符即 409 |

- `nodeKind` 与 `nodeKey` **必须成对**：只传一个 → 404 `previewScopeNotFound`（不会静默退回整册）。
- `nodeKey` 在草稿里找不到 → 404 `previewScopeNotFound`（不会返回空列表）。
- `draftRevision` 不传 = 不校验（与改造前一致）；传了且不符 → 409 `TOOLBOX_ADMIN_004` / `reason=revisionMismatch`。

建议的最小前端改动（`useAdminDraftPreview.generate`）：

```ts
const query = new URLSearchParams();
if (snapshot.nodeKind && snapshot.nodeKey) {
  query.set('nodeKind', snapshot.nodeKind);
  query.set('nodeKey', snapshot.nodeKey);
}
if (snapshot.revision != null) query.set('draftRevision', String(snapshot.revision));
const payload = await adminRequest<DraftPreviewPayload>(
  `/textbooks/${encodeURIComponent(editionId)}/draft/preview${query.size ? `?${query}` : ''}`
);
```

并据此把 `TextbookEditorView.vue` 里"仍按全册生成"的提示替换为真实范围标注
（`previewScope` 已经能算出范围文案；真实生效范围以响应 `scope.filtered` 为准）。

### 7.2 预览响应（新增字段）

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `draftRevision` | number | 本次预览**实际**依据的草稿修订号。与调用方 flush 后拿到的修订号不一致时，界面应提示"预览基于修订 N，当前为 M"，而不是标注成用户以为的那一版 |
| `scope` | object | `{ nodeKind: string \| null, nodeKey: string \| null, filtered: boolean }`；`filtered=false` 表示整册，此时必须**继续如实标注**"按全册生成" |

带范围时的**语义变化**（前端需要知道）：

- `units` / `lessons` / `entries` 均为**过滤后**内容：课文范围会带**父单元**；单元范围带该单元下全部课文与条目。
- `entries[].order` 保持**整册顺序号**（不重排），因此在选中范围里可能是**不连续**的（如 3、4）。这是刻意的：
  与发布后的正文顺序一致，便于对照。
- `edition.availableEntryCount` 是**范围内**条目数（与 `entries.length` 一致），不是整册条目数。

### 7.3 发布响应（新增字段，任务 1）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `catalogVersion` | string | 本次发布后 `toolbox_curriculum_catalog.catalog_version` 的库值，例如 `"catalog-2"` |
| `catalogEpoch` | **string** | 同表 `epoch` 的库值，设计示例为字符串 `"42"`；需要数值时请 `Number(...)` |

幂等重放时这两个字段来自**原回执**（不会因为再次请求而变化）——这是幂等语义，不是过期值。

---

## 8. 复现步骤（从零）

```bash
# 0) 前置：隔离 MySQL 容器在跑；本次临时库会被 10-setup.sh 重置
docker ps --filter name=toolbox-mysql-test --format '{{.Names}} {{.Image}} {{.Ports}}'

# 1) 目标代码与用例（H2）
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
cd /Users/pankang/mycode/MyTools/service/toolbox-service
mvn -o test -Dtest=AdminTextbookPublishAndPreviewScopeTest   # 8/8
mvn -o test                                                  # 403/0

# 2) 任务 3 环境与实例（脚本在 docs/verification/toolbox-admin-completion-2026-10-07/scripts-remaining/）
bash scripts-remaining/10-setup.sh                       # 重置 a/b、造迁移包
export TOOLBOX_DB_PASSWORD=toolbox-test-only
export TOOLBOX_DB_NAME=toolbox_verify_a TOOLBOX_PORT=23211
export TOOLBOX_CATALOG_PATH=/tmp/toolbox-remaining/task3/content
mvn -o spring-boot:run -Dspring-boot.run.profiles=local,local-admin \
  -Dspring-boot.run.additional-classpath-elements="$PWD/target/test-classes"   # 实例 A（后台）

# 3) 逐条跑任务 3
bash scripts-remaining/20-item1-migration.sh     # ① 迁移保留 + 首次发布
bash scripts-remaining/40-item3-unique-keys.sh   # ③ 唯一键
bash scripts-remaining/30-item2-rollback.sh      # ② 事务回滚 + D3 附加发现
bash scripts-remaining/50-item4-export.sh        # ④ 导出并独立重算包摘要

# 4) 实例 B（空库，端口 23212，库 toolbox_verify_b）后：
bash scripts-remaining/55-item4-restore.sh       # 恢复 + 48 项逐项对账 + 幂等复核
# 重启实例 B 后：
bash scripts-remaining/60-item4-after-restart.sh # 公开接口读回恢复内容
```

> 脚本里的端口/库名都可以用环境变量覆盖（`BASE` / `DB`）。`stop-instance.sh <PORT> <期望库名>`
> 会先核对占用者的 `TOOLBOX_DB_NAME` 再停止，避免误杀其它验收线的实例。

---

## 9. 证据索引（`evidence/`）

| 文件 | 内容 |
| --- | --- |
| `remaining-01-targeted-new-tests.log` | 新增用例首跑（7/7，含当时未收紧的那条断言） |
| `remaining-02-reverse-task1-fields-removed.log` | **反向验证 1**：撤掉两个发布字段 → 1 失败 + 1 错误 |
| `remaining-03-reverse-task2-filter-removed.log` | **反向验证 2**：撤掉范围过滤 → 4 跑 3 败（整册那条通过） |
| `remaining-04-targeted-restored.log` | 还原后 7/7 |
| `remaining-05-full-suite-intermediate.log` | 中间态全量：**402 通过 / 0 失败**（收紧断言之前的那一版用例） |
| `remaining-06-targeted-after-engine-fix.log` | 收紧为引擎无关断言后 7/7 |
| `remaining-08-targeted-final.log` | 最终针对性 8/8 |
| `remaining-09-reverse-task2-revision-check-removed.log` | **反向验证 3**：撤掉修订号校验 → 期望 409 实得 200 |
| `remaining-10-full-suite-final.log` | **最终全量：403 通过 / 0 失败** |
| `remaining-task3-10-setup.log` | 容器/版本/sql_mode、重置临时库、逐字节取真实已发布字节、拼清单 |
| `remaining-task3-20-item1.log` | ① 迁移保留 + 首次真实发布（含响应字段与库列对照） |
| `remaining-task3-30-item2.log` | ② 事务回滚 S1/S2 七项对照 + D3 附加发现 |
| `remaining-task3-31-item2-state-compare.log` | ② S1/S2 **更正后**的 python 逐行比对（13 行完全一致） |
| `remaining-task3-31b-item2-compare-negative-control.log` | ② 比对工具的**阴性对照**（人为改一个值必须报差异、退出 1） |
| `remaining-task3-40-item3.log` | ③ 两个唯一键的 ERROR 1062 原始输出 |
| `remaining-task3-50-item4-export.log` | ④ 导出 + 独立重算包摘要 + `sourceRefs` 期望值 |
| `remaining-task3-55-item4-restore.log` | ④ 恢复到空库 + 48 项逐项对账 + 幂等复核 |
| `remaining-task3-60-item4-after-restart.log` | ④ 重启后公开接口读回（4 册哈希逐一核对） |
| `remaining-task3-70-extra-content.log` / `-75-extra-publish.log` | 附加探针：迁移长标识教材发布失败（D2 适用范围） |
| `remaining-task3-item4-expected.tsv` / `-actual.tsv` | 48 项对账的两侧原始数据（对比时请用 python/`cmp`，本机 `diff` 不可靠，见 §4.2 的工具坑说明） |
