# MsgService Adapter Service

旧 MsgService 到新 Messaging Service 的历史数据防腐层，使用 Python 3.12 和独立 `mytools_msgservice_adapter` schema。该服务不加入 MyTools 根工程、不修改旧 MsgService，也不持有新 Messaging 数据库权限。

服务默认同时设置 `MSGSERVICE_ADAPTER_IMPORT_ENABLED=false` 和 `MSGSERVICE_ADAPTER_EXPORT_ENABLED=false`。健康检查可用不表示迁移能力已开启；只有部署方显式开启相应能力并配置非空 `MSGSERVICE_ADAPTER_INTERNAL_TOKEN` 后，受保护接口才可装载或导出数据。

接口：

- `POST /internal/v1/migration/inbound-messages/snapshots`：装载最多 200 条已经脱敏和标准化的历史快照。
- `GET /internal/v1/migration/inbound-messages?limit=200&afterId=...&snapshotHighWater=...`：首次读取冻结追加日志高水位，后续分页固定使用该不透明高水位，并返回固定条目数和集合摘要；契约供 `message_migrate_history` 1.1.0 使用。
- `GET /health`：仅检查进程存活。

相同 `sourceSystem + legacyMessageId` 和相同摘要的记录幂等跳过；身份相同但正文、元数据或分段变化时拒绝覆盖。快照只允许 Messaging 历史迁移契约中的字段，未知字段会被拒绝，避免旧服务密码、Cookie 或渠道密钥被意外带入。冻结高水位的集合证据按 `sourceSystem + legacyMessageId` 排序，首次计算后写入 `legacy_inbound_export_snapshot`，后续分页读取同一不可变证据，避免大批迁移按页重复扫描全量历史。证据缓存使用 `messaging-history-v1` 协议版本；升级摘要算法时保留旧证据但不会误用。

旧 MsgService 已按远程 `/opt/code/MsgService` 的实际 TypeScript 与 SQLite 实现完成核验。生产库使用 WAL，因此导出必须读取 SQLite online backup 生成的一致快照。当前适配器已经实现入站快照；发件快照接口和只读 SQLite 导出器是下一迁移步骤。在这两项完成前不得删除旧库或附件数据。发件附件可能以内嵌 Buffer JSON 存在，导出器必须先提取为内容寻址归档，并只向新服务传递文件名、类型、大小、SHA-256 和归档引用。
