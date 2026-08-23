# Reader Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

书架、阅读进度、书源、章节和电子书业务。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/13-reader-service.md)。

已建立独立 `mytools_reader` schema、可独立构建的 Spring Boot 服务，以及 `reader_source_search` 1.1.0 分片脚本。Scheduler 将一个任务实例展开为稳定的执行目标，脚本依据目标序号确定性分配书源，并使用执行实例隔离的 Reader Runtime 命名空间，避免并发分片互相覆盖书源快照。Reader Service 持久化请求和参数快照，幂等创建任务，保存每个执行目标的原始结果，并按规范化书名合并部分成功结果；现有 MyTools 搜索仍为线上权威实现。

MyTools 通过默认关闭的 `READER_SEARCH_SIDECAR_ENABLED` 开关提交同一书源快照。Reader Runtime 密钥仅由 Executor 的 `script-environments.reader_source_search` 注入，不进入 Scheduler 参数或数据库。

服务默认监听 `127.0.0.1:23230`，使用 `READER_DB_*` 连接独立 `mytools_reader` schema，并通过 `TASK_SCHEDULER_URL` 调用 Scheduler。`POST /api/v1/book-searches` 创建搜索，`GET /api/v1/book-searches/{id}` 查询并聚合分片结果，`POST /api/v1/book-searches/{id}/cancel` 取消执行。所有接口仍处于旁路阶段。

书源发现已迁移为 `reader_source_discovery` 1.0.0 脚本任务。脚本只访问经过公网地址校验、响应大小限制和重定向重验的仓库，并以最多 100 条一批调用 Reader Service 内部接口；服务以内容摘要维护不可变书源版本。公开编排接口为 `POST /api/v1/source-discoveries`、`GET /api/v1/source-discoveries/{id}` 和取消接口，内部写入接口必须使用 `READER_INTERNAL_TOKEN`。

书源健康检查使用 `reader_source_health_check` 1.0.0 多节点分片任务。Reader Service 固化本次检查使用的启用书源版本，脚本在执行隔离的 Runtime 命名空间中探测搜索规则，并汇总每个书源的状态、延迟和错误类别。健康观测不会自动修改用户维护的 `enabled` 状态。编排接口为 `POST /api/v1/source-health-checks`、`GET /api/v1/source-health-checks/{id}` 和取消接口。

书源电子书导入使用 `reader_import_ebook` 1.0.0 长任务。Reader Service 固化书源版本和任务参数，脚本逐章读取并在任务工作目录中流式生成有大小边界的 UTF-8 文本，通过 Storage Gateway 校验摘要并原子发布，成功后在 `ebook_asset` 登记稳定 `storage://` URI。后续步骤分别提取元数据并构建持久化目录：TXT/Markdown 目录保存字节范围，EPUB 目录保存经过归档安全校验的 spine 条目，PDF 保存有上限的页引用；脚本以受限批次调用内部写入接口，重试时先清理再重建。编排接口为 `POST /api/v1/ebook-imports`、`GET /api/v1/ebook-imports/{id}`、`GET /api/v1/ebook-imports/{id}/catalog` 和取消接口；客户端不能指定物理目录或任意输出路径。

电子书导入的第四步使用 `asset_register_content` 将已经由 Storage Gateway 验证的 URI、SHA-256 和大小镜像到 Asset Registry。迁移期该步骤失败可忽略，并保留独立补偿任务；Reader 自有 `ebook_asset` 仍是当前权威数据。

章节预取使用独立 `reader_prefetch_chapters` 1.0.0 即时任务。创建请求最多选择 100 个章节序号并冻结当前书源版本；Executor 在隔离 Runtime 命名空间中只读取选中章节，以最多 20 条一批写回 Reader Service。服务端复核 UTF-8 字节数与 SHA-256 后写入带 TTL 的全局章节缓存，并通过请求关联表保证批次重试和任务统计幂等。书源版本变化、书源停用或 TTL 到期后旧缓存不会被查询接口返回。公开接口为 `POST /api/v1/chapter-prefetches`、`GET /api/v1/chapter-prefetches/{id}`、取消接口及 `GET /api/v1/chapter-cache`。

`reader_extract_metadata` 1.0.0 支持 TXT/Markdown、EPUB OPF、基础 PDF 和 MOBI/AZW3 头解析，保留旧实现的 `READY`/`PARTIAL`/`FAILED` 语义，并限制文本大小、ZIP 条目数、展开大小、单条目大小和压缩比。书源导入任务将其作为第二步骤执行，元数据结果写回 `ebook_asset.metadata_json`；该脚本也注册为可独立创建的任务类型。

章节缓存维护使用 `reader_cleanup_chapter_cache` 1.0.0 即时任务。维护请求冻结清理类型、截止时间和每批上限，只把 `maintenanceId` 交给 Scheduler；Executor 通过需要 `READER_INTERNAL_TOKEN` 的 Reader 内部接口逐批清理过期缓存，或清理书源已停用/版本过时的缓存。每批最多 1000 条并先删除预取关联，成功、失败、超时和取消均写回独立维护记录。该能力只维护新 `mytools_reader` schema，不读取或删除 MyTools 现有缓存。

书库索引重建使用 `reader_reindex_library` 1.0.0 即时任务。创建请求冻结 `ebook_asset` 快照时间和批次大小，Executor 只携带 `rebuildId` 循环调用内部批次接口，在不可见 generation 中按内容摘要去重；确认冻结快照无遗漏后，Reader Service 在单个事务中撤销旧 generation 并发布新 generation。失败、超时和取消步骤只记录异常终态，不会发布半成品。重建数据完全可从新 schema 的成功电子书资产再生，且不会读写现有 MyTools 数据，也不会修改 `shelf_book`、`reading_progress` 或 `reader_marker`。

不可再生的旧书架、阅读进度和书签使用手工即时任务 `reader_migrate_legacy_state` 1.0.0 迁移。任务按 `SHELF`、`PROGRESS`、`MARKER` 依赖顺序，通过 MyTools 只读复合游标接口分页读取，再调用 Reader 内部批次接口；每批最多 200 条，目标端保存旧书 ID 映射、稳定 UUID、载荷摘要和幂等审计。必须先以 `{"migrationKey":"reader-state-v1","dryRun":true}` 演练并保存数量与摘要，再用相同 key 设置 `dryRun=false`。接口令牌分别由 `READER_MIGRATION_INTERNAL_TOKEN` 和 `READER_INTERNAL_TOKEN` 注入 Executor，不进入任务参数或结果。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
- 书库索引仅允许显式创建 `reader_reindex_library` 任务，不允许缓存清理隐式重建或覆盖用户书架。
