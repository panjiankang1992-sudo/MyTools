# Media Library Service 详细设计

## 职责

负责媒体目录、标签关系、资源包导入、分析业务状态、播放进度、媒体查询和 App API。它消费资产事件并创建分析任务，不执行 FFmpeg 或模型推理。

## 数据模型

- `media_items`、`media_directories`、`media_directory_entry`、`media_tags`、`media_tag_relations`。目录条目独立于按内容去重的媒体项，允许同一资产同时出现在多个目录。
- `media_packages`、`media_analysis`、`media_artifacts`。
- `playback_progress`、`media_task_bindings`、`media_outbox`。
- `media_scan`、`media_scan_entry`：扫描 generation 及其冻结清单；条目只有在资产登记并回写媒体项后才进入 `IMPORTED`。

## 任务类型

- `media_scan_directory`、`media_import_package`。
- `media_analyze_video`、`media_generate_tags`。
- `media_generate_thumbnail`、`media_generate_storyboard`。
- `media_generate_description`、`media_reconcile_assets`。

视频完整分析可作为父任务创建多个子任务，并按必需/可选结果聚合。媒体查询只读取已完成或处理中状态，不等待脚本。

## DML

目录扫描脚本不直接修改媒体项，只能通过内部 API 写入 `media_scan_entry` 暂存清单；媒体项合并由经过校验的资产事件完成。标签、分析状态和播放进度不允许被任意脚本直接更新，必须校验 asset ID、分析版本和任务绑定。

## 目录扫描发布协议

1. `media_scan_directory` 在执行节点上校验目录必须位于 `MEDIA_SCAN_ALLOWED_ROOTS`，按稳定相对路径排序，拒绝符号链接、空文件、扫描中变化的文件以及超过 1000 个媒体文件的批次。
2. 脚本计算每个文件的 SHA-256，向 Media Library 幂等创建 `STAGING` generation 并一次性暂存完整清单。
3. 父任务为每个条目创建 `media_ingest_scanned_file` 直接子任务。子任务携带父任务实际节点的 `executor.node` 亲和约束，在同一挂载节点依次执行 ffprobe、Asset Registry 幂等登记和 Media Library 事件回写，不在任务参数或领域事件中传输文件内容。媒体节点至少配置两个并发槽，避免等待子任务的父任务占满唯一执行槽。
4. Media Library 只接受与暂存条目的 owner、目录、来源标识、大小、类型和摘要全部匹配的回写，并在同一事务中标记条目为 `IMPORTED`。
5. 父任务等待全部子任务成功后请求发布。服务仅在条目总数等于 `expected_count` 且全部为 `IMPORTED` 时原子完成 generation，并把上一 generation 中消失的目录关系标记为 `MISSING`；同一去重媒体仍存在于其他就绪目录时保持 `READY`。
6. 失败、超时或取消不会发布 generation；重试使用扫描幂等键和子任务幂等键续跑。已完成 generation 的相同清单可安全重放。

扫描没有默认 Cron，也没有接入旧 MyTools 路径，因此 V2 仅新增旁路能力。正式切换前需要以显式目录执行扫描、比较清单摘要和媒体数量，再按租户启用新查询。

## 迁移

1. 在现有 MyTools 内建立 Task Client，替换 `TaggingJob`、扫描和媒体分析 Job。
2. 保持现有表为权威，先只替换执行方式。
3. 已接入 Asset Registry ID 领域模型、幂等事件收件箱和目录扫描 generation；现有媒体表回填映射仍待执行。
4. 已建立独立 `mytools_media` schema和服务进程 MVP，覆盖分析版本唯一性、标签/派生物合并和乐观锁播放进度；旧接口仍为权威。
5. Gateway 改为远程调用。

## 验收

- 查询接口不因分析任务缓慢而阻塞。
- 同一资产同一分析版本只有一个活跃任务。
- DownloadBot 与人工扫描结果正确合并。
