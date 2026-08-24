# Reader Service 详细设计

## 职责

负责书架、阅读进度、书签、书源配置、搜索缓存、章节缓存、电子书目录和导入业务。已保存数据直接查询，外部书源访问和文件处理全部任务化。

## 数据模型

- `shelf_books`、`reading_progress`、`reader_markers`。
- `book_sources`、`book_source_versions`。
- `book_search_requests`、`book_search_results`、`book_search_task_bindings`。
- `ebook_asset`、`ebook_catalog_entry`、`chapter_cache`。
- `library_rebuild_request`、`library_index_generation`、`library_index_entry`。
- `legacy_reader_key_map`、`legacy_reader_migration_item`：旧书 ID 映射和幂等迁移审计。

## 任务类型

- `reader_source_search`：多节点分片搜索。
- `reader_source_discovery`、`reader_source_health_check`。
- `reader_import_ebook`、`reader_extract_metadata`。
- `reader_build_catalog`、`reader_prefetch_chapters`。
- `reader_cleanup_chapter_cache`、`reader_reindex_library`。

书源搜索任务由 Scheduler 展开为不可变的多节点分片执行目标，目标按照书源序号确定性分片并允许部分成功；Reader Service 汇总全部目标结果、合并去重并通过事件推送进度。需要独立生命周期的发现、导入等工作仍通过脚本创建子任务。

旧 MyTools 的搜索旁路不直接调用 Scheduler。启用 `READER_SEARCH_SIDECAR_ENABLED` 后，它把规范化关键词、模式、页码和同一批书源不可变快照提交到 Reader Service；Reader Service 在独立 schema 中保存请求、参数与任务绑定后再创建 `reader_source_search`。稳定幂等键包含整个旧请求指纹和策略版本。当前仅旁路语义一致的 `EXACT` 和 `FUZZY`，`PROBE` 在完成关键词扩展任务化前仍只走旧实现。旁路失败不影响旧内存搜索，开关默认关闭；创建、查询和取消接口统一校验 `READER_INTERNAL_TOKEN`。

## 查询边界

- 查询书架、进度、书签、已缓存搜索结果和章节是同步操作。
- 新 Reader Service 已提供书架、进度和标记的同步查询、墓碑同步与乐观版本写入；迁移期仅监听回环地址，最终由 Gateway 使用认证主体限定 owner。
- 未缓存的多书源搜索返回 `202 + taskId`。
- 章节即时读取若预计在交互延迟内可同步；超时或需要多源回退时转为任务。

## DML

搜索和索引脚本可写专用暂存表，必须带任务与分片 ID，使用唯一键幂等。书架、阅读进度和书签必须走 API，防止覆盖用户新数据。

## 迁移

1. 将现有书源 Runtime 搜索封装为脚本包。
2. 已完成单节点旁路验证并保持现有缓存表。
3. 已使用 Scheduler 原生执行目标完成多节点分片、原始结果暂存和部分成功聚合。
4. 已将书源发现迁为受限公网脚本和 Reader Service 版本化写入，健康检查迁为原生多节点分片任务，书源电子书导入迁为 Storage Gateway 原子发布任务，TXT/EPUB/PDF/MOBI 元数据解析、持久化目录构建及指定章节预缓存已脚本化。
5. 已将过期缓存、停用书源缓存和旧书源版本缓存清理迁为受限批次任务，并配置失败、超时、取消终态步骤。
6. 已实现书库索引 generation 重建：冻结成功 `ebook_asset` 快照，按批次写入不可见 generation，完成性检查通过后在事务中原子切换 active generation；异常终态不发布半成品，重建不触碰书架、阅读进度或书签。
7. 已实现旧 MyTools 书架、阅读进度和书签的受保护只读导出、dry-run、复合游标、依赖顺序导入、稳定标识、摘要报告和幂等审计；删除墓碑保留在迁移载荷中，不会因迁移重新出现。
8. 已将书架、进度和标记的墓碑与版本字段规范化，并实现直接同步业务 API；进度和标记通过外键及 owner/book key 查找绑定书架，禁止生成孤儿状态。
9. 独立 schema 与 Reader Service MVP 已建立；完成实际数据对账后再由 Gateway 切换远程接口。
10. 旧 MyTools 的书源电子书导入已增加默认关闭的持久化旁路。旁路先按 owner 和 `sourceUrl` 解析新 schema 中已迁移书源，只在精确匹配时创建 `reader_import_ebook`；旧任务标识作为幂等键，新链路失败或书源尚未迁移均不影响旧导入。
11. 旧 MyTools 的书源发现已增加默认关闭的持久化旁路。旧入口完成公网地址校验后发布不可变请求，Reader Service 以旧任务标识幂等创建 `reader_source_discovery`；新链路失败不影响旧线程池任务。

## 验收

- 取消搜索后不会继续产生新分片结果。
- Gateway 创建搜索时只接受书源快照和搜索参数并注入 owner，查询和取消不能跨 owner；响应不暴露 Scheduler 任务 ID。
- 电子书导入继续复用 `reader_import_ebook` 脚本任务；Gateway 绑定 owner，Reader 对查询、取消和目录读取校验 owner，现有电子书资产无需搬迁。
- 书源发现与健康检查继续复用已有脚本任务和不可变书源版本；Gateway 只补 owner-bound 生命周期，不引入切流、双写或数据搬迁。
- 章节预取复用 `reader_prefetch_chapters` 脚本任务，Gateway 绑定 owner 并提供缓存读取；缓存清理和书库重建仍是内部系统维护任务。
- 单个书源失败不导致所有有效结果丢失。
- 重复分片执行不会产生重复书籍记录。
- 重复章节批次不会产生重复缓存，过期或旧书源版本缓存不会被同步查询返回。
- 缓存清理每批受限且可重试，任何终态都能在维护记录中审计累计删除数。
- 索引重建发布前对同步查询不可见，发布时只有一个 active generation，且不改变用户书架、进度和书签数据。
- Reader 用户状态 dry-run 与正式迁移导出摘要一致，重跑不增加记录，缺少书架映射的进度或书签进入拒绝报告而不产生孤儿行。
- 同步创建不会覆盖已有记录，只有匹配 `expectedVersion` 的更新成功；默认查询隐藏墓碑，显式同步查询能够返回墓碑。
