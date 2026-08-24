# DSH Connector Service

DSH Connector 使用 Java 21、Spring Boot 和独立 `mytools_dsh_connector` schema，逐步接管旧 MyTools 中的 DSH 外部集成。当前实现用户会话绑定、最新事件序号、归档、历史迁移，以及供 Executor 调用的有界探测词原子接口；旧面向用户的 RPC、WebSocket 与 SSE 路径保持不变。

`dsh_migrate_legacy_sessions` 使用旧库只读账号，在 Repeatable Read 一致性视图中迁移 `t_dsh_session_binding`。执行顺序为 dry-run、正式导入、正式重放和目标对账。新服务使用 UUID 主键，同时保存旧自增 ID，按旧 ID 和载荷摘要拒绝静默覆盖。

`POST /internal/v1/dsh/probe-terms` 只接受 `DSH_CONNECTOR_INTERNAL_TOKEN`，通过固定回环地址调用白名单 DSH RPC。`DSH_CONNECTOR_RPC_ENABLED=false` 为默认值；启用时还需配置回环地址、工作目录和执行节点 Secret。接口以 Scheduler 任务实例 UUID 创建确定性会话，返回 1 至 5 个长度受限、去重后的词，并始终尝试取消会话。
