# MyTools Gateway

## 技术栈

Java 21 / Spring Boot

## 服务职责

客户端统一入口、鉴权接入、聚合查询与任务进度推送。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/01-mytools-gateway.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。

## Identity 切换模式

- `LEGACY`（默认）：只执行原 MyTools JWT 与 `t_token` 会话校验，不调用 Identity。
- `DUAL`：原校验成功时立即使用旧结果；仅在旧令牌失败时调用 Identity，支持客户端分批重新登录。
- `IDENTITY`：只接受 Identity JWT 与服务端会话校验结果，必须在完成迁移和回滚演练后启用。

配置项为 `IDENTITY_VALIDATION_MODE`。远程校验失败时关闭授权，不降级为未校验身份。
