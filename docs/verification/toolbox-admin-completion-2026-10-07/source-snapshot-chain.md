# 来源快照（source snapshot）全链路 —— 后端实现与验证

日期：2026-10-07
范围：`service/toolbox-service`（Java 后端）与 `toolbox-web/content/sources/*.json` 的**只读**解析。
本轮**未修改任何 `toolbox-web/` 下的文件**，也**未改动** `catalogVersion`／`epoch` 的分配逻辑。
本目录不覆盖 2026-10-05／10-06 的历史报告。

## 0. 一句话结果

`toolbox_textbook_draft.source_metadata_json` 与 `toolbox_textbook_version.source_metadata_json`
两列此前从未被任何代码读写；本轮新增**六层来源快照**，在**内存中先构建并校验**，
再沿 草稿 → 发布 → 管理详情 → 导出 → 恢复 全链路串通，
并用真实随机端口 + 隔离 H2 验证了「发布后改磁盘来源、旧版快照逐字节不变」与「导出 → 空库恢复逐项对账」。

- 针对性测试：`SourceSnapshotBuilderTest`（4 条）+ `SourceSnapshotRoundTripTest`（4 条）= **8 条全绿**（`evidence/snapshot-tests-green.log`）。
- 全量回归：`mvn -o test` → **Tests run: 395, Failures: 0, Errors: 0**，`BUILD SUCCESS`（`evidence/full-suite.log`）。
  基线 381；本轮新增 8 条，其余增量来自并行工作线（F11 发布闸门／目录版本迁移）。

## 1. 六层快照各自的落点

构建器：`service/admin/migration/SourceSnapshotBuilder.java`（`@Component`，无新依赖，使用自有的确定性 `ObjectMapper`）。

| 层 | 快照字段 | 数据来源 | 落点 |
| --- | --- | --- | --- |
| 1 快照自身 | `snapshotSchemaVersion`、`snapshot.{capturedAt,capturedBy,operationId,origin,repairBasis}` | 服务端 `Instant.now()` 与调用方传入的操作标识 | 与教材自身的 `verifiedAt` **分开**：原核验时间只保留在冻结的 `sources[].record.verifiedAt` 里 |
| 2 对应教材版本 | `version.{editionId,contentVersion,versionId,contentSha256}` | 发布／导入时手上的原始字节哈希 | 发布走 `CurriculumPublisher.publish*`；导入走 `CurriculumFileImporter` |
| 3 导入目录基线 | `manifestBaseline.{present,missingReason,catalogVersion,schemaVersion,relativePath,fileSha256,byteLength,fileUtf8Text,declaredEdition}` | 内容根目录 `catalog/published.json` 的原始 UTF-8 文本 + SHA-256 | **catalogVersion 只来自 manifest**；草稿从基线版本的快照继承；新管理发布标 `noPublishedBaseline`，绝不拿 `contentVersion` 冒充 |
| 4 来源记录 | `sources[].{sourceId,missing,missingReason,origin,missingFields,record}` | `edition.sourceIds` ∪ `entry.sourceRefs[].sourceId` → 内容根目录 `sources/*.json` | 命中即冻结**完整对象**（kind/title/publisher/isbn/url/accessBasis/verifiedAt/reviewedBy/reviewNotes/revisionReviews/knownGaps…）；未声明/不存在则 `missing=true` + 原因；手工来源 `origin=manual` 只装管理员确实填过的字段 |
| 5 文件证据 | `files[].{relativePath,sha256,byteLength,utf8Text}` | 命中来源所在文件的原始 UTF-8 文本 | **只存内容根目录内的相对路径**；不装绝对路径/凭据/环境配置 |
| 6 条目定位 | `entryLocators[].{entryId,sourceRefs[{sourceId,locator,page}]}` | 发布取已映射条目；导入/恢复/补录取版本原始字节 | 原样保留，不只保存笼统 `sourceNote` |

