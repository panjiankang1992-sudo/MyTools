# 真实教材无损往返验收（B10 / D01 / C03）

- 日期：2026-10-07
- 样本：内容包 `toolbox-web/content/` 里**已在生产目录公开**的三册 —— `cn-ty-2-1-pep-2017@1.0.2`（760 条）、
  `cn-ty-3-1-pep-2024@1.0.0`（777 条）、`en-yl-3-1-yilin-2024@1.0.1`（140 条）；
  迁移阶段把清单里**全部 5 册**（另含 318 条与 640 条两册）一起导入，没有缩小样本。
- 链路：`import-from-files`（迁移）→ 改文本 / 调 `listTypes` / 增删条目（编辑）→ 保存草稿 → 发布 → **逐字段对账**。
- 边界：全程只访问回环地址（`127.0.0.1:23201`、`127.0.0.1:13316`）。**未访问生产**、未部署、
  未在生产建账号或发布教材。未删除/重建 `toolbox-mysql-test` 容器，未触碰其它容器与卷。

---

## 0. 结论一览

| 项 | 结论 |
| --- | --- |
| 迁移（5 册） | ✅ 全部 `INSERTED`；磁盘字节 = 清单声明哈希 = 库里哈希 = 下载回来的字节（逐册实测） |
| `manifest.catalogVersion` 是否被认领 | ✅ **空库（epoch=0）会认领**：`catalog_version` 从 `catalog-0` 变成清单的 `2026-10-04.1`；已发布过（epoch>0）时**不认领**，保留发布派生的 `catalog-N` |
| 编辑 → 保存 → 发布 | ✅ 三册全部发布成功（`cn-ty-3-1-pep-2024` 需先修正内容包里损坏的 `editionLabel`，见 §7.7） |
| **逐字段无损往返** | ❌ **未达成**。三册"必需字段全同"的条目数为 `758 / 775 / 138`；但除有意编辑外，还存在**系统性字段变化**与**教材级字段真丢失**（见 §6、§7） |
| 未改动条目"逐一相同" | ⚠️ 在**要求的比对字段集**（entryId/正文/拼音或 meaning/listTypes/sourceRefs）上成立；在**全 20 字段**上不成立（`order`、`reviewStatus`、`entryVersion` 每条都变） |
| `preserved` 反向检查 | ✅ 在真实 760/777/140 上成立：`sourceRefs`（含 `page`）760/760、777/777、140/140 全等；`senseId` 全等；`context` 18/18；`acceptableAnswers` 4/4。前端 `pairsToEntries` 另有 2635 条真实数据的往返 + 变异对照 |
| 未达成项 | `regionCodes` / `isbn` / `missingScopes` 丢失（**有用户可见后果**）；`editionLabel` 含 U+FFFD 导致 777 册首次发布被拒 |

---

## 1. 环境与可重跑方式

跑在**冻结副本**上（工作树当时被其它线并发修改，冻结是为了让哈希与运行代码一一对应）：

```bash
# 1) 隔离库（同一个 toolbox-mysql-test 容器，独立 database）
docker exec toolbox-mysql-test mysql -uroot -ptoolbox-test-only \
  -e "CREATE DATABASE IF NOT EXISTS toolbox_roundtrip CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 2) 冻结源码副本并编译
DEST=/tmp/toolbox-roundtrip-20261007/svc
mkdir -p "$DEST"
rsync -a --exclude target /Users/pankang/mycode/MyTools/service/toolbox-service/ "$DEST/"
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
cd "$DEST" && mvn -o -q test-compile -DskipTests

# 3) 起一个**独立**后端实例（不占用别人在跑的 23199）
export TOOLBOX_DB_PASSWORD=toolbox-test-only TOOLBOX_DB_NAME=toolbox_roundtrip TOOLBOX_PORT=23201
export TOOLBOX_CATALOG_PATH=/Users/pankang/mycode/MyTools/toolbox-web/content
mvn -o spring-boot:run -Dspring-boot.run.profiles=local,local-admin \
  -Dspring-boot.run.additional-classpath-elements="$PWD/target/test-classes"

# 4) 对账脚本（env → import → publish → repro-blocked → snapshots → compare，可重复执行）
cd /Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07/evidence
ROUNDTRIP_SERVICE_ROOT=/tmp/toolbox-roundtrip-20261007/svc python3 roundtrip-real-books.py all

# 5) 前端 pairsToEntries 往返检查（在 toolbox-web/ 下）
cd /Users/pankang/mycode/MyTools/toolbox-web
node --import tsx scripts/verify-preserved-roundtrip.mjs <draft.json> ...
```

脚本与日志：

