-- 修复：草稿的 `base_content_version` 列装不下它实际要存的值。
--
-- 语义澄清：这一列存的**不是** `contentVersion`，而是 **versionId**
-- （`editionId + "@" + contentVersion`，见 `CurriculumPublisher.publishDraft`）。
-- 而 `edition_id` 允许 128 字符 —— 所以只要
-- `len(editionId) + 1 + len(contentVersion) > 32`，首次发布写回基线时就会
-- MySQL `Data truncation: Data too long for column 'base_content_version'`。
--
-- 实测（2026-10-07，隔离 MySQL 8.4）：editionId 为 27 字符时
-- `POST /admin/v1/textbooks/{id}/publish` 返回 **503 TOOLBOX_008**，
-- 事务正确回滚（该教材 `current_version_id` 仍为 NULL）。
--
-- **为什么 15 条单测没发现**：H2 的 MySQL 兼容模式**不强制** VARCHAR 长度，
-- 写入超长值不报错。这个缺陷只有在真实 MySQL 上才会暴露。
--
-- 宽度取 **64**，与同一个值域的 `toolbox_textbook_version.version_id` 一致。
ALTER TABLE toolbox_textbook_draft
  MODIFY COLUMN base_content_version VARCHAR(64) NULL;
