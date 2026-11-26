# 本轮修掉的三处"值装不下 / 搬不过去"缺陷（2026-10-07）

**共同模式**：**能编译、单测全绿，但值在真实链路上到不了目的地或存不下**。
三处都在**真实 MySQL / 真实教材**上才暴露。前两处是 H2 **不强制 VARCHAR 宽度**掩盖的；
第三处是**迁移路径从未搬过那三个字段**。

## 缺陷 1：草稿 `base_content_version` 列宽不足 → 首次发布必 503

| 项 | 内容 |
| --- | --- |
| 症状 | editionId 27 字符时 `POST .../publish` → **503 `TOOLBOX_008`**，MySQL `Data too long`，事务正确回滚 |
| 根因 | 列是 `VARCHAR(32)`，写进去的却是 **versionId**（`editionId + "@" + contentVersion`）；同行 `version_id` 是 64 |
| 触发 | `len(editionId) + 1 + len(contentVersion) > 32`，即首次发布 `editionId ≥ 26` 必挂 |
| 修复 | `V5__widen_draft_base_content_version.sql` → `VARCHAR(64)` |
| 修复后实测 | 同一 editionId → **200**，`base_content_version` 长 **33**（33 > 32） |
| 反向验证 | 把列改回 32 → **MySQL 拒绝**：`ERROR 1406 Data too long for column 'base_content_version' at row 2`（表里已躺着那个 33 字符的值）|

## 缺陷 2：审计表 `from_version` / `to_version` 列宽不足 → 长标识教材发不出去

| 项 | 内容 |
| --- | --- |
| 症状 | 长 editionId 教材**第二次发布**必 503；**迁移进来的长标识教材首次发布也发不出去**（校验 `publishable=true` 仍 503）|
| 根因 | 与缺陷 1 同源：审计里传的 `from_version` 是草稿基线，而**草稿基线存的是 versionId**，列却只有 32 |
| 修复 | `V6__widen_audit_version_columns.sql` → 两列都 `VARCHAR(64)`（与 `version_id` / 草稿基线同宽）|
| 修复后实测（真实 MySQL）| 第一次发布 **200** → 保存编辑 **200** → **第二次发布 200**；审计行 `from_version` 长 **33** |
| 反向验证 | 见下（同一轮里先把两列当 32 用会失败；V5 的 1406 是同类自证）|

**为什么单测没抓到**：审计写入在 H2（MySQL 模式）里**不校验 VARCHAR 长度**，
超长值静默写入成功 → 403 条后端测试全绿。**只有真实 MySQL 暴露。**

## 缺陷 3：迁移不搬 `regionCodes` / `curriculumYear` / `isbn` → 教材在地区筛选下消失

| 项 | 内容 |
| --- | --- |
| 症状 | 真实 5 册迁移后，`GET /public/v1/textbooks?region=jiangsu` **只剩 2 册** —— **迁移后重新发布的教材在地区筛选下消失** |
| 根因 | `CurriculumFileImporter.draftJsonFromBook`（原 140–152 行）**不写**这三个字段 → 迁移草稿里恒为 `null`；`AdminTextbookService.toEdition` 虽已改成传真实值，**却拿不到值** |
| 修复 | 迁移时搬 `regionCodes`（数组）/ `curriculumYear`（**仅当是数字，缺失留空**）/ `isbn`（**仅当是非空文本**）|
| 为什么要真值判断 | 用 `asInt(0)` / `asText("")` 会把"缺失"变成"0 / 空串"——**看起来有值其实没有**，会污染后续对账 |
| 测试 | `CurriculumFileImporterTest` 新增 2 条：①三个字段进 `editable_json` ②年份缺失**不得写成 0** |
| **反向验证** | 临时撤回三个字段 → `Tests run: 6, Failures: 2`（真实失败）✓；恢复后 `cmp` **逐字节一致** ✓ |

## 本轮全量回归（三处修复之后）

| 检查 | 结果 |
| --- | --- |
| 后端 | **405 / 405** ✓（403 + 2） |
| 前端 Node | **715 / 715** ✓ |
| 组件 | **50 / 50** ✓ |
| typecheck | 干净 ✓ |

## 仍未修的相关缺陷（已复现，如实留档）

| # | 严重度 | 缺陷 | 为什么没修 |
| --- | --- | --- | --- |
| D3 | **高** | `publishDraft` 在**外层事务提交前**就 `reloadSnapshot` 交换内存快照 → 回滚后 DB 回滚了、内存没回退 → **公开接口继续 200 提供从未提交的版本**（实测 served `catalog-5` vs DB `catalog-4`）| 需挪到 afterCommit，属改发布主路径，需单独验证事务/回滚/并发 |
| D1 | 中 | `catalog_version` 拼装在 MySQL（`catalog-<epoch+1>`）与 H2（`catalog-<epoch>`）语义不同；设计示例两者同数 | 改法会让既有库版本名重名，需数据迁移决策 |
| — | 中 | `order` 全表 0 基→1 基、`reviewStatus` 全表 `reviewed`→`draft`、`meaning`(语文)/`pinyinDisplay`(英语) `null`→`""`、`form` 2 条 `word→character` | 属迁移/映射的语义选择，需按设计逐项确认后再改 |
| — | 中 | 发布必填 `sourceNote` 在正文里不存在 → 迁移草稿恒为空，发布 422 `SOURCE_REQUIRED` | 需定"迁移教材的来源说明从哪来" |
