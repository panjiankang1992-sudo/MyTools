# DSH Connector Service 详细设计

## 边界

DSH Connector 隔离外部 DSH RPC、长连接事件和用户会话授权。它不属于 Identity，因为会话由外部 DSH 创建；也不属于 Messaging，因为 `last_seq` 是外部事件流检查点。当前只迁移持久化绑定，旧 MyTools 继续承载 RPC、WebSocket 和 SSE，不进行切流。

## 数据与接口

- `dsh_session_binding`：所有者、外部会话 ID、工作区、状态、最新事件序号和时间。
- `dsh_session_migration`：旧 ID、迁移键和不可变载荷摘要。
- 内部 API：按用户查询、单调推进序号、归档、迁移及迁移对账。

## 迁移

`dsh_migrate_legacy_sessions` 在旧 `my_tools` schema 上冻结 `t_dsh_session_binding.id` 高水位并分页读取。批次导入使用目标本地事务；同一旧 ID 的相同内容重放为跳过，内容变化为拒绝。离线门禁要求 dry-run、正式导入和重放的高水位、数量及源摘要一致，重放全部跳过且目标数量闭合。

后续只有在新 Connector 实现并验证 RPC 和事件桥后，Gateway 才改为调用新服务；该后续工作不影响本阶段的数据保全。
