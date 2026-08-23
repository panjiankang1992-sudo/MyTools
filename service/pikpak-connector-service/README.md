# PikPak Connector Service

PikPak 外部协议适配服务，使用独立 `mytools_pikpak_connector` schema。当前阶段已建立账户、离线操作、稳定对象和 Outbox 的数据契约；默认关闭，不接管旧 DownloadBot watcher。

该服务只允许服务端定义的回环 rclone RC 白名单操作。PikPak 凭据使用 Secret 引用，任务脚本和调用方不能读取 remote key、凭据或任意执行命令。

详细设计见 [17-pikpak-connector-service.md](../design/17-pikpak-connector-service.md)。
