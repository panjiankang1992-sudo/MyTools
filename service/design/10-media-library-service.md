# Media Library Service 详细设计

## 职责

负责媒体目录、标签关系、资源包导入、分析业务状态、播放进度、媒体查询和 App API。它消费资产事件并创建分析任务，不执行 FFmpeg 或模型推理。

## 数据模型

- `media_items`、`media_directories`、`media_tags`、`media_tag_relations`。
- `media_packages`、`media_analysis`、`media_artifacts`。
- `playback_progress`、`media_task_bindings`、`media_outbox`。

## 任务类型

- `media_scan_directory`、`media_import_package`。
- `media_analyze_video`、`media_generate_tags`。
- `media_generate_thumbnail`、`media_generate_storyboard`。
- `media_generate_description`、`media_reconcile_assets`。

视频完整分析可作为父任务创建多个子任务，并按必需/可选结果聚合。媒体查询只读取已完成或处理中状态，不等待脚本。

## DML

批量扫描脚本可写 `media_scan_staging`；合并过程调用 API或存储过程。标签、分析状态和播放进度不允许被任意脚本直接更新，必须校验 asset ID、分析版本和任务绑定。

## 迁移

1. 在现有 MyTools 内建立 Task Client，替换 `TaggingJob`、扫描和媒体分析 Job。
2. 保持现有表为权威，先只替换执行方式。
3. 已接入 Asset Registry ID 领域模型和幂等事件收件箱，下一步从现有媒体表回填映射。
4. 已建立独立 `mytools_media` schema和服务进程 MVP，覆盖分析版本唯一性、标签/派生物合并和乐观锁播放进度；旧接口仍为权威。
5. Gateway 改为远程调用。

## 验收

- 查询接口不因分析任务缓慢而阻塞。
- 同一资产同一分析版本只有一个活跃任务。
- DownloadBot 与人工扫描结果正确合并。