序列化确定性：`ORDER_MAP_ENTRIES_BY_KEYS`、`TreeMap`、列表排序（来源按 id、文件按相对路径）、
时间由调用方以 ISO 字符串传入；同一输入 → 同一字节与同一 SHA-256。

## 2. 新增 / 修改文件

新增（后端）：
- `service/admin/migration/SourceSnapshotBuilder.java` —— 六层快照构建与缺失标记。
- `service/admin/migration/AdminCurriculumRestoreService.java` —— 从导出包恢复到数据库并逐项对账。
- `service/admin/migration/SourceSnapshotRepairService.java` —— 历史版本显式补录。
- `src/test/.../SourceSnapshotFixtures.java`、`SourceSnapshotBuilderTest.java`、`SourceSnapshotRoundTripTest.java`。

修改（后端；带 ★ 的与并行工作线共用同一文件，我只增补了来源快照相关代码）：
- `mapper/admin/AdminTextbookRepository.java` ★ —— 新增带 `source_metadata_json` 的 `saveDraft`／`installInitialDraft`／`publishVersion` 重载；`findDraft` 读快照；新增 `findVersionSourceMetadata`、`updateSourceMetadataIfAbsent`（条件更新）、`setCurrentVersion`（只移动指针）。
- `service/admin/validation/AdminTextbookDraft.java` —— 新增具名字段 `sourceIds`、`sources`（`DraftSource`）与条目级 `sourceRefs`；保留全部旧构造器；`withIdMapping` 改为规范构造，避免发布回写时丢来源。
- `service/admin/curriculum/DraftToBookMapper.java` —— 优先用具名 `sourceRefs`，为空回落到 `preserved.sourceRefs`。
- `service/admin/curriculum/CurriculumPublisher.java` ★ —— 发布前构建快照并随版本落库；从草稿快照并入手工来源。
- `service/admin/curriculum/AdminTextbookService.java` ★ —— `toEdition` 传真实 `sourceIds`；草稿保存写快照；详情/导出包/导出清单带快照；新增 `versionSourceSnapshot`。
- `service/admin/migration/CurriculumFileImporter.java` ★ —— 导入写快照；初始草稿带 `sourceIds`/具名 `sourceRefs`；基线改为写 `versionId`（见 §6）。
- `service/admin/migration/AdminCurriculumImportService.java` ★ —— 导入时为每册构建含 manifest 基线与来源的快照。
- `controller/admin/curriculum/AdminCurriculumController.java` ★ —— 新增恢复、快照详情、补录三个管理端点。
- `controller/admin/curriculum/AdminTextbookController.java` ★ —— 草稿详情返回 `sourceMetadata`。

未改：`toolbox-web/**`、`ErrorCode`（复用既有错误码）、`TextbookEdition`、`CurriculumEntry`、公开 API。

## 3. 测试与结果

命令：`mvn -o -Dtest='SourceSnapshotBuilderTest,SourceSnapshotRoundTripTest' test`
（`evidence/snapshot-tests-green.log`）→ 8/8 通过，`BUILD SUCCESS`。

| 需求条目 | 测试 | 断言要点 |
| --- | --- | --- |
| 构建器：缺失标记 / 同输入同 hash / 无绝对路径凭据 | `SourceSnapshotBuilderTest` 4 条 | 缺失字段进 `missingFields`；手工来源不含 `reviewedBy`/`verifiedAt`；两次构建字节与 hash 相同；JSON 不含临时根路径、`/Users/`、`password`/`secret`/`token`/`credential`；文件证据为相对路径 |
| 不可变性（验收点名） | `storedSnapshotSurvivesLocalSourceEdit` | 导入后改磁盘 `sources/*.json` → 版本快照与导出包内快照**逐字节不变**；同时证明"现在从磁盘重建会不同" |
| 往返不丢 + 空库恢复 | `exportRestoreIntoEmptyDatabaseReconcilesEverything` | 发布两版 → 导出 → 删除该册全部行（隔离 H2 专用库）→ 恢复：原 bytes/hash、current 指针、历史数 2、catalogVersion 基线、来源快照指纹、`sourceRefs` 逐项一致；指针被摆到旧版后再恢复会被摆回 |
| 手工录入 | `manualEntryBecomesExplicitSourceRecords` | 草稿 `sourceIds`/`sources`/条目 `sourceRefs` 详情可读回；发布后来源快照含 `origin=manual` 的显式记录（url/page/locator），且**没有**机器生成的 `reviewedBy`/`verifiedAt` |
| 补录 | `repairAddsProvenanceWithoutChangingBytes` | 清空历史版本快照（不动正文）→ 补录带 `origin=repair`、`repairBasis`、`capturedAt`、`capturedBy`；原正文 SHA 不变；对不上的来源保留缺失说明；二次补录 `applied=false` 且快照字节不变；无 `basis` 返回 422 |

