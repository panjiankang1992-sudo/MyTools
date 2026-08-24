# Legacy Asset Adapter Service 详细设计

## 定位

该服务把可变的 MyTools `local_file` 转换成不可变迁移快照。它不是新的媒体服务，不参与线上文件扫描或读取，不写旧数据库；旧 MyTools 在捕获与迁移期间继续权威运行。

## 一致性模型

直接跨 HTTP 请求分页读取旧表无法证明来自同一时点，因此禁止把高水位 ID 冒充冻结快照。捕获脚本在一个旧库可重复读一致性事务中确定高水位并扫描全部有效行，同时在适配器数据库的一个事务中写入快照、有效项和拒绝审计。任何异常都会回滚目标事务；只有完整提交的快照状态才是 `SEALED`。

## 数据与安全

- `legacy_asset_snapshot`：来源、高水位、接受/拒绝数量、清单摘要和封存状态。
- `legacy_asset_snapshot_item`：标准迁移载荷和逐项摘要。
- `legacy_asset_snapshot_rejection`：旧 ID 与稳定拒绝原因，不保存文件内容。
- 源账号仅授予 `local_file` 的 SELECT；目标账号仅授予适配器 schema DML。
- 导出开关和内部令牌缺省为空/关闭，且只允许读取显式 `snapshotId`。

## 任务流程

1. 创建 `legacy_asset_capture_snapshot` 即时任务，指定英文、数字、下划线、点、冒号或横线组成的快照 ID，并显式绑定可正常登录读取的正数 owner ID。
2. 检查捕获数量、拒绝数量和摘要；处理无法验证的旧记录。
3. 使用同一快照 ID 执行 `asset_migrate_legacy_mappings` dry-run。
4. dry-run 零冲突后正式迁移，重放应全部 skipped。
5. 执行 `asset_reconcile_registry`，核对旧映射数量及 Registry 修订号。

## 验收

- 捕获只在一个旧库一致性事务中读取，目标快照要么完整 SEALED，要么完全不可见。
- 缺少 SHA-256 的记录不能作为内容资产导入。
- 导出默认关闭，未封存或不存在的快照不可读取。
- 迁移任务声明的 `sourceSnapshotId` 必须与每一页响应一致。
