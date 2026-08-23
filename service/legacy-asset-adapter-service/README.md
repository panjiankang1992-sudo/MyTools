# Legacy Asset Adapter Service

MyTools `local_file` 到 Asset Registry 的独立只读迁移防腐层。服务使用 Python 3.12 和独立 `mytools_legacy_asset_adapter` schema，不加入 MyTools 根工程，也不修改旧表、旧控制器或原有任务。

迁移分为两个显式任务：

1. `legacy_asset_capture_snapshot` 在旧库的单个 `REPEATABLE READ ... WITH CONSISTENT SNAPSHOT` 事务中读取 `local_file`，把可验证记录物化到适配器 schema，并在目标事务提交时原子标记为 `SEALED`。
2. `asset_migrate_legacy_mappings` 必须携带明确 `sourceSnapshotId`，通过只读 HTTP API 分页读取该封存快照，再 dry-run 或正式写入 Asset Registry。

旧数据库账号只允许 `SELECT`；适配器数据库账号只访问 `mytools_legacy_asset_adapter`。捕获脚本不读取文件内容，以旧库已有 SHA-256 和大小作为迁移证据；缺失哈希、非法大小或非绝对路径记录进入 `legacy_asset_snapshot_rejection`，不会伪造资产。快照捕获和目标导出使用不同凭据。

导出默认 `LEGACY_ASSET_ADAPTER_EXPORT_ENABLED=false`。只有显式开启且提供 `LEGACY_ASSET_ADAPTER_INTERNAL_TOKEN` 后，`GET /internal/v1/migration/assets?snapshotId=...&limit=200&afterId=...` 才返回 `SEALED` 快照；健康检查不代表迁移已放量。