### 3.1 反向验证（撤销修复后必须失败，已实际执行）

每次都在**临时改坏生产代码**后运行对应测试并采集真实失败输出，随后按字节恢复（已用 `cmp` 校验）。
原始日志见 `evidence/reverse-*.log`。

| 反向验证 | 临时改动 | 真实失败输出（摘要） |
| --- | --- | --- |
| 构建器三属性 | 缺失字段恒返回空表；`capturedAt` 改为 `Instant.now()`；文件证据改返回绝对路径 | `Tests run: 4, Failures: 3`；`missingFieldsAreMarkedNotInvented:103`、`sameInputProducesSameBytesAndHash:113`（`capturedAt` 两次不同）、`snapshotCarriesNoAbsolutePathOrCredential:130`（输出含 `/var/folders/...`） |
| 不可变性 | 管理详情改为**从磁盘重建**快照 | `storedSnapshotSurvivesLocalSourceEdit:128 [发布后修改本地 sources 不得改变旧版快照]`；实际输出出现 `"title":"被改动的标题"`、`"reviewedBy":"someone-else"`——正是要阻止的"用今天的来源冒充当年" |
| 恢复 | 恢复时不再把包内 `sourceMetadataJson` 写回 | `exportRestoreIntoEmptyDatabaseReconcilesEverything:201` → `Expecting value to be true but was false`（`sourceSnapshotMatches`） |
| 补录不覆盖 | 去掉服务层"已有快照即返回"判断，并把条件 UPDATE 改为无条件 | `repairAddsProvenanceWithoutChangingBytes:354` → `Expecting value to be false but was true`（二次补录 `applied`） |
| 手工来源 | 发布时不再从草稿快照并入 `origin=manual` 记录 | `manualEntryBecomesExplicitSourceRecords:291 [管理员手填的来源必须成为明确来源记录]` → `Expecting actual not to be null` |

## 4. 恢复演练的做法与结果

1. 夹具内容根目录（临时目录，运行时生成）：`catalog/published.json`（`catalogVersion=2026-10-04.1`）、
   `textbooks/snap-book/1.0.0/book.json`（条目带 `sourceRefs={snap-source-full,row 2,7}`）、
   `sources/snap-sources.json`（一条字段完整 + 一条刻意缺字段）。
2. `POST /admin/v1/curriculum/import-from-files` → 版本快照记录 manifest 基线、完整来源对象、文件 SHA-256、条目定位。
3. 编辑并发布第二版（`1.0.1`）→ 历史留存 2 版。
4. `GET /admin/v1/curriculum/export-package` 与 `.../export-manifest` → 都带上快照。
5. **空库**：测试类使用独立 H2（`jdbc:h2:mem:source-snapshot`），并删除该册全部行，断言版本数为 0。
6. `POST /admin/v1/curriculum/restore-package` → 逐版导入（先校验 Base64 解码后的长度/哈希/快照指纹），
   再重建 current 指针与历史。
7. 对账（报告中 `reconciliations[]`）：`contentSha256Matches`、`sourceSnapshotMatches`、
   `currentPointerRestored`、`historyCount`、`catalogVersionBaseline` 全部为真/相符；
   再用 `/bytes` 下载恢复后的正文，逐字节等于发布时的正文。

