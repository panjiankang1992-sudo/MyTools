# Telegram Connector Service

Telegram Bot API 原子适配器，负责长轮询、标准化入站消息、凭据隔离的附件流代理和原渠道文本回复。业务分类、任务创建、下载和通知仍由 Messaging、Message Automation 与 Download Ingestion 处理。

默认监听 `127.0.0.1:23257`。设置 `TELEGRAM_CONNECTOR_API_BASE_URL` 可切换到 Local Bot API，以支持大文件下载。