| 文件 | 作用 |
| --- | --- |
| `evidence/roundtrip-real-books.py` | 迁移 / 编辑发布 / 快照 / 逐字段对账的**唯一驱动**，输出 JSON |
| `toolbox-web/scripts/verify-preserved-roundtrip.mjs` | 用真实草稿验证 `pairsToEntries` 不丢 `preserved`（调用产品代码，不重实现） |
| `evidence/roundtrip-run.log` | `all` 阶段的完整原始输出（可重复执行，重跑幂等） |
| `evidence/roundtrip-preserved-frontend.log` | 前端往返检查 + 变异对照的原始输出 |
| `evidence/roundtrip-env.json` | 运行环境 + 参与验收的 9 个源文件 SHA-256 |
| `evidence/roundtrip-source-drift.json` | 冻结副本 vs 实时工作树的哈希对照（**9/9 相同，无漂移**） |

后端实例：`127.0.0.1:23201`（本轮自建，与别人在跑的 `23199` 互不影响），库 `toolbox_roundtrip`；
冻结副本 `/tmp/toolbox-roundtrip-20261007/svc`。另有一次「原始草稿导出」用了
`toolbox_roundtrip_draft` 库 + `23202` 实例（验证完已停止）。

---

## 2. 迁移报告（`import-from-files`）

HTTP 200，`ok=true`，5 册全部 `INSERTED`（无哈希冲突）：

```
cn-ty-1-1-pep-2024@1.0.2 INSERTED  20017f83d008c9942ff1bf52dc962bda43db49165ff8881cdd8500a753844527
cn-ty-2-1-pep-2017@1.0.2 INSERTED  1d14e1fd6d3c819dfa5a74b00de26d9476e04e7f9d5e5780a97dff71914f8778
cn-ty-3-1-pep-2024@1.0.0 INSERTED  49abe27d8b49e50b99af599726d0374b13071b47f4447538fcaab65b74fd7cdf
cn-ty-4-1-pep-2024@1.0.0 INSERTED  ac988e6772a39464921fae77581faa484e407cad10ceffa88cc5a908d720d295
en-yl-3-1-yilin-2024@1.0.1 INSERTED aed31475025e503ca5f40fb2b6f70bc4c73a0ef4b647b03d0545fdab2bf479d4
```

点名的三册逐项核对（`evidence/roundtrip-migration-clean-db.json`）：

| editionId | contentVersion | 条目数（文件/清单） | 原始 bytes | SHA-256 | 磁盘=清单=库=下载 |
| --- | --- | --- | --- | --- | --- |
| cn-ty-2-1-pep-2017 | 1.0.2 | 760 / 760 | 693,200 | `1d14e1fd…f8778` | ✅ / ✅ / ✅ |
| cn-ty-3-1-pep-2024 | 1.0.0 | 777 / 777 | 716,239 | `49abe27d…7cdf` | ✅ / ✅ / ✅ |
| en-yl-3-1-yilin-2024 | 1.0.1 | 140 / 140 | 117,917 | `aed31475…79d4` | ✅ / ✅ / ✅ |
| （对照）cn-ty-1-1-pep-2024 | 1.0.2 | 318 / 318 | 289,494 | `20017f83…4527` | ✅ / ✅ / ✅ |
| （对照）cn-ty-4-1-pep-2024 | 1.0.0 | 640 / 640 | 595,122 | `ac988e67…d295` | ✅ / ✅ / ✅ |

版本行（`toolbox_textbook_version`）示例：

```
cn-ty-2-1-pep-2017@1.0.2  1d14e1fd…f8778  693200  local-admin-1001
cn-ty-3-1-pep-2024@1.0.0  49abe27d…7cdf   716239  local-admin-1001
en-yl-3-1-yilin-2024@1.0.1 aed31475…79d4  117917  local-admin-1001
```

### 2.1 `manifest.catalogVersion` 是否被认领

清单声明的 `catalogVersion = 2026-10-04.1`（`catalog/published.json`）。

| 场景 | 观察 |
| --- | --- |
| 空库（`epoch=0`）导入 | ✅ **认领**：`toolbox_curriculum_catalog` 由 `current,0,catalog-0` 变为 `current,0,2026-10-04.1` |
| 导入完成后再导入一次 | 5 册全部 `SKIPPED_IDENTICAL`（不覆盖），目录版本不变 |
| 首次发布之后（`epoch=4`）再导入 | ❌ **不认领**：保持发布派生的 `current,4,catalog-5`（`installInitialCatalogVersion` 的 `WHERE epoch=0` 不匹配） |

与另一条线在 `toolbox_test` 上的观察一致（那里 `epoch=3`、`catalog_version=catalog-4`，因此也不会认领）。
**准确表述**：认领只在"这个库还没有发生过真实发布"时成立；这不是缺陷，而是 F14 明确的设计。

### 2.2 来源快照（D01）

| 版本 | manifestBaseline | 来源条数 | 条目定位数 |
| --- | --- | --- | --- |
| cn-ty-2-1-pep-2017@1.0.2（迁移） | `present=true, catalogVersion=2026-10-04.1, fileSha256=107460ab…, byteLength=5822` | 1 | 760 |
| cn-ty-2-1-pep-2017@1.0.3（发布） | **继承同一基线**（catalogVersion/fileSha256 相同） | 2 | 761 |
| cn-ty-3-1-pep-2024@1.0.0 / @1.0.1 | 同上 | 1 / 2 | 777 / 778 |
| en-yl-3-1-yilin-2024@1.0.1 / @1.0.2 / @1.0.3 | 同上 | 1 / 2 / 2 | 140 / 141 / 140 |

