# MyTools Gateway

## 技术栈

Java 21 / Spring Boot

## 服务职责

客户端统一入口、鉴权接入、聚合查询与任务进度推送。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/01-mytools-gateway.md)。

现已建立可独立构建的 Java 21 / Spring Boot Gateway MVP，默认监听 `127.0.0.1:23200`。Reader 和 Drive 路由分别受 `GATEWAY_READER_ROUTE_ENABLED`、`GATEWAY_DRIVE_ROUTE_ENABLED` 控制，默认均关闭；关闭时直接返回未启用且不会调用认证或领域服务，因此现有 MyTools 入口不受影响。开启灰度后，还必须分别通过 `GATEWAY_READER_TENANT_ALLOWLIST`、`GATEWAY_DRIVE_TENANT_ALLOWLIST` 明确列出允许切流的用户 ID，空名单不会放行任何用户。Gateway 校验 Bearer 会话，从可信主体注入 `ownerId`，并用各领域独立内部令牌调用下游；客户端不能提供或覆盖 owner。

首批路由为 `/api/app/v1/reader/shelves`、`/progress` 和 `/markers`。Gateway 只转发自己重建的载荷、内部令牌和规范化 UUID `X-Correlation-Id`，不转发客户端内部头。Reader 对应领域接口现要求服务令牌，防止绕过 Gateway 伪造 owner。

Drive 首批只读路由为 `GET /api/app/v1/drive/accounts/{accountId}/items?parentPath=`。Gateway 使用已验证用户 ID 构造内部 `ownerId`，以 `DRIVE_INTERNAL_TOKEN` 调用 Drive Service；该阶段不切换写操作和旧入口。

Identity 首批入口为 `POST /api/app/v1/identity/login`、`/refresh` 和 `/logout`，由 `GATEWAY_IDENTITY_ROUTE_ENABLED=false` 独立控制。Gateway 使用严格请求模型重建登录和刷新载荷，不转发客户端内部头或 Cookie；注销不接受客户端 session ID，只撤销访问令牌实时校验结果绑定的 Identity 会话。Identity 的认证失败、请求错误和服务不可用分别映射为稳定 Gateway 错误。启用该入口时，`IDENTITY_VALIDATION_MODE` 必须至少为 `DUAL`，否则运行时门禁仍拒绝签发或撤销新会话，避免新令牌无法用于受保护路由。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。

## Identity 切换模式

- `LEGACY`（默认）：只执行原 MyTools JWT 与 `t_token` 会话校验，不调用 Identity。
- `DUAL`：原校验成功时立即使用旧结果；仅在旧令牌失败时调用 Identity，支持客户端分批重新登录。
- `IDENTITY`：只接受 Identity JWT 与服务端会话校验结果，必须在完成迁移和回滚演练后启用。

配置项为 `IDENTITY_VALIDATION_MODE`。远程校验失败时关闭授权，不降级为未校验身份。

旧 MyTools 新增 `POST /internal/v1/gateway/tokens/validate`，使用 `GATEWAY_INTERNAL_TOKEN` 自校验并以 JSON body 接收访问令牌，避免旧公开校验接口把 token 放入 URL。`DUAL` 仅在旧服务明确返回 inactive 时尝试 Identity；旧服务网络或协议异常时直接关闭授权。
