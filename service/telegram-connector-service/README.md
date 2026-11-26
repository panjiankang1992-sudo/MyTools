# Telegram Connector Service

Telegram Bot API 原子适配器，负责长轮询、标准化入站消息、凭据隔离的附件流代理和原渠道文本回复。业务分类、任务创建、下载和通知仍由 Messaging、Message Automation 与 Download Ingestion 处理。

默认监听 `127.0.0.1:23257`。设置 `TELEGRAM_CONNECTOR_API_BASE_URL` 可切换到 Local Bot API，以支持大文件下载。

更新在确认前写入本地 SQLite 收件箱。收件箱默认位于 `/opt/yuyutian/mytools/runtime/telegram/inbox.sqlite3`，可通过 `TELEGRAM_CONNECTOR_INBOX_PATH` 设置绝对路径。目录和文件必须仅限服务用户访问。未投递消息和待合并的相册可跨进程重启恢复。Messaging 瞬时失败会退避重试，永久错误和结果不确定的 Business 转发保留供人工核对。收件箱的更新去重收据保留 48 小时。

`GET /health/live` 检查进程存活。`GET /health` 返回 Bot API 轮询与入站投递就绪状态，含未投递数量、最老待投递记录年龄及终止失败数量。
