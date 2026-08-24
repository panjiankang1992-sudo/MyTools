# Media Library Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

媒体目录、标签关系、播放与分析业务状态。

## 当前阶段

已建立独立 `mytools_media` schema 和服务 MVP，覆盖资产事件收件箱、媒体身份、目录、版本化分析、标签、派生资产、任务绑定、播放进度及 Outbox。V2 增加目录扫描 generation、完整清单暂存、逐资产导入确认和原子发布；旧 MyTools 媒体表和 App API 仍为权威路径。详细设计见 [对应设计文档](../design/10-media-library-service.md)。

同步查询新增 `GET /internal/v1/media/items?ownerId=&afterId=&limit=` 和已有单项、播放进度接口，供 Gateway 从认证主体注入 owner 后调用。Gateway 对外提供媒体分页、详情和进度写入，默认由 `GATEWAY_MEDIA_ROUTE_ENABLED=false` 关闭。目录扫描、标签、缩略图、截图和简介等耗时能力仍只通过任务执行，不进入同步查询路径。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。

资产事件按 `eventId + payloadSha256` 幂等；同一所有者和资产只生成一个媒体项。同一媒体和分析版本只能绑定一个任务。播放进度使用 `expectedRevision` 乐观锁，避免多设备静默覆盖。

Scheduler V28 在 `media_probe` 的 Asset Registry 登记之后追加 `media_register_item` 步骤。该步骤不传输宿主机路径，失败策略为 `IGNORE`，因此 Media Library 不可用不会改变现有媒体处理结果。

Scheduler V47 新增 `media_scan_directory` 父任务和 `media_ingest_scanned_file` 子任务。父任务只允许扫描 `MEDIA_SCAN_ALLOWED_ROOTS` JSON 数组内的目录，不跟随文件符号链接，单批最多创建 1000 个直接子任务。子任务自动继承父任务实际执行节点的 `executor.node` 亲和值，避免把宿主机路径分发到未挂载该目录的节点。每个子任务依次执行 ffprobe、Asset Registry 登记和 Media Library 回写，三步全部成功后父任务才发布 generation。发布事务会把本目录未出现在新 generation 的旧目录关系标记为 `MISSING`；只有资产不再属于任何就绪目录时才把媒体项标记为 `MISSING`。不完整、被取消或失败的批次保持 `STAGING`，不会影响上一批权威结果。媒体执行节点至少需要两个并发槽，保证等待子任务的父任务不会占满唯一执行槽。

当前未把该任务接入 MyTools 的旧扫描入口，也没有默认 Cron；只有显式创建任务且执行节点配置非空 `MEDIA_SCAN_ALLOWED_ROOTS` 后才会读取目录。

Scheduler V48 将 `media_analyze_video` 升级为版本化业务闭环。任务首先用 `mediaItemId + assetRegistryId + analysisVersion + taskInstanceId` 建立唯一绑定，随后执行探测、缩略图、故事板、可选标签和简介生成。缩略图及故事板先发布到 Storage Gateway，再登记为 Asset Registry 派生资产；最终步骤把标签、简介和派生资产 ID 在一个 Media Library 事务中提交。任何必需步骤失败、超时或取消时，场景步骤分别写入 `FAILED`、`TIMED_OUT` 或 `CANCELLED`，不会留下永久 `RUNNING` 分析。

`POST /internal/v1/media/operations/directory-scans` 现可直接创建 `media_scan_directory` 任务，并通过操作接口查询或取消。创建请求只接受目录信息和是否继续分析，owner 由 Gateway 注入；物理路径最终仍由 Executor 的 `MEDIA_SCAN_ALLOWED_ROOTS` 校验。`media_operation` 只保存幂等绑定和任务状态，不复制扫描清单。

`media_analyze_video` 没有默认定时触发，也没有替换旧 MyTools 分析入口。调用方必须使用 Media Library 返回的真实 `mediaItemId` 和 `assetRegistryId` 显式创建任务；相同媒体和分析版本不能绑定不同任务。

Scheduler V49 在扫描摄取任务末尾增加 `media_submit_analysis`。扫描参数 `analyze` 默认 `false`；显式设为 `true` 时，脚本使用刚完成的 `register_asset` 和 `register_media_item` 输出创建 `media_analyze_video` 子任务，并继承同一个 `executor.node` 约束。扫描 generation 只等待摄取及分析任务的可靠创建，不等待模型分析完成，因此大目录发布不会被模型吞吐阻塞；分析进度和终态继续由 Scheduler 与 Media Library 独立查询。

V3 增加 `media_library_revision` 单调修订号和 `GET /internal/v1/media/reconciliation` 有界分页接口。媒体、扫描、目录关系、分析、标签、派生物或播放进度发生领域写入都会推进 revision。Scheduler V50 的 `media_reconcile_library` 聚合媒体状态、分析终态、标签、派生资产和目录关系数量及确定性摘要；分页期间 revision 或全局扫描计数变化会使任务失败。`requireQuiescent` 默认为 `true`，存在 `STAGING` 扫描、`ANALYZING` 媒体或 `RUNNING` 分析时不生成可用于切流的成功报告。

V5 为新建目录扫描操作保存完整创建请求的 SHA-256。相同 owner 和幂等键只能重放相同扫描参数，根目录、目录标识或分析选项变化会被拒绝；升级前已有操作保持可查询，不要求重建或删除。

`media_migrate_legacy_items` 1.0.0 从已封存的 `local_file` 资产快照恢复图片、视频、音频及其 `file_tag` 关系。任务第一遍分页校验所有媒体记录均已有不可变 Asset Registry 映射，存在缺失时不会写 Media Library；正式执行第二遍使用稳定事件 ID，在一个目标事务中幂等导入媒体项和 `LEGACY_MIGRATION` 标签。任务不重新读取旧表，不把存储 URI 写入 Media Library，并输出来源数量、媒体数量、旧标签数量、非媒体跳过数量和确定性摘要。应先执行 dry-run，再执行相同 `sourceSnapshotId` 的正式任务，最后运行 `media_reconcile_library`。旧缩略图、截图和简介属于可再生产物，不迁移其路径，由新任务重新生成。
