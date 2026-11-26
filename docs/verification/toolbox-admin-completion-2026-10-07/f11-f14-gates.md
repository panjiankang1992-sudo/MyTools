# F11（files 模式发布闸门）与 F14（manifest.catalogVersion 保真）——后端改动证据

本轮任务的两项后端小改动，各自都有**能在旧实现上真实失败的回归测试**与**反向验证**。
所有命令都在仓库根目录 `/Users/pankang/mycode/MyTools` 下执行，模块固定为
`service/toolbox-service`。

> **口径说明（诚实优先）**：本文件里的通过数、失败输出都是**实际粘贴**的。
> 有两个环境事实必须先说清楚，否则读者会误判某些中间输出：
> 1. **本会话期间有并行的子代理在同一个模块里改代码并跑 `mvn test`**。同一时刻两个
>    Maven 进程共用 `target/`，会让结果变成假信号（本轮见过一次"395 跑出 12 失败"，
>    重跑即恢复全绿；见 §4.3）。
> 2. **`git` 在本机不可用**（`/usr/bin/git` 报 Xcode 许可未同意，退出码 69，连
>    `git status` 都失败），因此无法给 `git diff`。文件级状态用 **md5 + mtime** 记录。

---

## 1. 改动文件清单

### 1.1 生产代码（7 个文件）

| 文件 | 改动 |
| --- | --- |
| `common/ErrorCode.java` | 新增 **`TOOLBOX_ADMIN_011(409, "admin.publish.disabled")`**（中文 Javadoc：目录来源为只读文件时禁止发布） |
| `service/admin/curriculum/AdminTextbookService.java` | `publish(...)` **最前面**调用新增私有方法 `requireWritableRepository()`：`repository != database` 时抛 `BusinessException(TOOLBOX_ADMIN_011, {reason: repositoryIsFiles})` |
| `service/admin/curriculum/CurriculumPublisher.java` | 构造器新增 `CurriculumProperties`；`publish(...)` 与 `publishDraft(...)` 各自在最前面调用 `requireWritableRepository()`（写公开目录的唯一入口，纵深防御）；两处 `reloadSnapshot("catalog-" + currentEpoch())` 改为 `reloadSnapshot(repository.currentCatalogVersion())` |
| `service/admin/curriculum/CurriculumSnapshotLoader.java` | `currentCatalogVersion()` 改为**读库中 `catalog_version` 列**，不再用 `"catalog-" + epoch` 现算 |
| `mapper/admin/AdminTextbookRepository.java` | 新增 `currentCatalogVersion()`（读列，值缺失时按旧逻辑回退）；新增 `installInitialCatalogVersion(String)`（**仅在 `epoch = 0`** 时写入清单版本号）；新增 `importVersion(...)` 与私有 `storeVersion(..., boolean advanceCatalog)`：把"是否推进目录世代"抽成显式参数 |
| `service/admin/migration/CurriculumFileImporter.java` | 迁移导入从 `repository.publishVersion(...)` 改为 `repository.importVersion(...)`（**不推进 epoch / catalog_version**） |
| `service/admin/migration/AdminCurriculumImportService.java` | 注入 `AdminTextbookRepository`；整批导入无冲突（`report.ok()`）且清单声明了 `catalogVersion` 时调用 `installInitialCatalogVersion(...)`，并记录"已发布过则保持库值"的日志 |

### 1.2 新增测试（3 个类 / 6 个用例）

| 文件 | 用例 |
| --- | --- |
| `src/test/java/.../FilesRepositoryPublishBlockedTest.java` | ① files 模式发布被拒（断言错误码 / HTTP 409 / messageKey / `reason`，并断言**没有写入任何版本行、当前指针未前移**）② files 模式保存草稿仍然成功 |
| `src/test/java/.../DatabaseRepositoryPublishAllowedTest.java` | `repository=database`（测试专用 properties 覆盖）下发布成功、`snapshotReloaded=true`、当前指针指向新版本 |
| `src/test/java/.../CurriculumCatalogVersionMigrationTest.java` | ① 迁移后库中 `catalogVersion` 仍是清单里的 `catalog-7`，且 `epoch` 独立保持 0 ② 公开目录接口（HTTP `GET /public/v1/textbooks`）读到的 `catalogVersion` 与库一致 ③ 迁移后**首次真实发布**才生成新的 `catalog-N`，公开目录与库一致 |