即：迁移把清单基线**冻结**在版本上，发布**从上一版本继承基线**（而不是去读当前文件清单）。
新版本来源数=2 是因为本轮编辑写了 `sourceNote`（发布必填，见 §7.4），它被记成一条 `manualNote` 来源。

`/admin/v1/curriculum/reconciliation` 在发布后返回 `identicalCount=2 / totalCount=5`、
`switchSafe=false`、`notes=["存在内容不同的教材：切换后公开内容会发生变化。"]`
—— 只对被改过的 3 册报不同，说明这个对账**有鉴别力**（`evidence/roundtrip-snapshots.json`）。

---

## 3. 编辑与发布

每册一轮编辑（都在真实草稿上、经管理 HTTP 接口）：改 1 条文本（语文同时改拼音）、
调 1 条 `listTypes`（追加合法类别 `expression`）、末尾新增 1 条；英册第二轮删掉上一轮新增的那条。

| editionId | 原版本 | 新版本 | 条目数 | 新版本 SHA-256 | `snapshotReloaded` |
| --- | --- | --- | --- | --- | --- |
| cn-ty-2-1-pep-2017 | 1.0.2 | **1.0.3** | 760 → 761 | `daf97d35520076d4…` | true |
| cn-ty-3-1-pep-2024 | 1.0.0 | **1.0.1** | 777 → 778 | `6466208d77380ff2…` | true |
| en-yl-3-1-yilin-2024 | 1.0.1 | **1.0.2** | 140 → 141 | `2bb2e25676ed3bf5…` | true |
| en-yl-3-1-yilin-2024 | 1.0.1 | **1.0.3**（删 1 条后） | 140 → 140 | `4d19348879c114cf…` | true |

具体改动（`evidence/roundtrip-publish-state.json`）：

| 册 | 文本改动 | listTypes 改动 | 新增条目的正式 ID |
| --- | --- | --- | --- |
| cn-ty-2-1 | `-0001` 两 / liǎng → 两个 / liǎng gè | `-0011` `[recognition]` → `[expression, recognition]` | `cn-ty-2-1-pep-2017-e0761` |
| cn-ty-3-1 | `-0001` 绒 / róng → 绒毛 / róng máo | `-0011` `[recognition]` → `[expression, recognition]` | `cn-ty-3-1-pep-2024-e0778` |
| en | `-0001` hello / 哈啰，你好 → hi / 嗨，你好 | `-0011` `[vocabulary]` → `[expression, vocabulary]` | `en-yl-3-1-yilin-2024-e0141`（第二轮已删） |

发布后公开目录（`GET /public/v1/textbooks`）读到新版本与 `catalogVersion=catalog-5`，
证明"发布 → 对外可见"这一环成立（`evidence/roundtrip-public-after.json`）。

---

## 4. 逐字段对账（实际数字）

对账对象：**内容包里的原始 `book.json`**（迁移进库的同一份字节）vs **发布出来的新版本字节**
（经 `/admin/v1/curriculum/versions/{editionId}/{contentVersion}/bytes` 下载，哈希与发布响应一致）。

### 4.1 条目层

「必需字段」= `entryId` / `text` / `listTypes` / `sourceRefs` + 语文的 `pinyinDisplay` 或英语的 `meaning`。

| 对账 | 条目数（原→新） | 共有条目 | 必需字段全同 | 必需字段有差异（=本轮有意编辑） | 仅"系统性差异" | 新增 | 删除 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| cn-ty-2-1 1.0.2→1.0.3 | 760 → 761 | 760 | **758** | **2** | 758 | 1 | 0 |
| cn-ty-3-1 1.0.0→1.0.1 | 777 → 778 | 777 | **775** | **2** | 775 | 1 | 0 |
| en 1.0.1→1.0.2 | 140 → 141 | 140 | **138** | **2** | 138 | 1 | 0 |
| en 1.0.1→1.0.3（删 1 条后） | 140 → 140 | 140 | **138** | **2** | 138 | **0** | **0** |

- "仅系统性差异"= 该条目的差异**全部**落在已知规则内（版本号 / 顺序重编号 / 校对状态产物 /
  `null`→`""` 归一 / `form` 重算）。**三册分别只有 2 条**条目落在规则之外，且正是本轮有意编辑的那 2 条。
- 条目相对顺序保持：✅（新增条目追加在末尾）。`entryId` 未变，**没有按位置重新分配 ID**。

逐字段变化条数（共有条目）：

