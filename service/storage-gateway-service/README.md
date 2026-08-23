# Storage Gateway Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

本地文件与 rclone 等存储后端的安全操作。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/09-storage-gateway-service.md)。

已实现 Java 21 / Spring Boot 的本地受管根和远端 Provider 基础能力，并使用独立 `mytools_storage` schema。服务默认监听 `127.0.0.1:23240`，通过 `STORAGE_DB_*`、`STORAGE_DEFAULT_ROOT_*`、`STORAGE_MAXIMUM_UPLOAD_BYTES` 和 `STORAGE_INTERNAL_TOKEN` 配置。

Executor 和其他内部服务先调用 `POST /api/internal/v1/storage/uploads` 幂等创建上传会话，再通过 `PUT /api/internal/v1/storage/uploads/{id}/content` 流式写入。Storage Gateway 负责限制大小、校验 SHA-256、拒绝绝对路径、目录穿越和符号链接逃逸，并在同一受管根中原子发布；响应只暴露 `storage://root/path`，不暴露物理路径。rclone、WebDAV、S3 和访问票据仍待后续阶段实现。

内部任务可通过 `GET /api/internal/v1/storage/objects/content?rootName=...&path=...` 流式读取已发布对象。读取与写入执行相同的真实路径和符号链接边界检查，物理路径不会进入 Scheduler 参数或脚本结果。

远端账户通过 `POST /api/internal/v1/storage/providers` 注册，只持久化 `secretRef`，响应不返回 remote 键或密钥引用。`GET /api/internal/v1/storage/providers/{id}/objects` 只允许调用服务端配置的回环 rclone RC `operations/list`，调用方不能提交 remote 名称或任意 RC 命令。递归扫描、复制、移动和同步仍必须走异步任务。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
