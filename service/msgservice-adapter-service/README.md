# MsgService Adapter Service

旧 MsgService 到新 Messaging Service 的历史数据防腐层，使用 Python 3.12 和独立 `mytools_msgservice_adapter` schema。该服务不加入 MyTools 根工程、不修改旧 MsgService，也不持有新 Messaging 数据库权限。

服务默认同时设置 `MSGSERVICE_ADAPTER_IMPORT_ENABLED=false` 和 `MSGSERVICE_ADAPTER_EXPORT_ENABLED=false`。健康检查可用不表示迁移能力已开启；只有部署方显式开启相应能力并配置非空 `MSGSERVICE_ADAPTER_INTERNAL_TOKEN` 后，受保护接口才可装载或导出数据。

接口：

- `POST /internal/v1/migration/inbound-messages/snapshots`：装载最多 200 条已经脱敏和标准化的历史快照。
- `GET /internal/v1/migration/inbound-messages?limit=200&afterId=...`：按不透明稳定游标导出，契约直接供 `message_migrate_history` 1.0.0 使用。
- `GET /health`：仅检查进程存活。

相同 `sourceSystem + legacyMessageId` 和相同摘要的记录幂等跳过；身份相同但正文、元数据或分段变化时拒绝覆盖。快照只允许 Messaging 历史迁移契约中的字段，未知字段会被拒绝，避免旧服务密码、Cookie 或渠道密钥被意外带入。

首批数据可由针对旧 MsgService 实际存储格式的一次性只读导出脚本映射后调用快照装载接口。由于当前工作区没有旧 MsgService 源码和 schema，本服务不猜测其表结构，也不直接连接旧数据库；映射脚本必须在取得真实 schema 后单独实现和对账。