| 字段 | cn-ty-2-1 | cn-ty-3-1 | en | 归因 |
| --- | --- | --- | --- | --- |
| `entryVersion` | 760 | 777 | 140 | 版本号重写（派生，允许） |
| `order` | 760 | 777 | 140 | **0 基 → 1 基重编号**（全表，见 §7.3） |
| `reviewStatus` | 760 | 777 | 140 | **`reviewed` → `draft`**（全表，见 §7.2） |
| `meaning` | 760 | 777 | 1 | `null` → `""` 归一（语文册全表；英册 1 条是编辑） |
| `pinyinDisplay` | 1 | 1 | 140 | `null` → `""` 归一（英册全表；语文 1 条是编辑） |
| `text` | 1 | 1 | 1 | 有意编辑 |
| `listTypes` | 1 | 1 | 1 | 有意编辑 |
| `pinyinSegments` | 1 | 1 | 0 | 随编辑行重算，其余全等 |
| `form` | 1 | 1 | 2 | 编辑行 + 英册 `I`/`a` 两条被重算成 `character`（见 §7.5） |
| `partOfSpeech` / `promptContext` / `blockingIssues` | 0 | 0 | 0 | 无差异（原始值全为空，无法作为正向对照） |

### 4.2 单元与课文层

| 对账 | 单元 | 课文 |
| --- | --- | --- |
| cn-ty-2-1 | 8 → 8，`unitId`/`title`/`order`/`editionId` **全部相同**，顺序保持 | 31 → 31，全部相同，顺序保持 |
| cn-ty-3-1 | 8 → 8，全部相同 | 30 → 30，全部相同 |
| en | 9 → 9，全部相同 | 0 → 0（该册无课文，`lessonId` 全为 `null`） |

### 4.3 教材级字段

| 字段 | cn-ty-2-1 | cn-ty-3-1 | en | 判定 |
| --- | --- | --- | --- | --- |
| `subject` / `grade` / `semester` / `publisher` / `series` | 相同 | 相同 | 相同 | ✅ |
| `coverageStatus` | `partial` 相同 | 相同 | 相同 | ✅ |
| `sourceIds` | `["nmg-smartedu-cn-2-first"]` 相同 | 相同 | `["yilin-en-3-first-2024"]` 相同 | ✅ |
| `schemaVersion` | 1 相同 | 相同 | 相同 | ✅ |
| `contentSha256`（正文内） | `""` 相同（真实哈希在版本行与发布响应里） | 相同 | 相同 | ✅ |
| `editionLabel` | `2017 年版（教育部审定）` 相同 | **`…修订版，��` → `…修订版，江苏`** | 相同 | ⚠️ 被迫修正（§7.1） |
| `regionCodes` | **`["jiangsu"]` → `null`** | **`["jiangsu"]` → `null`** | **`["jiangsu"]` → `null`** | ❌ **丢失** |
| `isbn` | `null` → `null`（原本为空） | `null` → `null` | **`"9787575300377"` → `null`** | ❌ **丢失** |
| `curriculumYear` | `null` → `null` | `null` → `null` | `null` → `null` | ⚠️ 样本里全为 `null`，**测不出**（同一条代码路径，见 §7.1） |
| `missingScopes` | 3 条 → `[]` | 2 条 → `[]` | 4 条 → `[]` | ❌ 丢失 |
| `expectedEntryCount` | 760 → `null` | 777 → `null` | 140 → `null` | ⚠️ 见 §7.6 |
| `availableEntryCount` | 760 → 761 | 777 → 778 | 140 → 141 | ✅ 允许（按实际内容重算） |
| `contentVersion` | 1.0.2 → 1.0.3 | 1.0.0 → 1.0.1 | 1.0.1 → 1.0.3 | ✅ 允许（版本标识） |
| `verifiedAt` / `publishedAt` | 变化 | 变化 | 变化 | ✅ 允许（时间戳） |

---

## 5. 反向检查：只在 `preserved` 里的字段还在吗

`CurriculumFileImporter.draftJsonFromBook` 把界面上没有编辑入口的字段塞进草稿的 `preserved`，
`DraftToBookMapper` 在发布时把它们写回正文。上一条线修的是"这条链路丢 `preserved`"。
下面是在**真实 760/777/140 数据**上的结果（`evidence/roundtrip-comparison.json`）：

| 字段 | cn-ty-2-1（原始非空 / 往返后全等） | cn-ty-3-1 | en | 结论 |
| --- | --- | --- | --- | --- |
| `sourceRefs` | 760 / **760** | 777 / **777** | 140 / **140** | ✅ 全等 |
| └ 其中 `sourceRefs[].page` 非空 | 0 / – | 0 / – | **140 / 140** | ✅ 含 `page` 也全等（如 `…0001` 的 `page=78` 原样保留） |
| `senseId` | 760 / **760** | 777 / **777** | 140 / **140** | ✅ 全等 |
| `context` | 0（全为 `null`） | 0 | **18 / 18** | ✅（英册是唯一样本） |
| `acceptableAnswers` | 0（全为 `[]`） | 0 | **4 / 4** | ✅（英册是唯一样本） |
| `pinyinSegments` | 760 / 759（1 条=编辑行） | 777 / 776 | 0 | ✅ 除编辑行外全等 |
| `form` | 760 / 759 | 777 / 776 | 140 / **138** | ⚠️ 两条英册条目被重算（§7.5） |
| `listTypes` | 760 / 759 | 777 / 776 | 140 / 139 | ✅ 除编辑行外全等 |
| `partOfSpeech` | **0 条非空** | 0 | 0 | ⚠️ 样本里全为 `null`，**无法作为正向对照**（如实标注） |
| `promptContext` | 0 条非空 | 0 | 0 | ⚠️ 同上 |
| `blockingIssues` | 全为 `[]` | 全为 `[]` | 全为 `[]` | ⚠️ 无差异可测 |
| `reviewStatus` | 760 / **0** | 777 / **0** | 140 / **0** | ❌ 全部由 `reviewed` 变成 `draft`（§7.2） |

