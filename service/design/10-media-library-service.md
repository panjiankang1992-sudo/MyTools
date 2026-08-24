# Media Library Service 详细设计

## 职责

负责媒体目录、标签关系、资源包导入、分析业务状态、播放进度、媒体查询和 App API。它消费资产事件并创建分析任务，不执行 FFmpeg 或模型推理。

## 数据模型

- `media_items`、`media_directories`、`media_directory_entry`、`media_tags`、`media_tag_relations`。目录条目独立于按内容去重的媒体项，允许同一资产同时出现在多个目录。
- `media_packages`、`media_analysis`、`media_artifacts`。
- `playback_progress`、`media_task_bindings`、`media_outbox`。
- `media_scan`、`media_scan_entry`：扫描 generation 及其冻结清单；条目只有在资产登记并回写媒体项后才进入 `IMPORTED`。
- `media_library_revision`：所有影响对账结果的领域写入共同推进的单调修订号。

## 任务类型

- `media_scan_directory`、`media_import_package`。
- `media_analyze_video`、`media_generate_tags`。
- `media_generate_thumbnail`、`media_generate_storyboard`。
- `media_generate_description`、`media_reconcile_assets`。

视频完整分析可作为父任务创建多个子任务，并按必需/可选结果聚合。媒体查询只读取已完成或处理中状态，不等待脚本。

当前 V48 先以单任务多步骤实现同节点分析流水线：`begin_analysis` 在任何耗时步骤前建立版本和任务绑定；探测、缩略图、故事板和简介为必需步骤，标签为可忽略的增强步骤；缩略图和故事板必须先经 Storage Gateway 持久化并在 Asset Registry 建立派生关系。`commit_analysis` 只接收前序步骤中的领域 ID，不接收临时路径，并在一个事务中替换当前分析版本的标签和派生物。`ON_FAILURE`、`ON_TIMEOUT`、`ON_CANCEL` 分别终止分析状态，场景步骤使用任务级截止时间之外的独立窗口。

分析绑定同时校验 Media Library 中的 `assetId` 与任务参数 `assetRegistryId`，防止调用方把一个媒体项的结果写到另一资产。`analysisVersion + mediaItemId` 唯一，任务重试使用同一 `taskInstanceId` 幂等恢复；已经成功的相同结果可以重放，不同摘要则报告冲突。

## DML

目录扫描脚本不直接修改媒体项，只能通过内部 API 写入 `media_scan_entry` 暂存清单；媒体项合并由经过校验的资产事件完成。标签、分析状态和播放进度不允许被任意脚本直接更新，必须校验 asset ID、分析版本和任务绑定。

## 目录扫描发布协议

1. `media_scan_directory` 在执行节点上校验目录必须位于 `MEDIA_SCAN_ALLOWED_ROOTS`，按稳定相对路径排序，拒绝符号链接、空文件、扫描中变化的文件以及超过 1000 个媒体文件的批次。
2. 脚本计算每个文件的 SHA-256，向 Media Library 幂等创建 `STAGING` generation 并一次性暂存完整清单。
3. 父任务为每个条目创建 `media_ingest_scanned_file` 直接子任务。子任务携带父任务实际节点的 `executor.node` 亲和约束，在同一挂载节点依次执行 ffprobe、文件复验和 Storage Gateway 幂等发布、Asset Registry 登记及 Media Library 事件回写。发布步骤再次验证允许根、普通文件、稳定 inode/大小/修改时间和清单 SHA-256；领域事件不传输文件内容，只引用持久化资产身份。媒体节点至少配置两个并发槽，避免等待子任务的父任务占满唯一执行槽。
4. Media Library 只接受与暂存条目的 owner、目录、来源标识、大小、类型和摘要全部匹配的回写，并在同一事务中标记条目为 `IMPORTED`。
5. 父任务等待全部子任务成功后请求发布。服务仅在条目总数等于 `expected_count` 且全部为 `IMPORTED` 时原子完成 generation，并把上一 generation 中消失的目录关系标记为 `MISSING`；同一去重媒体仍存在于其他就绪目录时保持 `READY`。
6. 失败、超时或取消不会发布 generation；重试使用扫描幂等键和子任务幂等键续跑。已完成 generation 的相同清单可安全重放。
7. Media Library 提供 owner-bound 目录扫描操作 API，负责创建 Scheduler 任务、保存幂等绑定以及查询和取消；扫描脚本仍负责实际发现、子任务创建和 generation 发布。

V49 增加可选的扫描后分析衔接。`analyze` 缺省为 `false`；显式启用时，每个摄取子任务在资产和媒体项均登记成功后，通过任务 SDK 创建同节点 `media_analyze_video` 子任务。分析幂等键由 `mediaItemId + analysisVersion` 构成。扫描只把“分析任务已可靠创建”作为摄取完成条件，不同步等待模型结果；这样目录 generation 的一致性不依赖 GPU 或模型吞吐，分析失败也通过自己的终态和重试策略处理。

