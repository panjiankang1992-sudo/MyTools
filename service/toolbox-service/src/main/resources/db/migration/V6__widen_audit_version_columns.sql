-- 修复：审计表的版本列表装不下它实际要存的值。
--
-- 与 V5 同源：`AdminTextbookService` 写审计时传的 `from_version` 是
-- **草稿基线** `stored.baseContentVersion()` —— 而这一列在草稿里存的是
-- **versionId**（`editionId + "@" + contentVersion`，见 V5 的说明），
-- 不是 32 字符以内的 `contentVersion`。
--
-- 于是 `len(editionId) + 1 + len(contentVersion) > 32` 时：
-- * 长 editionId 教材**第二次发布**写审计必失败（`Data too long`，事务回滚）；
-- * **迁移进来的长标识教材首次发布也发不出去**——
--   因为迁移会把 `base_content_version` 直接设为 `editionId@contentVersion`。
--
-- 实测（2026-10-07，隔离 MySQL 8.4）：校验 `publishable=true` 仍返回 **503 TOOLBOX_008**。
--
-- 宽度取 64，与同一个值域的 `toolbox_textbook_version.version_id` /
-- `toolbox_textbook_draft.base_content_version` 一致。
ALTER TABLE toolbox_curriculum_audit
  MODIFY COLUMN from_version VARCHAR(64) NULL;

ALTER TABLE toolbox_curriculum_audit
  MODIFY COLUMN to_version VARCHAR(64) NULL;