### 5.1 前端 `pairsToEntries` 的往返（同一 bug 的另一端）

`entriesToPairs`（草稿→编辑器行）→ `pairsToEntries`（编辑器行→草稿）是编辑器每次重建条目的必经之路。
用**产品代码本身**在真实草稿上跑（`toolbox-web/scripts/verify-preserved-roundtrip.mjs`）：

| 草稿 | 条目数 | 原样往返不一致字段 | 只改一行后"未改动行"不一致 |
| --- | --- | --- | --- |
| cn-ty-1-1-pep-2024（迁移原始） | 318 | `{}` | 0 |
| cn-ty-2-1-pep-2017（迁移原始） | 760 | `{}` | 0 |
| cn-ty-3-1-pep-2024（迁移原始） | 777 | `{}` | 0 |
| cn-ty-4-1-pep-2024（迁移原始） | 640 | `{}` | 0 |
| en-yl-3-1-yilin-2024（迁移原始） | 140 | `{}` | 0 |
| 三册发布后的草稿 | 761 / 778 / 140 | `{}` | 0 |

合计 **2635 条**真实条目（与生产公开目录的条目总数一致），
逐条比较 `rowId`/`entryId`/`unitId`/`clientUnitId`/`lessonId`/`clientLessonId`/`listTypes`/`preserved`/`automaticInitialized`。

**变异对照（证明该检查有鉴别力）**：把 `pairsToEntries` 里唯一那行
`...(previous?.preserved ? { preserved: { ...previous.preserved } } : {}),` 删掉（**副本放 `/tmp`，仓库文件未改动**，
SHA-256 变异前后均为 `44c46365891a23f759cd6da5fb9e1086e102d1fc421be431a2ae2d96b8d3ea0c`）：

```
FAIL  pristine-cn-ty-2-1-pep-2017  entries=760  原样往返不一致字段={"preserved":760}  改一行后未改动行不一致=9
FAIL  pristine-en-yl-3-1-yilin-2024 entries=140 原样往返不一致字段={"preserved":140}  改一行后未改动行不一致=9
```

---

## 6. "允许变化"的字段（明确清单）

| 字段 | 是否本轮观测到变化 | 理由 |
| --- | --- | --- |
| `contentVersion` | ✅ | 服务端按 `nextContentVersion` 分配 |
| `versionId`（`editionId@contentVersion`） | ✅ | 由版本号派生 |
| `contentSha256`（版本行 / 发布响应） | ✅ | 由新正文字节重算；**正文内的 `contentSha256` 保持 `""` 不变** |
| `entryVersion` | ✅ | 与 `contentVersion` 一致（这是设计规定） |
| `publishedAt` / `verifiedAt` | ✅ | 发布时刻 |
| `availableEntryCount` | ✅ | 按实际写入正文的条目数重算（760→761 等） |
| `catalogVersion` / `epoch` | ✅ | 发布推进世代：`2026-10-04.1`(epoch 0) → `catalog-5`(epoch 4) |
| `order` | ⚠️ **实际变化，但不属于派生** | 见 §7.3 |
| `reviewStatus` | ⚠️ **实际变化，但不属于派生** | 见 §7.2 |
| `form` | ⚠️ **实际变化，但不属于派生** | 见 §7.5 |
| `meaning` / `pinyinDisplay` 的 `null`→`""` | ⚠️ **实际变化，但不属于派生** | 见 §7.5 |
| `expectedEntryCount` / `missingScopes` / `regionCodes` / `isbn` | ⚠️ **实际变化，且其中三项是信息丢失** | 见 §7 |

即：**"只允许版本号/发布时间等派生字段变化"这一条本轮没有通过**——除了派生字段，还有
`order`/`reviewStatus`/`form`/`null→""` 四类系统性变化，以及教材级字段的真丢失。

---

## 7. 未达成项与真实错误

### 7.1 ❌（最重要）迁移后发布**丢掉 `regionCodes`**，`isbn` 同样丢失

- **现象**：三册新版本的 `edition.regionCodes` 全部由 `["jiangsu"]` 变成 `null`；
  英册 `edition.isbn` 由 `"9787575300377"` 变成 `null`。
