# Identity Service 详细设计

## 职责

负责用户、角色、权限、登录、JWT、刷新令牌、设备会话、验证码生成和核销。邮件实际投递由 Messaging Service 或消息投递任务完成。

## 数据模型

- `users`、`roles`、`user_roles`。
- `sessions`：访问和刷新会话、版本、撤销状态。
- `verification_codes`：用途、哈希、有效期、核销状态。
- `login_attempts`：失败计数和锁定窗口。
- `identity_outbox`：验证码投递等事件。

## 任务化操作

- `message_send_email`：发送验证码、找回密码和安全通知。
- `identity_cleanup_expired_sessions`：定时清理。
- `identity_bulk_role_migration`：批量迁移。

验证码生成、校验和核销仍是 Identity 的同步事务；发送失败不能删除已生成记录，可允许用户在限流约束下重发。

## 脚本与 DML

- 清理和批量迁移脚本可使用 Identity 专用 DML 账号。
- DML 账号只允许访问 Identity schema，不得读取密码散列之外的敏感导出。
- 用户注册、角色变更、会话撤销必须调用领域 API，避免绕过审计和安全规则。

## 迁移

1. 已建立独立 `mytools_identity` schema 和服务 MVP，覆盖用户、角色、会话、验证码、登录失败锁定及 Outbox；短期 JWT 绑定服务端会话，刷新令牌只存 SHA-256 并在每次刷新时原子轮换，撤销实时参与校验。MyTools 原登录仍为权威路径。
2. 已提供 `identity_migrate_users` 1.1.0 手工用户迁移任务。MyTools 首页冻结用户 ID 高水位，Identity 批次 API 支持 dry-run、显式迁移键、每用户幂等审计和目标集合摘要；受保护接口只导出用户、BCrypt 哈希和角色，不复制旧访问或刷新令牌，迁移后必须重新登录建立新会话。
3. Gateway 已提供默认关闭的登录、刷新和当前会话注销代理，只重建稳定请求字段；注销只使用实时校验得到的会话 ID。启用代理时必须同时将令牌校验设为 `DUAL` 或 `IDENTITY`，旧登录入口仍保持权威。
4. SMTP 调用替换为创建 `message_send_email` 任务。
5. 建立独立 schema 与服务账号。
6. 双写会话数据并核对后切换认证入口。
7. Gateway 完成远程切换后删除单体认证实现。

### 用户迁移证据

源任务按用户 ID 排序，对用户稳定字段和排序后的角色使用长度前缀 SHA-256，再对 `userId + payloadSha256` 生成集合摘要。正式写入与 `identity_user_migration` 审计记录处于同一本地事务；dry-run 回滚用户和审计写入。同一迁移键重放必须全部跳过，目标对账摘要必须等于源集合摘要。

`identity_cutover_gate.py` 仅在 dry-run 与首次正式执行计数一致、重放全部跳过、三次源高水位和摘要相同、目标数量与摘要相同且零拒绝时允许进入认证灰度。旧会话不迁移，因此放量前还必须确认强制重新登录策略和回退窗口。

## 验收

- 验证码不会因任务重复消费而重复生成。
- 邮件任务失败可重试且不改变验证码哈希。
- Token 撤销实时生效。
