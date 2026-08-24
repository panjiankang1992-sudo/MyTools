# Storage Gateway Service 详细设计

## 职责

统一本地受管目录、rclone、WebDAV、S3、PikPak 等存储后端，提供安全的流式读写、原子移动、列目录、复制、删除、校验和短期访问票据。Provider 查询通过类型化连接器注册表路由，避免领域服务绑定具体后端。

## 数据模型

- `storage_providers`：类型、配置 Secret 引用和状态。
- `storage_roots`：受管根、用途、节点亲和标签。
- `storage_operations`：异步操作业务记录。
- `access_tickets`：短期、单用途、可撤销票据。

## 同步与任务边界

轻量列表、元数据和小文件流可同步。递归扫描、大文件复制、跨后端移动、校验和同步必须创建任务：

- `storage_scan_root`、`storage_copy_tree`、`storage_copy_tree_native`、`storage_move_tree`。
- `storage_compute_checksum`、`storage_sync_remote`。

当前已实现 `storage_scan_root`、`storage_copy_tree`、`storage_copy_tree_native`、`storage_move_tree`、`storage_compute_checksum` 和 `storage_sync_remote`。复制与同步只允许服务端登记的来源/目标 Provider 和相对路径，rclone remote 键不会进入 Scheduler 参数；远端 RC job 标识持久化到操作聚合，特殊步骤负责停止远端 job 并回写失败、超时或取消终态。校验和任务只携带不透明操作 UUID，Scheduler 使用 Storage Root 服务端登记的 `storage.mount.<rootName>` 约束选择挂载节点，再通过节点本地 Storage Gateway 流式读取。

原生 WebDAV 和 S3 连接器已实现单级目录查询：Provider 只保存服务端端点、必要的非敏感路由参数和 `secretRef`，凭据按调用从密钥解析器获取；协议层禁用重定向和 XML 外部实体，限制响应大小、对象数以及返回对象必须是请求目录的直接子项。S3 使用 SigV4、ListObjectsV2 和有界分页，兼容临时会话令牌。现有 rclone Provider 契约保持兼容且仍是耗时远端树任务的默认执行后端。

原生写入首批开放 `COPY_OBJECT`：来源支持 RCLONE、WebDAV 或 S3，目标支持 WebDAV 或 S3。操作创建阶段验证连接器能力并预占目标路径栅栏，任务参数只携带 UUID。Executor 通过操作专属端点有界下载来源、计算摘要、条件 PUT 目标、复读目标并逐字节摘要对账；Gateway 不提供可由脚本指定 Provider 或路径的通用写端点。WebDAV 和 S3 使用 `If-None-Match: *` 阻止覆盖，V10 持久化本操作确认创建目标的所有权，补偿步骤只有在所有权成立时才删除；既有目标只复验不删除。S3 GET/PUT/DELETE 采用 SigV4，条件头和会话令牌纳入签名，并使用任务复读 SHA-256 验证 `UNSIGNED-PAYLOAD`。Gateway 向 Executor 返回目标连接器的真实上限，S3 单次 PutObject 最多 5 GiB，超限对象在读取正文前失败。成功终态在复验后的独立步骤提交，失败、超时和取消步骤补偿后再提交对应终态。

原生递归复制使用独立的 `COPY_TREE_NATIVE` 类型，既有 `COPY_TREE` 继续保持 rclone 行为。父任务先广度遍历来源树，把完整对象清单冻结到 `storage_operation_item`，对象数量超过 `maximumObjects` 时在创建任何子任务前失败。冻结完成后，每个普通文件按稳定幂等键创建一个 `COPY_OBJECT` 子操作，并在 `storage_operation_child` 保存父子关系和派生后的目标路径；目标路径只能由 Gateway 按父操作来源根与目标根计算，脚本不能提交任意目标。父任务轮询所有子操作，只有全部成功才能提交成功终态。失败、超时和取消特殊步骤通过父操作接口级联取消仍在运行的子任务，再写入父终态。目录本身暂不创建空目录，因此空目录不会在目标端物化；原生移动和同步仍待实现。

远端移动采用持久化阶段状态机：对目标 Provider 和规范化路径建立排他写入栅栏，确认目标不存在后执行复制，通过 `operations/check` 下载比对来源和目标，再清理来源。复制或验证阶段失败、超时和取消会停止当前 job 并清理目标；来源清理开始后不再回滚已验证目标，而是前向重试来源清理。特殊步骤截止前仍无法收敛时记录 `PURGE_SOURCE` 或 `PURGE_TARGET` 恢复动作并自动创建独立高优先级恢复任务。恢复完成前写入栅栏不会释放。

## 脚本与 DML

脚本使用 Storage 内部 API 或受控 CLI，不接收用户提供的任意 remote 或 shell 命令。操作记录可由服务 API 更新；批量索引可以写暂存表后合并。

## 迁移

1. 已完成独立 `mytools_storage` schema、本地受管根、幂等流式上传、摘要校验和同文件系统原子发布 MVP；继续以 MyTools `drive/rclone` 为目标扩展统一接口。
2. 让 DownloadBot staging 和发布脚本改用受管根配置。
3. 迁移旧 WebDAV/Alist 账户：先注册默认关闭的原生 WebDAV Provider 做目录摘要对账，耗时操作继续使用 rclone Provider；验证后再逐项迁移写操作。
4. Provider 迁移任务先以同一 `migrationKey` 执行 dry-run，保存来源数量、拒绝数量、游标和 SHA-256；正式执行使用相同来源快照和摘要，任何拒绝项或摘要变化都阻止切流。
5. 目录对账报告必须证明 Storage 操作属于迁移后的 Provider、类型为成功的根扫描，并同时匹配 Drive 索引数量和集合摘要；其他成功操作不得伪装成扫描证据。
4. 切换播放和下载票据。
5. 删除旧 `cloudfile/webdav/alist` 通用实现。

## 验收

- 无法越过受管根或提交任意 rclone 命令。
- 原子发布只在同文件系统进行，跨文件系统走复制校验再切换。
- 节点调度遵守存储挂载亲和性。
- 原生对象写入必须经过 Scheduler，复读摘要不一致时不得保留目标。

本地能力已覆盖根内相对路径、目录穿越、符号链接逃逸及跨文件系统发布：原子移动不可用时，先复制到目标目录临时文件，复验大小与 SHA-256，再在目标文件系统内原子切换。受管根亲和标签已接入任务实例不可变节点约束，并由本地校验和任务完成端到端验证。远端 Provider 已覆盖受控目录读取、树复制、下载校验后移动、镜像同步和单用途访问票据；移动失败补偿、前向恢复和目标写入栅栏已纳入状态机验收。

内部任务现在可以按 Provider UUID、逻辑相对路径和最大字节数流式读取远端普通文件。Gateway
在服务端解析 remote key 和凭据；RCLONE 首版只调用回环 RC `operations/cat`，同时检查
Content-Length 与实际读取字节数。该同步接口是任务原语，批量下载、PikPak 物化和资产发布仍由
Scheduler 任务触发。