- **用户可见后果（实测）**：
  ```
  GET /public/v1/textbooks                 → 5 册
  GET /public/v1/textbooks?region=jiangsu  → 只剩 ['cn-ty-1-1-pep-2024','cn-ty-4-1-pep-2024']
  ```
  三册刚发布的教材**在地区筛选下消失**（`CurriculumCatalog.matches` 用
  `edition.regionCodes().contains(query.region())` 判定）。
- **根因**：`CurriculumFileImporter.draftJsonFromBook`
  （`service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/migration/CurriculumFileImporter.java`
  第 140–152 行）只把 `editionId/subject/grade/semester/publisher/series/editionLabel/sourceNote/
  coverageDeclaration/sourceIds` 写进草稿，**没有** `regionCodes` / `curriculumYear` / `isbn`。
  而 `AdminTextbookService.toEdition`（同目录第 593–597 行）**已经**在传 `draft.regionCodes()` /
  `draft.curriculumYear()` / `draft.isbn()`——F03 把"后端硬编码丢掉"改掉了，但**上游的迁移草稿从来没填过这三个值**，
  于是补丁没有覆盖迁移路径。
- **不止点名三册**：直接对比 5 份**迁移原始草稿**与内容包（`evidence/roundtrip-draft-edition-field-loss.json`）：
  **5/5 册**的草稿 `regionCodes` 都由 `["jiangsu"]` 变成缺省，英册 `isbn` 由 `"9787575300377"` 变成缺省。
  即另外两册（318 条 / 640 条）**只要走一次编辑发布就同样会丢**，无需再发布一次来证明。
- **为什么单测没抓到**：既有用例的草稿是**手工构造**的（直接给出 `regionCodes`/`isbn`），
  从不经过 `draftJsonFromBook`；`CurriculumFileImporterTest` 只断言版本落库与草稿非空。
- **修复方向（供业务线决策，本轮未改）**：在 `draftJsonFromBook` 里补
  `regionCodes`（数组）、`curriculumYear`（可空数字）、`isbn`（可空字符串）三项；
  并加一条"从 `book.json` 迁移 → 编辑 → 发布 → 教材级字段逐个相等"的测试
  （用 H2 也能覆盖，因为这与列宽无关）。

### 7.2 ⚠️ 每条的 `reviewStatus` 由 `reviewed` 变成 `draft`

- `installInitialDraft` 只写 `confirmed=true`，**不写 `confirmedDigest`**；
  `DraftToBookMapper.isVerified` 要求"confirmed 且摘要匹配"，缺摘要 → `false` →
  条目以 `ReviewStatus.DRAFT` 发布。三册 760/777/140 条**无一例外**。
- 校验器只检查 `confirmed`（注释明确说"摘要本来就可能还是空的"），因此**发布不会因此被挡住**。
- 后果：公开正文里所有条目的 `reviewStatus` 从 `reviewed` 降级为 `draft`，
  而 `AdminTextbookService` 的"发布即核对"审计按 `reviewStatus==PUBLISHED` 统计，
  于是迁移教材的核对记录为 0 条。**实测佐证**：本轮 4 次成功发布的审计表里
  `textbook.publish` 有 4 行，`textbook.confirm` **一行都没有**。
- 边界：`reviewed` 与 `published` 是**不同**枚举值，现映射表只有 `published`/`draft` 两个出口，
  因此 `reviewed` 在本流程中**不可能原样往返**。

### 7.3 ⚠️ 每条的 `order` 由 0 基变成 1 基

原始 `book.json` 的 `order` 是 `0..N-1`，`DraftToBookMapper` 写的是 `index+1`，因此**全表 +1**。
三册 760/777/140 条全部受影响。单元与课文的 `order` 不受影响（沿用草稿值）。

### 7.4 ⚠️ 发布必填的 `sourceNote` 是发布正文里不存在的字段

- `CurriculumValidator`（PUBLISH 级）要求 `sourceNote` 非空（`SOURCE_REQUIRED`），
  但 `book.json` 的 `edition` 块与 `TextbookEdition` 都**没有** `sourceNote`，
  于是迁移草稿的 `sourceNote` 恒为 `""` → **任何迁移进来的教材，第一次发布都会被 422 挡下**。
  实测（把草稿的 `sourceNote` 清空后原样打回）：
  ```
  HTTP 422 {"code":"TOOLBOX_ADMIN_005","messageKey":"admin.publish.invalid","details":{"issues":1}, …}
  POST /validate 的 issues:
  [{"severity":"BLOCKING","code":"SOURCE_REQUIRED","fieldPath":"sourceNote",
    "detail":"发布必须填写来源说明"}]
  ```
  证据：`evidence/roundtrip-source-note-required-repro.json`。
- 本轮由管理员补写了来源说明（`来源：<sourceIds>（迁移自内容包 2026-10-04.1）`）才发出去；
  补写后它成为版本快照里的一条 `manualNote` 来源（来源数 1→2）。
- 这不造成正文丢失（`sourceNote` 本就不在正文里），但**是迁移 → 发布的必经人工步骤**，
  值得在设计里写明或由迁移自动带出一条可核实的说明。

