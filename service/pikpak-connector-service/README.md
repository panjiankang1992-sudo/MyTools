# PikPak Connector Service

PikPak 外部协议适配服务，使用 Java 21、Spring Boot 和独立
`mytools_pikpak_connector` schema。已实现账户登记、幂等离线操作、文件集合稳定性观察、
异步受控移动、取消收敛、Outbox 和内部 HTTP API。固定目录 watcher 通过独立持久化状态机
按顶层文件或目录分批，稳定后由定时扫描任务创建 Download Request；逐文件任务全部成功后
才把来源移动到服务端配置的备份目录。

该服务只允许服务端定义的回环 rclone RC 白名单操作。PikPak 凭据使用 Secret 引用，任务脚本和调用方不能读取 remote key、凭据或任意执行命令。

主要接口：

- `POST /api/internal/v1/pikpak/accounts`：登记账户路由，响应不返回 Secret 引用和 remote key。
- `POST /api/internal/v1/pikpak/operations`：按业务幂等键创建操作，数据库只保存输入 SHA-256。
- `POST /api/internal/v1/pikpak/operations/{id}/advance`：执行一次有界状态推进。
- `GET /api/internal/v1/pikpak/operations/{id}`：读取脱敏状态和稳定对象。
- `POST /api/internal/v1/pikpak/operations/{id}/cancel`：取消并按移动阶段安全收敛。
- `POST /api/internal/v1/pikpak/watchers`：配置账户固定监听目录、备份目录和稳定窗口。
- `POST /api/internal/v1/pikpak/watchers/scan`：扫描全部启用 watcher，仅返回稳定批次。
- `GET /api/internal/v1/pikpak/watch-batches/{id}`：读取批次和固定 Provider 路径。
- `POST /api/internal/v1/pikpak/watch-batches/{id}/archive`：下载成功后异步归档来源。

首次推进会再次携带 magnet URI，用于与已保存摘要核对后提交；服务不会把原文写入数据库、
Outbox 或响应。READY 响应只提供 Storage Provider UUID 和逻辑远端路径，不返回 remote key；
父任务据此创建逐对象物化、资产登记和结果回写子任务。服务和 PikPak 父任务默认禁用，完成
真实 rclone 集成与旧新摘要对账前不得开启。

提交 `addurl` 前必须通过 `operations/mkdir` 创建操作专属目录。rclone 对不存在的目录会
回退到默认收件箱，导致后续隔离目录查询失败；创建失败时禁止继续提交。空目录响应中的
`list: null` 按空集合继续有界观察，缺失 `list` 字段仍视为协议错误。

启用账户前还需验证 `STORAGE_RCLONE_SERVE_URL` 的只读流路由：如果使用 combine remote，
必须存在 `<remoteKey>=<remoteKey>:` 映射。RC 列目录成功不代表读取服务也可访问该账户；
缺失映射会在云端 READY 后造成 Storage Gateway 502。上线校验应包含实际文件读取及哈希验证。
若 systemd 的 `ExecStartPre` 使用 `RCLONE_DRIVE_UPSTREAMS` 重建映射，应将映射保存在
该服务的环境文件中，不能只修改生成的 rclone 配置，否则重启会覆盖修复。
只读流服务与 RC 是独立进程，RC 移动后的文件不会自动刷新读取服务的 VFS 目录缓存。
即时落盘链路应设置 `RCLONE_DIR_CACHE_TIME=0s` 与 `RCLONE_POLL_INTERVAL=0`，避免默认
五分钟目录缓存将刚完成的文件误判为不存在；文件内容本身的流式缓冲不受影响。

旧账户元数据通过 Scheduler 的 `pikpak_migrate_legacy_accounts` 手工任务导入。任务要求为每个
旧 `externalKey` 显式提供 Storage Provider UUID 和 `secret://` 引用，不能从旧配置猜测映射。
应先使用 `dryRun=true` 校验导出摘要和映射覆盖，再正式执行。正式导入的账户一律保持禁用，
即使旧配置处于启用状态；完成 Provider、Secret 和真实内容摘要验证后才能逐账户启用。

详细设计见 [17-pikpak-connector-service.md](../design/17-pikpak-connector-service.md)。
