# Reader Service 详细设计

## 职责

负责书架、阅读进度、书签、书源配置、搜索缓存、章节缓存、电子书目录和导入业务。已保存数据直接查询，外部书源访问和文件处理全部任务化。

## 数据模型

- `shelf_books`、`reading_progress`、`reader_markers`。
- `book_sources`、`book_source_versions`。
- `book_search_requests`、`book_search_results`、`book_search_task_bindings`。
- `ebook_metadata`、`ebook_catalog`、`chapter_cache`。

## 任务类型

- `reader_source_search`：多节点分片搜索。
- `reader_source_discovery`、`reader_source_health_check`。
- `reader_import_ebook`、`reader_extract_metadata`。
- `reader_build_catalog`、`reader_prefetch_chapters`。
- `reader_cleanup_cache`、`reader_reindex_library`。

书源搜索任务由 Scheduler 展开为不可变的多节点分片执行目标，目标按照书源序号确定性分片并允许部分成功；Reader Service 汇总全部目标结果、合并去重并通过事件推送进度。需要独立生命周期的发现、导入等工作仍通过脚本创建子任务。

## 查询边界

- 查询书架、进度、书签、已缓存搜索结果和章节是同步操作。
- 未缓存的多书源搜索返回 `202 + taskId`。
- 章节即时读取若预计在交互延迟内可同步；超时或需要多源回退时转为任务。

## DML

搜索和索引脚本可写专用暂存表，必须带任务与分片 ID，使用唯一键幂等。书架、阅读进度和书签必须走 API，防止覆盖用户新数据。

## 迁移

1. 将现有书源 Runtime 搜索封装为脚本包。
2. 已完成单节点旁路验证并保持现有缓存表。
3. 已使用 Scheduler 原生执行目标完成多节点分片、原始结果暂存和部分成功聚合。
4. 已将书源发现迁为受限公网脚本和 Reader Service 版本化写入，健康检查迁为原生多节点分片任务，书源电子书导入迁为 Storage Gateway 原子发布任务；继续迁移元数据、目录构建和章节预缓存 Job。
5. 独立 schema 与 Reader Service MVP 已建立；完成对账后再由 Gateway 切换远程接口。

## 验收

- 取消搜索后不会继续产生新分片结果。
- 单个书源失败不导致所有有效结果丢失。
- 重复分片执行不会产生重复书籍记录。