### 7.5 ⚠️ `form` 被重算 / `meaning`·`pinyinDisplay` 的 `null` 被改成 `""`

- `form` 由 `DraftToBookMapper.formOf(text)` 重新推断（不读 `preserved.form`）：
  英册 `en-yl-3-1-yilin-2024-0004`（`"I"`）与 `-0007`（`"a"`）由 `word` 变成 `character`
  ——单字母英文词按"码点数为 1"被判成汉字形态。
- 草稿用空串承载"没有值"（`entry.path("meaning").asText("")`），发布时原样写出，
  于是**语文册全部 760/777 条的 `meaning`**、**英册全部 140 条的 `pinyinDisplay`** 由 `null` 变成 `""`。
  语义上等价，但字段级不再相同，读端若用 `== null` 判断会走到不同分支。

### 7.6 ⚠️ `expectedEntryCount` 与 `missingScopes` 在发布时被清空

- `AdminTextbookService.toEdition` 对 `expectedEntryCount` 传 `null`（有注释说明理由：
  不把"没有预期"伪装成"预期恰好等于实际"）、对 `missingScopes` 传 `List.of()`。
- 后果：原版本声明的"缺哪些内容"（第六、七、八单元识字表缺失…／平台限制 PDF…）在**新版本里消失**，
  公开目录不再能表达收录缺口。本轮未改动这条设计。

### 7.7 ❌ 内容包缺陷：`cn-ty-3-1-pep-2024` 的 `editionLabel` 含 U+FFFD，带原值**发不出去**

- 内容包里该册 `editionLabel` = `2024 年版（2022 课标修订版，` + **两个 U+FFFD**（原始字节 `ef bf bd ef bf bd`）。
- 发布返回（**原文**）：
  ```
  HTTP 422 {"code":"TOOLBOX_ADMIN_005","messageKey":"admin.publish.invalid",
            "details":{"issues":1},"requestId":"73d4383e-…"}
  ```
  同一草稿的 `POST /validate` 给出问题原文：
  ```json
  [{"severity":"BLOCKING","code":"NAME_REPLACEMENT_CHAR","fieldPath":"editionLabel",
    "rowId":null,"unitId":null,"lessonId":null,"detail":"名称含替换字符 U+FFFD"}]
  ```
- **没有放宽校验**：本轮由管理员把它改成 `2024 年版（2022 课标修订版，江苏）` 后再发布成功。
  因此 cn-ty-3-1 的往返对账里 `editionLabel` 是一项**有意编辑**，不是映射丢字段。
- 附带事实：这条 U+FFFD 同时存在于**生产公开目录**（`production-baseline.md` 里该册
  `editionLabel` 显示为 `…修订版，��`），说明它是**已发布内容包的缺陷**，不是本次引入。

### 7.8 既有测试与"是否需要改产品代码"

- **本轮没有修改任何产品代码**：唯一的仓库新增文件是
  `toolbox-web/scripts/verify-preserved-roundtrip.mjs`（新增，未改既有文件）。
  它不在任何测试 glob 里（`npm test` 只收 `tests/*.test.ts`，vitest 只收 `tests/component/**`，
  `tsconfig.include` 不含 `scripts/**`），因此不影响既有套件。
- 在**与对账同一份字节**的冻结副本上重跑既有测试（`evidence/roundtrip-existing-backend-tests.log`、
  `evidence/roundtrip-existing-frontend-tests.log`）：

  | 套件 | 命令 | 结果 |
  | --- | --- | --- |
  | 后端 | `mvn -o test` | **402 通过 / 0 失败 / 0 错误**，`BUILD SUCCESS` |
  | 前端纯逻辑 | `npm test` | **715 通过 / 0 失败** |
  | 前端组件 | `npx vitest run` | **50 通过 / 0 失败**（9 个文件） |

  （矩阵文档记的是 395 / 715 / 39；本轮实测为 402 / 715 / 50，差值是这一轮其它线新增的用例。）
- **一个值得记录的测试环境事实**：`CurriculumBaselineReconciliationTest` 与
  `PublicCurriculumApiReconciliationTest`（共 4 个用例）通过**从工作目录向上查找 `toolbox-web/content`**
  定位仓库根，并进一步读 `docs/verification/toolbox-admin-textbooks-2026-10-05/curriculum-baseline.json`。
  在 `/tmp` 的副本里直接跑会以
  `IllegalStateException: 未找到仓库根目录（缺少 toolbox-web/content）` 报错；
  给副本父目录补上软链接后 4 个用例全过。**即这套"真实教材对账"测试依赖仓库目录结构，
  不能只靠 jar/副本运行**——如果将来要在 CI 里跑，需要把基线路径做成可配置项。
  这也正是"真实教材逐字段对账"此前一直缺位的机制性原因：现有对账测试只覆盖
  **清单级**（册数、条目数、哈希），不覆盖发布路径上的字段映射。

### 7.9 其它观察（非失败）

