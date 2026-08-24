# MsgService Adapter Service

旧 MsgService 到新 Messaging Service 的历史数据防腐层，使用 Python 3.12 和独立 `mytools_msgservice_adapter` schema。该服务不加入 MyTools 根工程、不修改旧 MsgService，也不持有新 Messaging 数据库权限。

服务默认同时设置 `MSGSERVICE_ADAPTER_IMPORT_ENABLED=false` 和 `MSGSERVICE_ADAPTER_EXPORT_ENABLED=false`。健康检查可用不表示迁移能力已开启；只有部署方显式开启相应能力并配置非空 `MSGSERVICE_ADAPTER_INTERNAL_TOKEN` 后，受保护接口才可装载或导出数据。

接口：

- `POST /internal/v1/migration/inbound-messages/snapshots`：装载最多 200 条已经脱敏和标准化的历史快照。
- `GET /internal/v1/migration/inbound-messages?limit=200&afterId=...&snapshotHighWater=...`：首次读取冻结追加日志高水位，后续分页固定使用该不透明高水位，并返回固定条目数和集合摘要；契约供 `message_migrate_history` 1.1.0 使用。
- `POST /internal/v1/migration/outbound-messages/snapshots`：装载最多 200 条发件归档快照，只接受内容寻址附件引用。
- `GET /internal/v1/migration/outbound-messages?limit=200&afterId=...&snapshotHighWater=...`：按冻结高水位导出发件历史，供 Messaging `legacy-outbound` 迁移接口使用。
- `GET /health`：仅检查进程存活。

相同 `sourceSystem + legacyMessageId` 和相同摘要的记录幂等跳过；身份相同但正文、元数据或分段变化时拒绝覆盖。快照只允许 Messaging 历史迁移契约中的字段，未知字段会被拒绝，避免旧服务密码、Cookie 或渠道密钥被意外带入。冻结高水位的集合证据按 `sourceSystem + legacyMessageId` 排序，首次计算后写入 `legacy_inbound_export_snapshot`，后续分页读取同一不可变证据，避免大批迁移按页重复扫描全量历史。证据缓存使用 `messaging-history-v1` 协议版本；升级摘要算法时保留旧证据但不会误用。

旧 MsgService 已按远程 `/opt/code/MsgService` 的实际 TypeScript 与 SQLite 实现完成核验。生产库使用 WAL，导出器通过 SQLite backup API 生成一致快照，不直接复制活动 `.db` 文件。发件附件可能以内嵌 Buffer JSON、data URI、裸 Base64 或受控目录文件存在，导出器会提取为内容寻址归档，并只向新服务传递文件名、类型、实际大小、SHA-256 和归档引用；越过附件根目录或声明大小不一致时立即停止。若旧记录引用的文件在源主机已经不存在，则保留 `MISSING` 状态和旧引用，并在对账中单独计数，防止把源端既有缺失伪装成完整迁移。

只读导出示例（输出目录必须不存在）：

```bash
mytools-msgservice-export \
  --source /path/to/msgsvc.db \
  --output /path/to/msgservice-export-20260824 \
  --attachment-root /path/to/allowed/attachments \
  --owner-id 0
```

输出包含 `msgservice-consistent.db`、`outbound-batch.json`、`attachment-manifest.json`、`reconciliation.json` 和 `attachments/sha256/`。这些文件包含历史业务数据，应放在受控迁移目录，不得提交到代码仓库。
