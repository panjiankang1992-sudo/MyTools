# MyTools Gateway

## 技术栈

Java 21 / Spring Boot

## 服务职责

客户端统一入口、鉴权接入、聚合查询与任务进度推送。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/01-mytools-gateway.md)。

现已建立可独立构建的 Java 21 / Spring Boot Gateway MVP，默认监听 `127.0.0.1:23200`。Reader、Drive 和 Download 路由分别受独立的 `GATEWAY_*_ROUTE_ENABLED` 与 `GATEWAY_*_TENANT_ALLOWLIST` 控制；Media 使用单独的 `GATEWAY_MEDIA_ROUTE_ENABLED` 总开关。所有路由默认关闭，关闭时不会调用对应领域服务，因此现有 MyTools 入口不受影响。Gateway 校验 Bearer 会话，从可信主体注入 `ownerId`，并用各领域独立内部令牌调用下游；客户端不能提供或覆盖 owner。

首批路由为 `/api/app/v1/reader/shelves`、`/progress` 和 `/markers`。Gateway 只转发自己重建的载荷、内部令牌和规范化 UUID `X-Correlation-Id`，不转发客户端内部头。Reader 对应领域接口现要求服务令牌，防止绕过 Gateway 伪造 owner。
Reader 还开放书源搜索长任务的创建、状态查询和取消；owner 由认证主体注入，响应不返回 Scheduler 任务 ID。

Download 首批路由为创建 HTTPS 下载、查询状态、查询结果摘要和取消任务。创建入口只接受幂等键、HTTPS URL、安全文件名和大小上限；Gateway 重建下游载荷并绑定可信 owner。查询与取消使用 owner 绑定的内部接口，对错误租户统一返回不存在，响应不包含源 URL 或下游 `parameters`。

Drive 路由包括安全账户摘要列表、目录查询、`POST /accounts/{accountId}/refresh-index`、`GET /operations/{operationId}` 和取消接口。Gateway 使用已验证用户 ID 构造内部 `ownerId`，不返回 remote key、外部账户标识或 Secret 引用，并以 `DRIVE_INTERNAL_TOKEN` 调用 Drive Service；文件复制、移动和删除仍保留在旧入口。

Media 路由包括媒体分页、单项查询、播放进度写入，以及目录扫描长任务的创建、状态查询和取消。Gateway 只从认证主体注入 owner；实际目录必须命中 Executor 配置的允许根，扫描可选择在成功摄取后继续创建媒体分析子任务。

Messaging 路由默认由 `GATEWAY_MESSAGING_ROUTE_ENABLED=false` 关闭，开放 owner-bound 入站消息分页和详情。响应保留正文与可展示附件信息，但移除 provider file id、Provider 账户键、来源 URL、外部消息 ID和会话键。
附件分段可通过 Gateway 创建异步下载任务，并查询或取消；响应不暴露 Scheduler 任务 ID和 Download Ingestion 请求 ID。
出站邮件通过 `POST /api/app/v1/messages/deliveries/email` 创建，客户端只提供幂等键、收件人、主题和正文；状态查询与取消使用同一 owner 边界，响应不暴露 Scheduler 或 Provider 标识。

Identity 首批入口为 `POST /api/app/v1/identity/login`、`/refresh` 和 `/logout`，由 `GATEWAY_IDENTITY_ROUTE_ENABLED=false` 独立控制。Gateway 使用严格请求模型重建登录和刷新载荷，不转发客户端内部头或 Cookie；注销不接受客户端 session ID，只撤销访问令牌实时校验结果绑定的 Identity 会话。Identity 的认证失败、请求错误和服务不可用分别映射为稳定 Gateway 错误。启用该入口时，`IDENTITY_VALIDATION_MODE` 必须至少为 `DUAL`，否则运行时门禁仍拒绝签发或撤销新会话，避免新令牌无法用于受保护路由。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。

## Identity 切换模式

- `LEGACY`（默认）：只执行原 MyTools JWT 与 `t_token` 会话校验，不调用 Identity。
- `DUAL`：原校验成功时立即使用旧结果；仅在旧令牌失败时调用 Identity，支持客户端分批重新登录。
- `IDENTITY`：只接受 Identity JWT 与服务端会话校验结果，必须在完成迁移和回滚演练后启用。

Reader 电子书导入通过 Gateway 提供创建、查询、取消和目录接口，由可信主体注入 `ownerId`，响应不暴露内部调度任务标识。

Reader 书源发现和健康检查通过 Gateway 提供 owner-bound 创建、查询与取消接口，继续使用 Reader 已有脚本任务和数据表。

Reader 章节预取通过 Gateway 提供创建、查询和取消接口，章节缓存查询同样由可信主体绑定 owner；内部 Scheduler 任务标识不对外暴露。

Drive Gateway 提供同 owner 账户间的受控单对象复制入口，目标只读检查和 Provider 绑定由 Drive 校验，实际写入、复验与补偿由 Storage Gateway 执行。

配置项为 `IDENTITY_VALIDATION_MODE`。远程校验失败时关闭授权，不降级为未校验身份。

旧 MyTools 新增 `POST /internal/v1/gateway/tokens/validate`，使用 `GATEWAY_INTERNAL_TOKEN` 自校验并以 JSON body 接收访问令牌，避免旧公开校验接口把 token 放入 URL。`DUAL` 仅在旧服务明确返回 inactive 时尝试 Identity；旧服务网络或协议异常时直接关闭授权。