## 5. 供前端接线的字段契约（后端已就绪）

全部为**写可选、读返回**；缺省不报错。JSON 字段名与形状：

草稿读写 `POST/PUT/GET /admin/v1/textbooks[/{editionId}/draft]`，`draft` 对象：
```json
{
  "editionId": "cn-ty-1-1-pep-2024",
  "sourceIds": ["smartedu-ebook-cn-1-first-2024"],
  "sources": [
    {"sourceId": null, "kind": "manualEntry", "title": "管理员手填的教辅",
     "url": "https://example.org/manual", "publisher": "某出版社", "isbn": "9780000000000",
     "editionLabel": "2024 年版", "locator": "第 12 页", "page": 12, "note": "手填备注"}
  ],
  "entries": [
    {"rowId": "r1", "entryId": null, "text": "水", "pinyin": "shuǐ",
     "sourceRefs": [{"sourceId": "smartedu-ebook-cn-1-first-2024", "locator": "row 9", "page": 3}]}
  ]
}
```
- `sourceIds`: `string[]`，可选，默认 `[]`；元素指向内容包 `sources/*.json` 的 `sourceId`。
- `sources`: `DraftSource[]`，可选，默认 `[]`；每项字段均可空：
  `sourceId`(string) / `kind`(string) / `title`(string) / `url`(string) / `publisher`(string) /
  `isbn`(string) / `editionLabel`(string) / `locator`(string) / `page`(number) / `note`(string)。
- `entries[].sourceRefs`: `{sourceId:string, locator:string|null, page:number|null}[]`，可选，默认 `[]`。
  **优先于**旧的 `entries[].preserved.sourceRefs`（旧字段仍兼容读取，前端可逐步迁移）。

草稿 `GET` 响应新增：`sourceMetadata: string|null` —— 草稿级快照 JSON（原样展示即可，不必解析）。

管理端新增/变化的读接口：
- `GET /admin/v1/curriculum/versions/{editionId}`：每项新增 `hasSourceSnapshot:boolean`、`sourceSnapshotSha256:string|null`。
- `GET /admin/v1/curriculum/versions/{editionId}/{contentVersion}/source-snapshot`
  → `{editionId, contentVersion, snapshotJson, sha256}`；无快照时 404（`reason=sourceSnapshotMissing`）。
- `GET /admin/v1/curriculum/export-package`：每个版本项新增 `current:boolean`、`editionMetadataJson:string|null`、
  `sourceMetadataJson:string|null`、`sourceSnapshotSha256:string|null`。
- `GET /admin/v1/curriculum/export-manifest`：每个历史项新增 `hasSourceSnapshot`、`sourceSnapshotSha256`、
  `sourceMetadataJson`（有快照时）。

管理端新增的写接口：
- `POST /admin/v1/curriculum/restore-package`，body 即导出包的 `data`，
  返回 `{restoredEditions, restoredVersions, skippedVersions, conflicts[], reconciliations[], ok}`。
- `POST /admin/v1/curriculum/versions/{editionId}/{contentVersion}/repair-source-snapshot?basis=<必填>`
  → `{editionId, contentVersion, applied, overwroteOriginal, reason, snapshot:{json,sha256}|null}`；
  缺 `basis` 返回 422。

## 6. 一处必要的既有行为修复（超出快照本身，但为链路可用所必需）

`CurriculumFileImporter` 安装初始草稿时，`base_content_version` 写的是**裸 `contentVersion`**
（如 `1.0.0`），而 `AdminTextbookService.publishLocked` 的锁内基线校验拿它与
`current_version_id`（`editionId@contentVersion`）比较——两者永不相等，
于是**任何从文件迁移进来的教材一被编辑就再也发不出去（409 baselineMismatch）**。
现改为写 `editionId@contentVersion`，与 `CurriculumPublisher.updateDraftBaseVersion` 的既有约定一致。
该修复由本轮的往返测试（导入 → 编辑 → 再发布第二版）直接覆盖。

## 7. 未达成 / 明确边界（如实说明）

