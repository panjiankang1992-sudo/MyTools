# Storage Gateway Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

本地文件、rclone、原生 WebDAV 与原生 S3 等存储后端的安全操作。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/09-storage-gateway-service.md)。

已实现 Java 21 / Spring Boot 的本地受管根、远端 Provider、访问票据和异步跨 Provider 操作，并使用独立 `mytools_storage` schema。服务默认监听 `127.0.0.1:23240`，通过 `STORAGE_DB_*`、`STORAGE_DEFAULT_ROOT_*`、`STORAGE_DEFAULT_ROOT_NODE_LABEL`、`STORAGE_MAXIMUM_UPLOAD_BYTES` 和 `STORAGE_INTERNAL_TOKEN` 配置。

Executor 和其他内部服务先调用 `POST /api/internal/v1/storage/uploads` 幂等创建上传会话，再通过 `PUT /api/internal/v1/storage/uploads/{id}/content` 流式写入。Storage Gateway 负责限制大小、校验 SHA-256、拒绝绝对路径、目录穿越和符号链接逃逸；同文件系统直接原子发布，遇到嵌套挂载点导致跨文件系统时，复制到目标侧临时文件并复验大小和摘要后再原子切换。响应只暴露 `storage://root/path`，不暴露物理路径。

内部任务可通过 `GET /api/internal/v1/storage/objects/content?rootName=...&path=...` 流式读取已发布对象。读取与写入执行相同的真实路径和符号链接边界检查，物理路径不会进入 Scheduler 参数或脚本结果。

内部任务还可通过
`GET /api/internal/v1/storage/providers/{providerId}/objects/content?path=...&maximumBytes=...`
读取远端 Provider 普通文件。当前仅 RCLONE 实现该原子能力：Gateway 在服务端解析 remote key，
只调用回环 RC 的固定 `operations/cat`，同时校验声明长度并用限流输入流强制执行调用方上限。
该接口用于任务执行器，不作为在线用户下载接口。

远端账户通过 `POST /api/internal/v1/storage/providers` 注册，只持久化 `secretRef`，响应不返回 remote 键、端点或密钥引用。`GET /api/internal/v1/storage/providers/{id}/objects` 按 Provider 类型选择受控连接器：`RCLONE` 调用服务端配置的回环 RC `operations/list`；`WEBDAV` 使用原生 `PROPFIND Depth: 1`，只允许 HTTPS 或测试用回环 HTTP、禁止重定向并限制 XML 响应大小和对象数。WebDAV 凭据当前从 `env://ENVIRONMENT_NAME` 引用的 JSON 对象按需读取，至少包含 `username` 和 `password`，不会写入数据库或响应。

原生 WebDAV 和 S3 支持轻量单级目录查询。S3 使用路径风格 ListObjectsV2 和 SigV4，支持临时会话令牌，单页最多 1000 项、最多 10 页；继续截断时返回稳定上限错误并要求改走扫描任务。S3 Provider 的 `remoteKey` 表示 bucket，`regionName` 表示签名区域，凭据 JSON 至少包含 `accessKeyId` 和 `secretAccessKey`，可选 `sessionToken`。

`COPY_OBJECT` 是首个原生异步写操作：来源可为 RCLONE、WebDAV 或 S3，目标可为 WebDAV 或 S3。创建操作时必须提供非空的服务端 Provider UUID 和相对对象路径，Scheduler 参数仍只有 `operationId`。`storage_copy_object` 在 Executor 工作目录有界暂存来源、计算 SHA-256、通过操作专属条件 PUT 写入目标并复读校验；WebDAV 和 S3 都使用 `If-None-Match: *` 防止覆盖既有对象，V10 所有权标记确保失败、超时或取消步骤只删除本操作确认创建的目标。既有同路径对象只允许复验，永不由补偿步骤删除。成功终态由复验后的独立步骤提交，目标路径写入栅栏防止并发 COPY/MOVE/SYNC 写入相同或上下级路径。

内部调用方通过 `POST /api/internal/v1/storage/operations/{id}/cancel` 取消操作，由 Storage Gateway 统一请求 Scheduler 取消，调用方无需持有 Scheduler 契约或令牌。

