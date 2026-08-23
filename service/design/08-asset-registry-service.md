# Asset Registry Service 详细设计

## 职责

提供跨下载、人工扫描、网盘和消息附件的统一资产身份，维护内容哈希、来源、存储位置、资源包和派生产物引用。它不负责用户媒体视图和实际文件传输。

## 数据模型

- `asset`：ID、SHA-256、大小、MIME、状态。
- `asset_source`：来源类型、来源业务 ID、事件键。
- `asset_location`、`asset_location_invalidation`：存储位置、可用性与失效审计。
- `asset_bundle`、`asset_bundle_item`、`asset_bundle_idempotency`：不可变资源包、资产版本快照和请求绑定。
- `asset_artifact`：缩略图、标签、截图、简介等派生物。
- `asset_registry_revision`：关系写入单调修订号。
- `asset_legacy_mapping`：旧系统资产身份、目标资产和迁移载荷摘要。
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

当前已完成独立资产、来源、位置、派生关系、位置失效审计和不可变资源包。资源包清单固化资产版本，每个成功请求键都有永久绑定，并产生 `AssetBundlePublished` Outbox；位置失效通过资产乐观版本推进并产生 `AssetLocationInvalidated` Outbox。`asset_reconcile_registry` 任务只调用有界摘要页，聚合全库数量与确定性摘要；分页间单调修订号不一致时拒绝报告。

旧 ID 映射已由 `asset_migrate_legacy_mappings` 任务写入专用映射表，提供真实事务回滚式 dry-run、冻结源快照、游标、摘要和冲突报告；旧物理路径只可作为经过 URI 安全校验的位置，不能成为新资产身份。后续需要基于真实旧 schema 实现只读源适配器并运行生产副本迁移。

1. 已建立独立 schema、按内容去重的资产、来源/位置/派生关系、乐观版本、幂等写入和 Outbox；Reader、HTTP 下载、媒体探测和缩略图已通过失败忽略的任务步骤旁路登记，继续从 DownloadBot `assets` 建立初始映射。
2. 导入 MyTools `local_file`，按路径和哈希对账。
3. 已建立旧 ID 到新 asset ID 的映射表和任务化导入契约，待生产数据执行。
4. 双写新资产，验证后切换 Media Library 查询。
5. 删除两边重复的资产权威字段。

## 验收

- 相同内容不同来源只有一个资产和多个来源关系。
- 路径变化不会改变资产身份。
- 迁移可重复执行且不会重复建资产。
- 幂等键绑定不同内容时明确冲突，过期资产版本不能覆盖新位置或派生关系。
