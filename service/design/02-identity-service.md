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
2. 已提供手工用户迁移任务，受保护接口只导出用户、BCrypt 哈希和角色，不复制旧访问或刷新令牌；迁移后必须重新登录建立新会话。
2. SMTP 调用替换为创建 `message_send_email` 任务。
3. 建立独立 schema 与服务账号。
4. 双写会话数据并核对后切换认证入口。
5. Gateway 改为远程调用，删除单体认证实现。

## 验收

- 验证码不会因任务重复消费而重复生成。
- 邮件任务失败可重试且不改变验证码哈希。
- Token 撤销实时生效。
