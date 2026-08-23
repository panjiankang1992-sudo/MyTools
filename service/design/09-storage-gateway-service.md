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

- `storage_scan_root`、`storage_copy_tree`、`storage_move_tree`。
- `storage_compute_checksum`、`storage_sync_remote`。

当前已实现 `storage_scan_root`、`storage_copy_tree`、`storage_move_tree`、`storage_compute_checksum` 和 `storage_sync_remote`。复制与同步只允许服务端登记的来源/目标 Provider 和相对路径，rclone remote 键不会进入 Scheduler 参数；远端 RC job 标识持久化到操作聚合，特殊步骤负责停止远端 job 并回写失败、超时或取消终态。校验和任务只携带不透明操作 UUID，Scheduler 使用 Storage Root 服务端登记的 `storage.mount.<rootName>` 约束选择挂载节点，再通过节点本地 Storage Gateway 流式读取。

原生 WebDAV 和 S3 连接器已实现单级目录查询：Provider 只保存服务端端点、必要的非敏感路由参数和 `secretRef`，凭据按调用从密钥解析器获取；协议层禁用重定向和 XML 外部实体，限制响应大小、对象数以及返回对象必须是请求目录的直接子项。S3 使用 SigV4、ListObjectsV2 和有界分页，兼容临时会话令牌。现有 rclone Provider 契约保持兼容且仍是所有耗时远端任务的默认执行后端；WebDAV/S3 原生异步写操作尚待实现。

远端移动采用持久化阶段状态机：对目标 Provider 和规范化路径建立排他写入栅栏，确认目标不存在后执行复制，通过 `operations/check` 下载比对来源和目标，再清理来源。复制或验证阶段失败、超时和取消会停止当前 job 并清理目标；来源清理开始后不再回滚已验证目标，而是前向重试来源清理。特殊步骤截止前仍无法收敛时记录 `PURGE_SOURCE` 或 `PURGE_TARGET` 恢复动作并自动创建独立高优先级恢复任务。恢复完成前写入栅栏不会释放。

## 脚本与 DML

脚本使用 Storage 内部 API 或受控 CLI，不接收用户提供的任意 remote 或 shell 命令。操作记录可由服务 API 更新；批量索引可以写暂存表后合并。

## 迁移

1. 已完成独立 `mytools_storage` schema、本地受管根、幂等流式上传、摘要校验和同文件系统原子发布 MVP；继续以 MyTools `drive/rclone` 为目标扩展统一接口。
2. 让 DownloadBot staging 和发布脚本改用受管根配置。
3. 迁移旧 WebDAV/Alist 账户：先注册默认关闭的原生 WebDAV Provider 做目录摘要对账，耗时操作继续使用 rclone Provider；验证后再逐项迁移写操作。
4. Provider 迁移任务先以同一 `migrationKey` 执行 dry-run，保存来源数量、拒绝数量、游标和 SHA-256；正式执行使用相同来源快照和摘要，任何拒绝项或摘要变化都阻止切流。
4. 切换播放和下载票据。
5. 删除旧 `cloudfile/webdav/alist` 通用实现。

## 验收

- 无法越过受管根或提交任意 rclone 命令。
- 原子发布只在同文件系统进行，跨文件系统走复制校验再切换。
- 节点调度遵守存储挂载亲和性。

本地能力已覆盖根内相对路径、目录穿越、符号链接逃逸及跨文件系统发布：原子移动不可用时，先复制到目标目录临时文件，复验大小与 SHA-256，再在目标文件系统内原子切换。受管根亲和标签已接入任务实例不可变节点约束，并由本地校验和任务完成端到端验证。远端 Provider 已覆盖受控目录读取、树复制、下载校验后移动、镜像同步和单用途访问票据；移动失败补偿、前向恢复和目标写入栅栏已纳入状态机验收。