V85 要求新扫描资产先发布到受管存储，并把 Asset Registry Provider 固定为
`STORAGE_GATEWAY`。因此 V82 的分析输入物化步骤可以仅凭资产 UUID 读取原文件，不再依赖
扫描节点的 `media://legacy` 位置。迁移只创建内容寻址副本，不删除、重命名或覆盖旧媒体文件。

扫描没有默认 Cron，也没有接入旧 MyTools 路径，因此 V2 仅新增旁路能力。正式切换前需要以显式目录执行扫描、比较清单摘要和媒体数量，再按租户启用新查询。

## 对账协议

V3 的内部对账接口以媒体 UUID 为稳定游标，每页最多返回 200 个媒体聚合后的状态计数和页摘要。摘要覆盖媒体主身份、内容摘要、来源、分析版本及终态、标签关系、派生资产关系和目录条目关系；响应同时携带目录数、完成/暂存扫描数和 `libraryRevision`。同步接口保持有界，不在一个请求中扫描全库。

`media_reconcile_library` 任务顺序读取全部页面，首个页面固定 revision 和全局计数，后续任一页面不一致立即失败。任务默认要求系统静止：没有暂存扫描、分析中媒体或运行中分析。成功结果包含各类关系总数和由全局元数据、每页计数及页摘要组成的最终 SHA-256，可作为多次迁移、灰度前后和旧新系统抽样对账的机器证据；它只读数据，不修改运行开关。

目录扫描业务操作保存创建请求摘要，相同 owner 和幂等键的参数变化必须拒绝，避免同一操作身份指向不同根目录或分析策略。Gateway 不返回内部 Scheduler 任务标识。

## 迁移

1. 在现有 MyTools 内建立 Task Client，替换 `TaggingJob`、扫描和媒体分析 Job。
2. 保持现有表为权威，先只替换执行方式。
3. 已接入 Asset Registry ID 领域模型、幂等事件收件箱和目录扫描 generation；`media_migrate_legacy_items` 使用封存 `local_file` 快照和 Asset Registry 旧 ID 映射进行两遍式预检、幂等回填。内容去重时通过 `media_item_source`、`media_item_source_tag` 保留每条旧文件身份及其标签，生产副本执行仍待进行。
4. 已建立独立 `mytools_media` schema和服务进程 MVP，覆盖分析版本唯一性、标签/派生物合并和乐观锁播放进度；旧接口仍为权威。
5. 已完成视频分析结果业务聚合和失败终态回写。Media Library 现在可按显式 owner、媒体 UUID、分析版本和幂等键创建 `media_analyze_video` 任务，并用统一操作接口查询或取消；任务参数不再接受物理路径，输入由 V82 物化步骤根据 Asset Registry 身份解析。
6. 旧 MyTools 已提供默认关闭的每日目录扫描旁路。它复用 `file.scan.path` 和资源盘可用性保护，通过 Media Library 创建持久化扫描操作；旧扫描任务及旧库保持不变，按日期和路径摘要幂等。
6. Gateway 改为远程调用。

当前已完成第 6 步的首批入口：Media Library 提供按 owner 和 UUID 游标的有界分页查询，Gateway 代理媒体列表、单项详情、播放进度写入、目录扫描与分析任务创建。耗时操作仍由 Scheduler 和 Executor 执行，客户端只获得可查询、可取消的操作标识。新入口由单独总开关控制，旧 MyTools 路径继续保留，数据迁移只追加到 `mytools_media`，不删除旧媒体记录。

历史媒体回填不重新连接旧 MyTools schema。第一阶段先用只读适配器在同一快照中封存 `local_file` 和 `file_tag`，并完成 Asset Registry 映射；第二阶段任务完整预检所有图片、视频和音频映射，随后用 `legacy-media:{identitySha256}` 事件在一个事务中建立 Media Library 项及 `LEGACY_MIGRATION` 标签。任务中断可重跑，标签变化会触发幂等冲突而不是静默覆盖，非媒体资产明确计入跳过数量，旧路径只用于推导显示名且不会进入目标事件。缩略图、截图和简介允许通过新分析任务重新生成。

旧 MyTools 的缩略图成功事件可在 `MEDIA_PROCESSING_SIDECAR_ENABLED=true` 时旁路创建完整 `media_analyze_video`。发布器先按 `local_file` 旧 ID 向 Media Library 解析真实媒体与 Asset Registry UUID，并核对内容摘要；映射缺失或摘要变化时不创建任务。任务通过 `MEDIA_PROCESSING_EXECUTOR_NODE` 固定到能够读取旧路径的 Executor 节点。默认开关关闭，旧缩略图仍为权威结果，旁路失败不影响旧事务。

## 验收

- 查询接口不因分析任务缓慢而阻塞。
- 同一资产同一分析版本只有一个活跃任务。
- DownloadBot 与人工扫描结果正确合并。
