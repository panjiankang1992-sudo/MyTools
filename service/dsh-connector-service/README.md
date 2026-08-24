# DSH Connector Service

DSH Connector 使用 Java 21、Spring Boot 和独立 `mytools_dsh_connector` schema，逐步接管旧 MyTools 中的 DSH 外部集成。当前阶段只实现用户会话绑定、最新事件序号、归档和历史迁移；旧 RPC、WebSocket 与 SSE 路径保持不变。

`dsh_migrate_legacy_sessions` 使用旧库只读账号，在 Repeatable Read 一致性视图中迁移 `t_dsh_session_binding`。执行顺序为 dry-run、正式导入、正式重放和目标对账。新服务使用 UUID 主键，同时保存旧自增 ID，按旧 ID 和载荷摘要拒绝静默覆盖。
