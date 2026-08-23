# MyTools Gateway 详细设计

## 定位与职责

Gateway 是 App、Web、MCP 和管理后台的统一入口，负责认证接入、路由、客户端协议适配、聚合查询和任务进度推送。它不执行脚本、不访问其他服务数据库、不持有下载或分析状态。

## 接口

- `/api/app/v1/auth/**`：代理 Identity。
- `/api/app/v1/media/**`：聚合 Media Library 与任务摘要。
- `/api/app/v1/drive/**`：代理 Drive。
- `/api/app/v1/reader/**`：聚合 Reader 查询和任务进度。
- `/api/app/v1/tasks/{id}`：返回面向用户裁剪后的任务状态。
- `/api/app/v1/task-events`：SSE/WebSocket 推送。

## 实现设计

- 使用统一 `correlation_id` 贯穿下游调用。
- 只把用户身份和服务调用令牌向下传递，不转发客户端任意内部头。
- 任务创建接口返回 `202 Accepted + taskInstanceId`。
- 聚合接口设置独立超时和部分降级，不因任务平台暂时不可用阻塞直接数据查询。
- 不直接暴露脚本命令、数据库连接、节点地址和内部错误栈。
- Reader 首批代理已实现可信主体 owner 注入、请求载荷重建、服务令牌、关联标识、默认关闭开关和用户 ID 白名单；全局开关与白名单必须同时命中，客户端不能通过 query/body/header 覆盖 owner。
- Drive 首批只读代理已实现独立默认关闭开关、独立用户白名单和服务令牌；账户目录查询的 owner 仅从已验证主体注入，客户端只允许提交账户 ID 与父路径。
- Identity 首批登录、刷新和当前会话注销入口已使用独立默认关闭开关、严格请求模型和稳定错误映射；注销会话 ID 只来自实时令牌校验，客户端不能指定其他会话。开启入口时校验模式必须为 `DUAL` 或 `IDENTITY`，避免签发后无法使用的新令牌。

## 迁移

1. 保留现有 Controller 路径，内部改为领域 Facade；JWT 过滤器已支持默认 `LEGACY`、迁移期 `DUAL` 和最终 `IDENTITY` 三种显式模式，默认模式完全不调用远端服务。
2. 为耗时接口增加异步版本，返回任务 ID。
3. 客户端适配任务查询和取消。
4. Reader Facade、Drive 目录只读查询及 Identity 登录/刷新/注销已替换为有界超时 HTTP 客户端，分别默认关闭；其余领域能力按相同模式逐项迁移。
5. 删除 Gateway 中的 Mapper、Job、FFmpeg、邮件和文件扫描依赖。

## 验收

- Gateway 重启不影响后台任务。
- 客户端只能查看自己有权访问的任务。
- Reader 客户端即使提交 `ownerId` 也不能查询或改写其他用户数据，关闭灰度开关时不产生任何认证或下游调用，开启开关但未进入白名单时不调用 Reader。
- Drive 客户端不能控制内部 `ownerId`；关闭 Drive 灰度开关或未进入独立白名单时不调用认证服务或 Drive Service。
- Identity 注销不能接受或推导客户端提供的会话 ID，旧会话令牌不能撤销 Identity 会话，撤销后访问和刷新校验均失败。
- 所有耗时接口在短时间内返回任务 ID。