S3 对象 GET、PUT、DELETE 使用 SigV4，临时会话令牌和条件写入头均纳入签名；HTTPS/回环端点下使用 `UNSIGNED-PAYLOAD`，写后复读 SHA-256 作为内容完整性门禁。`STORAGE_NATIVE_COPY_MAXIMUM_BYTES` 在 Gateway 与 Executor 两侧必须配置为相同值，默认 20 GiB；Gateway 会把目标连接器的更小上限返回给任务，S3 单次 PutObject 固定最多 5 GiB，任务在下载来源正文前即拒绝超限对象。现有 rclone 任务保持不变。

`COPY_TREE_NATIVE` 提供原生递归树复制。`storage_copy_tree_native` 父任务先冻结完整来源清单，再按普通文件创建 `COPY_OBJECT` 子操作；父子关系持久化到独立表，目标路径由 Gateway 从父操作的来源根和目标根派生。只有全部子操作成功时父操作才允许成功。父任务失败、超时或取消会级联取消未完成子任务。该能力不改变 `COPY_TREE` 的 rclone 契约，也不会物化来源中的空目录；原生移动和同步仍待逐项实现。

`POST /api/internal/v1/storage/operations` 当前开放已落地的 `SCAN_ROOT`。它创建 `storage_scan_root` 调度实例，Executor 广度遍历远端目录并以最多 500 项的批次幂等回写 `storage_operation_item`；对象总量受 `maximumObjects` 硬限制。成功、失败、超时和取消都会回写稳定终态，任务参数只携带 Provider UUID，不携带 remote 键或密钥。

跨 Provider 操作现开放 `COPY_TREE`、`MOVE_TREE` 和 `SYNC_REMOTE`。创建请求只接受来源/目标 Provider UUID 与受限相对路径，服务端解析 remote 键并调用回环 rclone RC 白名单；传给任务脚本的参数只有不透明的操作 UUID，不包含 Provider、路径、remote 名称或 RC 命令。复制和同步使用正确的 `remote:path` Fs 参数调用 `sync/copy` 或 `sync/sync`。相同来源和目标禁止执行。

`MOVE_TREE` 不直接调用破坏性的 `sync/move`，而是使用 `storage_move_tree` 持久化状态机：目标写入栅栏、目标不存在检查、复制、强制下载校验、来源清理。源删除前失败会清理目标，源删除开始后保留已验证目标并重试来源清理。90 秒特殊步骤仍无法收敛时，操作标记为 `STORAGE_025`，同时创建 `storage_recover_move` 任务继续执行持久化的来源或目标清理动作；恢复前目标栅栏保持占用，防止复制、同步或另一个移动操作写入相同或上下级路径。

内部调用方可通过 `POST /api/internal/v1/storage/access-tickets` 为已存在的受管本地对象创建最长一小时的单用途下载票据，并通过撤销接口提前失效。数据库仅保存 Token SHA-256，原始 Token 只出现在创建响应的 `accessUrl` 中；公共下载端点采用条件更新原子消费，并发请求最多一个成功。该能力默认不替换任何旧下载 URL。

`storage_migrate_drive_providers` 1.1.0 是手工即时迁移任务：使用 `migrationKey`、`dryRun` 和可选 `afterId` 读取 Drive 的脱敏账户页，严格拒绝用户名、密码、Token 等非白名单字段；支持 RCLONE、WEBDAV 和 S3 路由元数据，但只传输 Secret 引用。dry-run 不需要写令牌，也不会注册或回绑；正式执行按稳定 Provider 名称幂等注册并将 UUID 回绑 Drive。结果包含导出、接受、绑定、拒绝数量、续跑游标和确定性来源摘要，任务不会自动触发。

成功的 `SCAN_ROOT` 操作可读取稳定排序的对象集合摘要，供 `drive_reconcile_index` 同时比较数量与 SHA-256。摘要采用共享长度前缀协议和黄金向量，避免仅按数量判断造成误切换。

`POST /api/internal/v1/storage/checksum-operations` 为受管本地对象创建 `storage_compute_checksum` 任务。Storage Root 保存节点亲和标签和值，创建任务时将其写入 Scheduler 的 `requiredNodeLabels`，脚本参数只包含校验操作 UUID。Executor 必须注册匹配标签，并通过同节点的 Storage Gateway 流式读取对象、计算大小和 SHA-256 后幂等回写；失败、超时和取消由独立特殊步骤收敛。默认根使用 `storage.mount.managed=present`，部署多个根时每个挂载节点必须显式声明对应标签。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