### 1.3 既有测试的配套调整（7 个文件，逐条理由）

| 文件 | 调整 | 为什么这不是"放宽校验" |
| --- | --- | --- |
| `AdminTextbookApiTest` | 加 `properties = "toolbox.curriculum.repository=database"` + 独立 H2 库 | 该类的断言是"**发布成功**"。新不变量下发布只在 database 模式合法，所以它必须在 database 模式下跑；files 模式下"发布被拒"由 `FilesRepositoryPublishBlockedTest` 负责 |
| `CurriculumExportManifestTest` | 同上 | 要先发布才有历史可导，同上 |
| `CurriculumPublisherTest` | 同上 | 直接调用 `publishDraft`，同上 |
| `PublishSnapshotReloadFailureTest` | 同上 | 该用例验证"重载失败要如实报错"，必须能走到发布器；files 模式下会在闸门处就被拒 |
| `AdminTextbookRepositoryTest` | 加独立 H2 库 | 该类**刻意写"故意非法"的数据层夹具**（元数据 `{}`、哈希 `sha-1`）。这类行留在共享库里，会让 database 模式的上下文在**启动重建快照时按设计拒绝启动**（`stored contentSha256 mismatch`）。隔离后仍然照旧写非法夹具、照旧断言数据层约束 |
| `PublishCurrentVersionCasTest` | 加独立 H2 库 | 同上（写 `{}` 元数据的版本行验证 CAS） |
| `ErrorEnvelopeTest` | `hasSize(22)` → `hasSize(23)`，并补 `TOOLBOX_ADMIN_011` 的状态（409）与 messageKey 断言 | 这是**契约表**的变动检测。新增错误码就必须把它登记进契约表；同时补的是**显式断言**而不是删断言，严格性不变 |

> 新增错误码后前端未同步：`toolbox-web/src/services/admin-api.ts` 里没有任何 admin 错误码映射表
> （只有注释提到 `TOOLBOX_ADMIN_010`），所以前端不会有编译期/类型期遗漏，只会走通用错误处理。
> 按任务边界**没有改前端**。

---

## 2. 任务 1：files 模式发布闸门（F11）

### 2.1 闸门语义

- 触发条件：`CurriculumProperties.usesDatabase() == false`，即 `toolbox.curriculum.repository`
  既不是 `database`（默认 `files`，拼错也落到这里）。
- 错误：`TOOLBOX_ADMIN_011` / HTTP **409** / `messageKey = admin.publish.disabled` /
  `details.reason = repositoryIsFiles`。**明确、可区分**，不是日志、不是笼统 500。
- 位置：`AdminTextbookService.publish` 的**第一行**（在任何写操作之前：写回 ID 映射、推进修订号、
  递增世代都算"已经发生了变更"），并且在 `CurriculumPublisher.publish` / `publishDraft` 里再挡一次。
- **发布之外一律不受影响**：`create` / `saveDraft` / `validate` / `preview` 都不经过闸门。

### 2.2 通过证据

命令：

```bash
mvn -f service/toolbox-service/pom.xml test \
  -Dtest='FilesRepositoryPublishBlockedTest,DatabaseRepositoryPublishAllowedTest,CurriculumCatalogVersionMigrationTest'
```

实际输出（`evidence/f11-f14-targeted-new-tests.log`）：

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.181 s -- in com.yuyutian.mytools.toolbox.FilesRepositoryPublishBlockedTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.243 s -- in com.yuyutian.mytools.toolbox.DatabaseRepositoryPublishAllowedTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.890 s -- in com.yuyutian.mytools.toolbox.CurriculumCatalogVersionMigrationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

> 第一条用例里还有一个**前提断言**：草稿本身必须通过发布级校验
> （`textbooks.validate(...).report().publishable() == true`）。
> 这样"被拒绝"才只能归因于来源模式，而不是"校验没过"这种与闸门无关的原因。

### 2.3 反向验证（去掉闸门条件 → 必须失败）

改动（两处同形，`AdminTextbookService` 与 `CurriculumPublisher`）：

```java
// 原：if (!curriculumProperties.usesDatabase()) {
// 反向验证时改成：
if (false && !curriculumProperties.usesDatabase()) { // REVERSE-VERIFY-TEMP
```

