# 修复：草稿 `base_content_version` 列宽不足（H2 测试掩盖的真实缺陷）

**发现方式**：隔离 MySQL 8.4 环境上的管理员完整链路（**不是**单测）。
**证据等级**：✅ **真实 MySQL 上前后对比 + 反向验证**。

## 缺陷

`toolbox_textbook_draft.base_content_version` 是 **`VARCHAR(32)`**，
但代码写进去的是 **versionId**（`editionId + "@" + contentVersion`，
见 `CurriculumPublisher.publishDraft`）。而 `edition_id` 允许 **128** 字符、
`toolbox_textbook_version.version_id` 是 **64** 字符 —— 同一个值域，列宽却不一致。

**触发条件**：`len(editionId) + 1 + len(contentVersion) > 32`，
即**首次发布**只要 `editionId >= 26` 字符就必然失败。

## 症状（修复前，隔离 MySQL 实测）

| 项 | 值 |
| --- | --- |
| 请求 | `POST /admin/v1/textbooks/local-acceptance-2026-10-07/publish` |
| editionId 长度 | **27** |
| HTTP | **503 `TOOLBOX_008`** |
| MySQL 错误 | `Data truncation: Data too long for column 'base_content_version'` |
| 事务 | **正确回滚**（该教材 `current_version_id` 仍为 `NULL`） |
| 换短 id（`lab-a`） | 200 —— 证明链路本身没问题，只有列宽 |

**为什么 15 条单测全绿**：H2 的 MySQL 兼容模式**不强制** VARCHAR 长度，
写入超长值不报错。**这个缺陷只有在真实 MySQL 上才会暴露。**

## 修复

新增迁移 `service/toolbox-service/src/main/resources/db/migration/V5__widen_draft_base_content_version.sql`：

```sql
ALTER TABLE toolbox_textbook_draft
  MODIFY COLUMN base_content_version VARCHAR(64) NULL;
```

宽度取 **64**，与同一个值域的 `toolbox_textbook_version.version_id` 一致。
**H2 与真实 MySQL 都能应用**（H2 侧 `AdminTextbookRepositoryTest` 6/6 通过）。

## 修复后（真实 MySQL 实测）

```
editionId=local-acceptance-2026-10-07 长度=27
3) create=HTTP:409  reason=editionExists      # 上一轮失败尝试已建过草稿
4) publish=HTTP:200  versionId=local-acceptance-2026-10-07@1.0.0
5) current_version_id = local-acceptance-2026-10-07@1.0.0
6) base_content_version 长度 = 33
```

**`33 > 32`** —— 正是旧列宽装不下的那个值 ✓。

## 反向验证（比"改回代码再跑"更强）

把列改回 `VARCHAR(32)`：

```
ERROR 1406 (22001) at line 1: Data too long for column 'base_content_version' at row 2
```

**MySQL 直接拒绝** —— 因为表里现在已经躺着那个 33 字符的值。
**旧列宽连它自己该存的数据都装不下**，这是本次修复必要性的自证。

（未改回列宽业务代码来做"撤回修复"，因为**数据层面已经证否**：迁移前的 schema
无法容纳迁移后必然产生的值。另一条独立证据是上文"修复前 503"的实测记录。）

## 顺带发现（本次未处理，如实记录）

`toolbox_textbook_version.content_version` 也是 `VARCHAR(32)`。
`contentVersion` 由服务端在锁内分配（当前形态 `1.0.0`），
**目前没有越界路径**，所以本次没有一并加宽 —— **不是遗漏，是没有证据表明它会溢出**。
若将来引入更长的版本号方案（例如带构建号/时间戳），这一列需要重新评估。

## 复现命令

```bash
# 1) 迁移前：用长 editionId 发布 → 503
# 2) 应用 V5（重启本地后端，Flyway 自动应用）
# 3) 迁移后：同一 editionId 发布 → 200，base_content_version 长度 33
# 4) 反向：ALTER ... VARCHAR(32) → ERROR 1406
```
完整脚本：`/tmp/verify-v5.sh`（本次为一次性验证脚本，命令已抄录在上面）。