1. **未调用 `reloadSnapshot` 重建运行中实例的公开目录**。恢复服务只写数据库；对**运行中的**实例，
   公开目录要等重启或下一次发布才会重建。这是刻意选择：为避免与并行工作线的 F11 闸门／目录版本逻辑耦合。
   备份恢复的常规流程（换机重启）不受影响。
2. **旧包兼容**：导出包若缺 `editionMetadataJson`（本轮之前生成的包），恢复时从正文 `edition` 块重建声明元数据，
   `publishedAt` 等时刻字段可能与原始元数据不完全一致；本轮之后生成的包不再有这个问题。
3. **恢复到已有内容的库**：同版本同哈希会被跳过（不新增、不改动）；若库中既有快照与包内不同，
   对账会报 `sourceSnapshotMatches=false`，**不覆盖**——需要人工判断。
4. **补录只能补空**：某版本一旦有快照（原始或已补录），本流程不再改写它。
   这是"不覆盖既有非空快照"的代价：**一次错误的补录无法用同一流程纠正**，只能走人工数据库操作。
5. **`setCurrentVersion` 的"当前版本不是最高版本"场景**：真实夹具里当前版本恰好是最高版本，
   测试用"手动把指针摆到旧版再恢复"覆盖了指针修复；没有构造"current 低于历史"的真实内容包。
6. **`catalogVersion` / `epoch` 分配逻辑未改**（按协调要求）。快照只**记录** manifest 的 `catalogVersion`；
   初始迁移是否把该值写回 `toolbox_curriculum_catalog` 由并行工作线负责。因此恢复后
   `toolbox_curriculum_catalog.catalog_version` 仍是 epoch 派生的 `catalog-N`，
   对账比较的是**快照里记录的 manifest 基线**（`reconciliations[].catalogVersionBaseline`），已在测试中固定为 `2026-10-04.1`。
7. **前端未接线**：本轮未改 `toolbox-web/**`；字段契约为后端已实现并可读回（见 §5），
   由前端工作线接入。本条不影响后端验收。

## 8. 验收矩阵相关行的建议状态

| 行 | 原状态 | 建议 | 依据 |
| --- | --- | --- | --- |
| **F14**（导出与回退） | 部分 | **后端部分可升为通过**：新增"从导出包恢复到空库 + 逐项对账"（8 条测试含反向验证）；恢复 UI/运维入口未做 | 本文件 §3、§4 |
| **F03**（三字段录入/迁移保真） | 部分（界面控件无法验证） | **来源快照子项通过**：`sourceIds` 已有具名通道、发布经 `toEdition` 真实透传、草稿→详情→导出→恢复不丢；**"界面录入控件"仍待前端与真实浏览器验证**，故整行仍为部分 | §1、§3、§5 |
| **B04**（双列录入） | 部分 | **不变**（前端职责）。本轮仅提供 `entries[].sourceRefs` 等后端字段契约 | §5 |
| **D01**（迁移保真） | 部分 | 增强：manifest 的 `catalogVersion`/`schemaVersion`/原文与 SHA-256 现随版本冻结并可导出核对；`catalog_version` 列的回写仍属并行工作线 | §1、§6 |
| **D02**（导出与回退） | 部分 | 增强：数据库级恢复演练已完成；"切换 files 后仍保留历史索引"由并行工作线验证 | §4 |

## 9. 复现命令

```bash
# 针对性（本轮新增）
mvn -o -f service/toolbox-service/pom.xml -Dtest='SourceSnapshotBuilderTest,SourceSnapshotRoundTripTest' test

# 全量
mvn -o -f service/toolbox-service/pom.xml test
```

证据文件：`evidence/snapshot-tests-green.log`、`evidence/full-suite.log`、
`evidence/reverse-builder-mutated.log`、`evidence/reverse-immutability-mutated.log`、
`evidence/reverse-restore-mutated.log`、`evidence/reverse-repair-mutated.log`、
`evidence/reverse-manual-entry-mutated.log`。
