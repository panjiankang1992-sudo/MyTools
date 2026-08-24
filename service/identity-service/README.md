# Identity Service

独立身份与会话服务，使用 `mytools_identity` schema。当前 MVP 提供用户导入、BCrypt 登录、短期 JWT、刷新令牌轮换、会话校验和实时撤销。旧 MyTools 登录与 JWT 过滤器仍为权威路径，尚未启用流量切换。

`identity_migrate_users` 1.1.0 是手工即时任务，通过 MyTools 受保护分页接口冻结用户 ID 高水位，再经 Identity 批次领域 API 执行 dry-run 或正式迁移。迁移使用显式迁移键、每用户载荷摘要和目标侧审计表支持幂等重放；旧 access/refresh token 不导出、不复制，新会话必须重新登录生成。任务输出只包含高水位、数量和不可逆集合摘要，不包含密码哈希、邮箱或用户名。

内部迁移接口：

- `POST /internal/v1/migrations/legacy-users`：校验或导入最多 100 个旧用户。
- `GET /internal/v1/migrations/legacy-users/{migrationKey}/reconciliation`：重算指定迁移键的目标侧数量和集合摘要。

生产切换前必须先执行 dry-run，再把报告中的 `sourceHighWater` 作为 `snapshotHighWater` 参数传给正式执行和正式重放；四次操作使用同一迁移键，并保存 dry-run、正式执行、正式重放和目标对账四份 JSON，最后运行 `python3 service/scripts/identity_cutover_gate.py`。该闸门只读取本地证据文件，不读取密码哈希，也不修改 Gateway 或 Identity 开关。

Gateway 已提供默认关闭的 `/api/app/v1/identity/login`、`/refresh` 和 `/logout` 代理。注销通过实时校验结果撤销当前 Identity 会话，不允许客户端指定会话标识。启用 `GATEWAY_IDENTITY_ROUTE_ENABLED` 前必须先迁移并对账用户，并把 `IDENTITY_VALIDATION_MODE` 设置为 `DUAL` 或 `IDENTITY`；关闭开关即可恢复旧登录入口，且不会删除 Identity schema 中已创建的会话。

## 技术栈

Java 21 / Spring Boot

## 服务职责

用户、角色、会话、认证与验证码业务。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/02-identity-service.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
