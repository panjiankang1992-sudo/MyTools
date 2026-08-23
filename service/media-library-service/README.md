# Media Library Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

媒体目录、标签关系、播放与分析业务状态。

## 当前阶段

已建立独立 `mytools_media` schema 和服务 MVP，覆盖资产事件收件箱、媒体身份、目录、版本化分析、标签、派生资产、任务绑定、播放进度及 Outbox。V2 增加目录扫描 generation、完整清单暂存、逐资产导入确认和原子发布；旧 MyTools 媒体表和 App API 仍为权威路径。详细设计见 [对应设计文档](../design/10-media-library-service.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。

资产事件按 `eventId + payloadSha256` 幂等；同一所有者和资产只生成一个媒体项。同一媒体和分析版本只能绑定一个任务。播放进度使用 `expectedRevision` 乐观锁，避免多设备静默覆盖。

Scheduler V28 在 `media_probe` 的 Asset Registry 登记之后追加 `media_register_item` 步骤。该步骤不传输宿主机路径，失败策略为 `IGNORE`，因此 Media Library 不可用不会改变现有媒体处理结果。

Scheduler V47 新增 `media_scan_directory` 父任务和 `media_ingest_scanned_file` 子任务。父任务只允许扫描 `MEDIA_SCAN_ALLOWED_ROOTS` JSON 数组内的目录，不跟随文件符号链接，单批最多创建 1000 个直接子任务。子任务自动继承父任务实际执行节点的 `executor.node` 亲和值，避免把宿主机路径分发到未挂载该目录的节点。每个子任务依次执行 ffprobe、Asset Registry 登记和 Media Library 回写，三步全部成功后父任务才发布 generation。发布事务会把本目录未出现在新 generation 的旧目录关系标记为 `MISSING`；只有资产不再属于任何就绪目录时才把媒体项标记为 `MISSING`。不完整、被取消或失败的批次保持 `STAGING`，不会影响上一批权威结果。媒体执行节点至少需要两个并发槽，保证等待子任务的父任务不会占满唯一执行槽。

当前未把该任务接入 MyTools 的旧扫描入口，也没有默认 Cron；只有显式创建任务且执行节点配置非空 `MEDIA_SCAN_ALLOWED_ROOTS` 后才会读取目录。

Scheduler V48 将 `media_analyze_video` 升级为版本化业务闭环。任务首先用 `mediaItemId + assetRegistryId + analysisVersion + taskInstanceId` 建立唯一绑定，随后执行探测、缩略图、故事板、可选标签和简介生成。缩略图及故事板先发布到 Storage Gateway，再登记为 Asset Registry 派生资产；最终步骤把标签、简介和派生资产 ID 在一个 Media Library 事务中提交。任何必需步骤失败、超时或取消时，场景步骤分别写入 `FAILED`、`TIMED_OUT` 或 `CANCELLED`，不会留下永久 `RUNNING` 分析。

`media_analyze_video` 没有默认定时触发，也没有替换旧 MyTools 分析入口。调用方必须使用 Media Library 返回的真实 `mediaItemId` 和 `assetRegistryId` 显式创建任务；相同媒体和分析版本不能绑定不同任务。