- 迁移 5 册全部通过哈希自校验；重复导入全部 `SKIPPED_IDENTICAL`，未覆盖任何已发布内容。
- 逐字段对账脚本与迁移脚本都可重复执行：`all` 重跑时发布阶段会跳过已成功的轮次，
  不会重复消耗版本号（`roundtrip-run.log` 中 4 次 `already published, skip`）。

---

## 8. 对矩阵的建议状态

| 项 | 建议 | 依据 |
| --- | --- | --- |
| **B10 大教材往返** | **部分**（从"测试"升级为"测试 + 实测"，但**不能判通过**） | 真实 760/777/140 三册已跑完 迁移→编辑→保存→发布→逐字段对账，且给出实际数字；但"无损"不成立：`regionCodes`/`isbn`/`missingScopes` 丢失、`order`/`reviewStatus`/`form`/`null→""` 系统性变化。**"未改动条目必须逐一相同"只在要求的比对字段集上成立，在全部 20 个字段上不成立。** |
| **D01 迁移保真** | **部分（增强但不通过）** | 新增实测证据：5 册逐字节哈希四路一致；`catalogVersion` 在空库被认领、发布后不再认领（两个方向都实测）；来源快照冻结/继承 manifest 基线并给出 fileSha256；`reconciliation` 只对被改册报不同。**仍未通过**：迁移保真在"编辑一次再发布"的路径上会丢 `regionCodes`/`isbn`（§7.1），根因定位到具体文件与行号。 |
| **C03 草稿与版本分离** | **部分（不变）** | 本轮新增可用证据：草稿改动只经 `PUT` 影响草稿（发布前公开目录不变）；**发布新版本之后，5 册的旧版本字节全部仍可按 `(editionId, contentVersion)` 原样取回**——`GET /admin/v1/curriculum/versions/{editionId}/{contentVersion}/bytes` 返回 1.0.2 / 1.0.2 / 1.0.0 / 1.0.0 / 1.0.1 原始字节，与内容包**逐字节相同**且等于清单声明的 SHA-256（`evidence/roundtrip-original-bytes-after-publish.json`）。矩阵原判"部分"的其它子项（UI/回退流程）本轮未覆盖，故不建议改判。 |

---

## 9. 证据文件清单（`evidence/`，前缀 `roundtrip-`）

| 文件 | 内容 |
| --- | --- |
| `roundtrip-real-books.py` | 可重跑驱动脚本（env / import / publish / repro-blocked / dump-drafts / snapshots / compare / all） |
| `roundtrip-run.log` | `all` 完整原始输出 |
| `roundtrip-env.json` | 环境 + 9 个源文件 SHA-256 |
| `roundtrip-source-drift.json` | 冻结副本 vs 实时工作树哈希（9/9 相同） |
| `roundtrip-migration-clean-db.json` | 空库迁移报告（5 册 `INSERTED`，含 bytes/SHA/下载回比） |
| `roundtrip-migration.json` | 同一库重复导入（5 册 `SKIPPED_IDENTICAL`，目录 `epoch=4/catalog-5`） |
| `roundtrip-publish-state.json` | 每一次保存/校验/发布的请求-响应、改动明细、新版本哈希 |
| `roundtrip-blocked-repro.json` | cn-ty-3-1 带损坏 `editionLabel` 的 validate/publish 原文 |
| `roundtrip-source-note-required-repro.json` | 清空 `sourceNote` 后的 `SOURCE_REQUIRED` 原文（发布 422） |
| `roundtrip-snapshots.json` | 版本链 + 各版本来源快照骨架 + reconciliation 报告 |
| `roundtrip-comparison.json` | **逐字段结构化差异清单**（条目/单元/课文/教材级 + preserved 反向检查） |
| `roundtrip-book-<editionId>-<version>.json` | 从库里下载的新版本原始字节 |
| `roundtrip-draft*-<editionId>.json` / `roundtrip-drafts-index*.json` | 迁移原始草稿（pristine，干净库导出）/ 发布后草稿，供前端往返检查 |
| `roundtrip-preserved-frontend-pristine.json` | 5 份迁移原始草稿的 `pairsToEntries` 往返结果（PASS） |
| `roundtrip-preserved-frontend-postedit.json` | 3 份发布后草稿的往返结果（PASS） |
| `roundtrip-preserved-frontend-reverse.json` | 变异对照（去掉 `preserved` 带回那一行）的失败明细 |
| `roundtrip-preserved-frontend.log` | 上述三段检查的原始输出 |
| `roundtrip-public-after.json` | 发布后的公开目录 + `region=jiangsu` 过滤结果 |
| `roundtrip-original-bytes-after-publish.json` | 发布新版本后，5 册**旧版本**字节仍逐字节可取回 |
| `roundtrip-draft-edition-field-loss.json` | 5 份迁移草稿 vs 内容包：`regionCodes`/`curriculumYear`/`isbn` 的取舍对照 |
| `roundtrip-existing-backend-tests.log` | 冻结副本上 `mvn -o test`：402 通过 |
| `roundtrip-existing-frontend-tests.log` | `npm test` 715 通过 + `npx vitest run` 50 通过 |
