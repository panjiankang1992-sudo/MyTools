# Identity Service

独立身份与会话服务，使用 `mytools_identity` schema。当前 MVP 提供用户导入、BCrypt 登录、短期 JWT、刷新令牌轮换、会话校验和实时撤销。旧 MyTools 登录与 JWT 过滤器仍为权威路径，尚未启用流量切换。

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
