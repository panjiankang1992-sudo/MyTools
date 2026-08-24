# DSH Connector Service 详细设计

## 边界

DSH Connector 隔离外部 DSH RPC、长连接事件和用户会话授权。它不属于 Identity，因为会话由外部 DSH 创建；也不属于 Messaging，因为 `last_seq` 是外部事件流检查点。旧 MyTools 继续承载面向用户的 RPC、WebSocket 和 SSE，不进行切流；新增的探测词接口仅供任务 Executor 调用。

## 数据与接口

- `dsh_session_binding`：所有者、外部会话 ID、工作区、状态、最新事件序号和时间。
- `dsh_session_migration`：旧 ID、迁移键和不可变载荷摘要。
- 内部 API：按用户查询、单调推进序号、归档、迁移及迁移对账。
- `POST /internal/v1/dsh/probe-terms`：使用任务实例 UUID 创建确定性的短生命周期 DSH 会话，在 120 秒硬上限内生成 1 至 5 个探测词，并在结束时取消会话。接口只接受内部 Bearer Token，RPC 地址只能配置为回环 HTTP，且只开放创建、提示、历史和取消四个方法。

## 迁移

`dsh_migrate_legacy_sessions` 在旧 `my_tools` schema 上冻结 `t_dsh_session_binding.id` 高水位并分页读取。批次导入使用目标本地事务；同一旧 ID 的相同内容重放为跳过，内容变化为拒绝。离线门禁要求 dry-run、正式导入和重放的高水位、数量及源摘要一致，重放全部跳过且目标数量闭合。

书源 `PROBE` 已首先迁移为任务能力：单节点 `reader_probe_search` 调用 Connector 生成冻结词集，再创建多节点 `reader_source_search` 直接子任务并汇总结果。该路径仍由 Reader 旁路开关控制，不改变旧搜索响应。完整的面向用户 DSH RPC 和事件流只有在新 Connector 实现并验证事件桥后才切换。

当前 Gateway 已旁路开放会话绑定查询与归档，使用可信主体注入 `ownerId`，并由独立开关默认关闭。外部事件序号推进仍是 Connector 内部能力；完整 RPC、WebSocket 和 SSE 尚未切换。`DSH_CONNECTOR_RPC_ENABLED` 默认关闭，只有部署到具备 DSH 回环访问能力的专用执行节点后才允许启用。
