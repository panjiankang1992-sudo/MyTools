# Asset Registry Service 详细设计

## 职责

提供跨下载、人工扫描、网盘和消息附件的统一资产身份，维护内容哈希、来源、存储位置、资源包和派生产物引用。它不负责用户媒体视图和实际文件传输。

## 数据模型

- `assets`：ID、SHA-256、大小、MIME、状态。
- `asset_sources`：来源类型、来源业务 ID、事件键。
- `asset_locations`：存储提供方、规范路径、版本和可用性。
- `asset_packages`：资源包、清单版本、发布状态。
- `asset_artifacts`：缩略图、标签、截图、简介等派生物。
- `asset_outbox`。

## 接口

- 按哈希发现或创建资产。
- 登记/失效存储位置。
- 发布资源包。
- 登记派生产物。
- 查询资产及来源。

写接口全部支持幂等键和乐观版本。

## 任务与 DML

- `asset_hash_file`、`asset_reconcile_locations`、`asset_migrate_legacy_packages`。
- 扫描脚本可以批量 DML 写入暂存表，最终通过存储过程或 API 合并到权威表。
- 禁止脚本直接覆盖资产哈希或把两个不同内容强制合并。

## 迁移

1. 已建立独立 schema、按内容去重的资产、来源/位置/派生关系、乐观版本、幂等写入和 Outbox；继续从 DownloadBot `assets` 建立初始映射。
2. 导入 MyTools `local_file`，按路径和哈希对账。
3. 建立旧 ID 到新 asset ID 的映射表。
4. 双写新资产，验证后切换 Media Library 查询。
5. 删除两边重复的资产权威字段。

## 验收

- 相同内容不同来源只有一个资产和多个来源关系。
- 路径变化不会改变资产身份。
- 迁移可重复执行且不会重复建资产。
- 幂等键绑定不同内容时明确冲突，过期资产版本不能覆盖新位置或派生关系。
