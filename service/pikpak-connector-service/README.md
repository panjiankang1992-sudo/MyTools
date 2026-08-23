# PikPak Connector Service

PikPak 外部协议适配服务，使用 Java 21、Spring Boot 和独立
`mytools_pikpak_connector` schema。已实现账户登记、幂等离线操作、文件集合稳定性观察、
异步受控移动、取消收敛、Outbox 和内部 HTTP API；默认关闭，不接管旧 DownloadBot watcher。

该服务只允许服务端定义的回环 rclone RC 白名单操作。PikPak 凭据使用 Secret 引用，任务脚本和调用方不能读取 remote key、凭据或任意执行命令。

主要接口：

- `POST /api/internal/v1/pikpak/accounts`：登记账户路由，响应不返回 Secret 引用和 remote key。
- `POST /api/internal/v1/pikpak/operations`：按业务幂等键创建操作，数据库只保存输入 SHA-256。
- `POST /api/internal/v1/pikpak/operations/{id}/advance`：执行一次有界状态推进。
- `GET /api/internal/v1/pikpak/operations/{id}`：读取脱敏状态和稳定对象。
- `POST /api/internal/v1/pikpak/operations/{id}/cancel`：取消并按移动阶段安全收敛。

首次推进会再次携带 magnet URI，用于与已保存摘要核对后提交；服务不会把原文写入数据库、
Outbox 或响应。服务和 Scheduler 任务均保持禁用，完成 Storage Gateway 物化与旧新对账前不得开启。

详细设计见 [17-pikpak-connector-service.md](../design/17-pikpak-connector-service.md)。