命令与实际输出（`evidence/f11-reverse-gate-removed.log`）：

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.934 s <<< FAILURE! -- in com.yuyutian.mytools.toolbox.FilesRepositoryPublishBlockedTest
[ERROR] com.yuyutian.mytools.toolbox.FilesRepositoryPublishBlockedTest.publishIsRejectedWhenRepositoryIsFiles -- Time elapsed: 0.034 s <<< FAILURE!
java.lang.AssertionError:
Expecting code to raise a throwable.
    at com.yuyutian.mytools.toolbox.FilesRepositoryPublishBlockedTest.publishIsRejectedWhenRepositoryIsFiles(FilesRepositoryPublishBlockedTest.java:96)
[ERROR]   FilesRepositoryPublishBlockedTest.publishIsRejectedWhenRepositoryIsFiles:96
Expecting code to raise a throwable.
[INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
```

失败语义正是审查 F11 描述的那个静默失败：**发布"成功"返回、什么都没抛**。
同一轮里"保存草稿仍然成功"那条**依然通过**——说明闸门的确只挡发布。
随后两处条件已还原（`grep REVERSE-VERIFY-TEMP` 无结果）。

---

## 3. 任务 2：初始迁移保留 manifest.catalogVersion（F14）

### 3.1 先勘察耦合点（这一步改变了最小改法）

任务要求"先读代码确认 epoch 与 catalogVersion 的现有耦合点，再决定最小改法"。实测结论：

1. **公开目录的 ETag 与 `catalogVersion` 无关**。
   `CurriculumController:94` 是 `String etag = "\"" + catalog.snapshotVersion() + "\""`，
   即 `CurriculumCatalog` 里那个**每次成功 `installSnapshot` 都自增的内存计数器**。
   所以"把 catalogVersion 从 epoch 派生改成读列"**不可能**让 ETag 失效；
   发布后 ETag 仍然会变（既有 `AdminTextbookApiTest` 的 ETag 用例本轮全绿）。
2. `catalogVersion` 由 epoch 现算的地方一共只有 3 处：
   `CurriculumSnapshotLoader:60`、`CurriculumPublisher:91`、`CurriculumPublisher:143` → 全部改为读库中的列。
3. **关键发现（也是第一版改法失败的地方）**：迁移路径
   `AdminCurriculumImportService → CurriculumFileImporter.importEdition → repository.publishVersion`
   复用的正是发布用的那条 SQL：

   ```sql
   UPDATE toolbox_curriculum_catalog
      SET epoch = epoch + 1, catalog_version = CONCAT('catalog-', epoch + 1), updated_at = ?
    WHERE catalog_key = 'current'
   ```

   也就是说**"导入既有内容"本身就被当成了一次真实发布**：清单的 `catalogVersion` 还没写进去，
   就被计数器改成了 `catalog-1`。所以"迁移不推进世代"不能只靠在导入之后补写一个字段，
   必须让迁移走一条**不推进世代的写入路径**。

### 3.2 最小改法

- `AdminTextbookRepository`：把发布写入抽成私有
  `storeVersion(..., boolean advanceCatalog)`；`publishVersion(...)` 传 `true`（行为不变），
  新增 `importVersion(...)` 传 `false`。版本行、当前指针、草稿基线与真实发布**完全一致**，
  唯一差别是"是否推进 epoch / catalog_version"。
- `CurriculumFileImporter`：迁移改用 `importVersion(...)`。→ 迁移后 `epoch` 保持 **0**。
- `AdminCurriculumImportService`：整批无冲突时 `installInitialCatalogVersion(manifest.catalogVersion())`；
  该方法带 `AND epoch = 0` 守卫：
  - 迁移重跑：`epoch` 仍是 0 → 幂等重写同一个值；
  - 已经真实发布过：`epoch > 0` → 返回 0 行，**保持发布生成的新版本号不倒退**（并打日志）。
- 发布路径的 SQL 一字未改 → 正常发布仍然生成新的 `catalog-N`。

> **`epoch` 独立初始化的直接推论（预期行为，不是缺陷）**：迁移包是 `catalog-7` 时，
> 迁移后库中 `catalogVersion = catalog-7`、`epoch = 0`；**首次真实发布**产出 `epoch = 1`、
> `catalogVersion = catalog-1`。数值上"新版本号小于迁移包版本号"是设计第 421 行
> "epoch 独立初始化"的必然结果——`epoch` 不从迁移包派生。

### 3.3 通过证据

见 §2.2 的同一个命令输出：`CurriculumCatalogVersionMigrationTest` **3/3 通过**，其中：

- 用例①断言 `repository.currentCatalogVersion() == "catalog-7"`、`currentEpoch() == 0`
  （并直接查库断言 `epoch = 0`）；
- 用例②断言 HTTP `GET /public/v1/textbooks` 返回的 `data.catalogVersion` 既等于 `catalog-7`
  又等于库值；
- 用例③断言首次真实发布后 `epoch == 1`、`catalogVersion == "catalog-1"`（且 `!= "catalog-7"`），
  公开接口读到的值与之相等。

### 3.4 反向验证（撤销保留逻辑 → 第一条用例必须失败）

改动：把 `AdminCurriculumImportService` 里的

```java
if (report.ok() && manifest.catalogVersion() != null && !manifest.catalogVersion().isBlank()) {
```

反向验证时改成 `if (false && report.ok() && ...)`（即不再认领清单版本号）。

命令与实际输出（`evidence/f14-reverse-preserve-removed.log`）：

```
[ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 2.432 s <<< FAILURE! -- in com.yuyutian.mytools.toolbox.CurriculumCatalogVersionMigrationTest
expected: "catalog-7"
 but was: "catalog-0"
[ERROR]   CurriculumCatalogVersionMigrationTest.migrationPreservesManifestCatalogVersion:153 [迁移必须保留清单声明的目录版本号]
expected: "catalog-7"
 but was: "catalog-0"
[ERROR]   CurriculumCatalogVersionMigrationTest.publicCatalogServesMigratedCatalogVersion:175 [公开目录必须报出库里保存的版本号，而不是现算的 catalog-0]
expected: "catalog-7"
 but was: "catalog-0"
```

（第 3 条用例仍通过——发布路径没有被"保留行为"污染，反向印证了"不能泄漏到发布路径"。）

**另一次真实失败（修好 `importVersion` 之前，实际观察到的）**：迁移仍在推进世代时，
`CurriculumCatalogVersionMigrationTest` 的第一条用例拿到的是 `catalog-1`
（`expected: "catalog-7" but was: "catalog-1"`），并且 `AdminCurriculumImportService`
打出了 `curriculum catalog version already advanced by publish, keep database value, manifest=catalog-7`。
这条日志正是"迁移被误当成一次发布"的直接证据，也是把它改成 `importVersion` 的原因。

随后条件已还原。

---

## 4. 测试与回归

### 4.1 基线（改动前实测）

```bash
mvn -f service/toolbox-service/pom.xml test
# [INFO] Tests run: 381, Failures: 0, Errors: 0, Skipped: 0
# [INFO] BUILD SUCCESS   （EXIT=0）
```

完整日志：`evidence/f11-f14-baseline-full-381.log`。

### 4.2 改动后全量

```bash
mvn -f service/toolbox-service/pom.xml test
# [INFO] Tests run: 395, Failures: 0, Errors: 0, Skipped: 0
# [INFO] BUILD SUCCESS   （EXIT=0, 2026-10-07 01:39:07 +08:00）
```

完整日志：`evidence/f11-f14-full-after-395.log`（其中我新增的 3 个类分别 3/1/2 全通过）。
395 = 381 基线 + 我新增 6 条 + 并行线新增 8 条。

### 4.3 一次假信号（如实记录）

01:28 的那次全量跑出 **12 失败**（`EventIngestApiTest` 6、`PublicCurriculumApiReconciliationTest` 2、
`SourceSnapshotBuilderTest` 3、`SourceSnapshotRoundTripTest` 1）。原因是**并行的子代理在同一时刻
跑 `mvn -o -Dtest=SourceSnapshotBuilderTest,SourceSnapshotRoundTripTest test`**，两个 Maven 共用
`target/` 边编译边跑；失败表现（公开目录 0 条、事件接口 503）与代码语义无关。
等它结束后重跑（§4.2）恢复 **395 / 0 / 0**。**唯一可信口径是 §4.2 的那次。**

---

## 5. 未达成项与观察（不藏）

1. **管理端发布响应仍然不返回 `catalogVersion` / `catalogEpoch`**（设计 391-392 行的响应体里两者并存）。
   本轮**没有**加：任务只要求"迁移保真 + 发布生成新版本"，而给 `PublishResult`
   加字段属于**改公开行为**（管理端响应契约）。这是本轮明确未做的一项。
2. **只有 H2 证据，没有真实 MySQL 证据**：`catalog_version` 的保留/回写只在 H2(MySQL 模式) 下验证过。
   列类型是 `VARCHAR(64)`，清单里的版本号长度（如 `2026-10-05.1`）远小于上限，预期无风险，但**未实测 MySQL**。
3. **前端未动**（任务边界）：`toolbox-web` 没有 admin 错误码映射表，新错误码 `TOOLBOX_ADMIN_011`
   在前端只会落到通用错误处理；若要求按码给中文提示，需要另一条改动。
4. **`toolbox_textbook_draft.base_content_version VARCHAR(32)` 的列宽不足**：我自己写用例时撞到过
   （`editionId` 27 字符 → 发布器写入 `editionId@contentVersion` 34 字符 → H2 直接
   `Value too long for column`）。这是与 F11/F14 无关的既有缺陷，已由并行线修复
   （见同目录 `fix-draft-base-content-version-width.md`）；我把用例的 `editionId` 缩短以避开它，
   **没有**改这条列定义。
5. **`git` 不可用**（Apple git 需要 Xcode 许可，退出码 69），因此无法提供 `git status/diff`；
   文件状态用下面的 md5/mtime 记录。同时**工作区里其他人的改动全部保留**
   （并行代理对 `AdminTextbookRepository` / `AdminTextbookService` / `CurriculumFileImporter`
   等同一批文件的改动没有被我覆盖，全量测试在其之上通过）。

### 5.1 本轮我改动的文件状态（2026-10-07 01:39 附近）

```
md5                                mtime               文件
94272a5c191cfc8607fda6fbd3111e78   01:19:39  common/ErrorCode.java
03a93c2fd508292090e0df46a265468a   01:29:45  service/admin/curriculum/AdminTextbookService.java
b12ad3a8bc2321661942820e552984ad   01:28:48  service/admin/curriculum/CurriculumPublisher.java
187f3f4bf0b5eebe4125be3c8001e5ab   01:20:29  service/admin/curriculum/CurriculumSnapshotLoader.java
436160e7cb249e7a435aa6997ee280bc   01:28:15  mapper/admin/AdminTextbookRepository.java
1ba878ad36a444c532866a1721221580   01:25:38  service/admin/migration/CurriculumFileImporter.java
193bcd4212d728112fe5e4f3590b4b88   01:24:26  service/admin/migration/AdminCurriculumImportService.java
```

---

## 6. 对验收矩阵相关行的建议状态

（本目录的 `status-matrix.json` / `status-matrix.md` 由并行线维护，**我没有改动它们**，只给建议。）

| 行 | 现状 | 建议 | 依据 |
| --- | --- | --- | --- |
| `C02` 发布后目录生效 | 通过 | 建议备注补充：**发布生效只在 `repository=database` 下成立**；`files` 模式下发布现在被 `TOOLBOX_ADMIN_011` 明确拒绝 | `FilesRepositoryPublishBlockedTest`、`DatabaseRepositoryPublishAllowedTest` |
| `C08` 目录 epoch 一致性 | 部分（备注：本轮新增 catalogVersion 迁移保真测试） | 建议**升为通过**（若"一致性"的判据就是"公开目录读到的 `catalogVersion` 与库一致 + 发布后按 epoch 前进"）：公开接口读到的版本号与库一致、发布后 `epoch+1` 且 `catalog-N` 同步、ETag（`snapshotVersion`）发布后必变 | `CurriculumCatalogVersionMigrationTest` ②③；`AdminTextbookApiTest` 的目录 ETag 用例 |
| `D01` 迁移保真 | 部分（备注：catalog_version 列回写见并行线） | 建议把备注里的"catalog_version 列回写"标记为**本轮已闭环**；该行整体仍为**部分**（来源快照/文件 hash 等子项不属本任务） | `CurriculumCatalogVersionMigrationTest` ① + 反向验证 `f14-reverse-preserve-removed.log` |
