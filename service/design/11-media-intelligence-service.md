# Media Intelligence 详细设计

## 定位

Media Intelligence 主要是一组版本化脚本包和模型配置，而不是持有业务数据库的在线服务。它由媒体 Executor 执行，为 Media Library 和 Asset Registry 产生结构化派生物。

## 脚本包

- `media_materialize_input`：从 Asset Registry 解析可用的 Storage Gateway 位置，流式物化并复验大小和 SHA-256。
- `media_probe`：ffprobe 元数据。
- `media_generate_thumbnail`：缩略图。
- `media_generate_storyboard`：约 12 张截图。
- `media_generate_tags`：视觉标签。
- `media_describe_video`：模型或确定性降级的短摘要与长简介。
- `asset_register_media_thumbnail`、`asset_register_media_storyboard`：将临时产物发布并登记为版本化派生资产。
- `media_begin_analysis`、`media_commit_analysis`、`media_fail_analysis`：建立业务绑定、提交聚合结果和回写异常终态。

原子生成结果至少携带资产逻辑身份和内容摘要；涉及模型的结果额外携带模型或策略版本。业务聚合只引用已经持久化的 Asset Registry ID，不把执行器临时路径写入 Media Library。

## 执行流程

V82 已把分析输入改为从 Storage Gateway 获取：任务先依据 `assetRegistryId` 查询 Asset Registry，只接受状态为 `AVAILABLE`、类型为 `STORAGE_GATEWAY` 的 `storage://root/path`，随后流式写入步骤工作目录，并以资产登记的大小和 SHA-256 复验。只有物化成功才执行探测、缩略图、故事板、标签和简介步骤；这些步骤优先使用物化输出。旧 `sourcePath` 只作为历史任务实例的兼容回退，不再是新任务必填参数，也不会写入领域状态。

V48 在耗时步骤前建立 `mediaItemId + assetRegistryId + analysisVersion + taskInstanceId` 绑定，缩略图和每一帧故事板均先持久化并登记派生关系，最后一次性提交标签、简介和领域资产 ID。标签模型失败可忽略，物化或其他必需步骤失败、超时和取消会执行独立场景步骤关闭分析。模型任务创建时应匹配 GPU/模型能力节点。

过渡期 MyTools 不再为同一事件创建孤立的 `media_probe` 和 `media_generate_thumbnail` 任务，因为两者的临时产物无法单独形成领域闭环。旁路只创建完整 `media_analyze_video`，并要求旧 ID 已迁移、摘要一致且显式配置 `executor.node` 亲和约束。这样任务成功时结果一定进入 Asset Registry 和 Media Library，失败时也有明确分析终态。

## DML

默认不直接写业务表，只调用 Asset Registry 和 Media Library API。离线批量回填可以写专用暂存表，但最终合并仍由领域服务完成。

## 迁移

1. 已封装 MyTools 当前 FFmpeg、标签、简介实现为脚本包。
2. 已增加 Storage Gateway 输入物化与完整性复验，后续新任务不再依赖媒体节点物理路径。
3. 对相同样本并行运行旧实现和新脚本，比较产物。
4. DownloadBot 停止最终标签和分析，只创建任务。
5. MyTools 停止进程内分析 Job。
6. 统一模型、Prompt 和输入指纹版本。

## 验收

- 相同输入指纹和版本可复用已有产物。
- 失败不会覆盖上一个有效产物。
- 任务取消能停止 FFmpeg 和模型请求。
