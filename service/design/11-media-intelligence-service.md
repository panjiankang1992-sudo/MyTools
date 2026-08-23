# Media Intelligence 详细设计

## 定位

Media Intelligence 主要是一组版本化脚本包和模型配置，而不是持有业务数据库的在线服务。它由媒体 Executor 执行，为 Media Library 和 Asset Registry 产生结构化派生物。

## 脚本包

- `media_probe`：ffprobe 元数据。
- `media_generate_thumbnail`：缩略图。
- `media_generate_storyboard`：约 12 张截图。
- `media_generate_tags`：视觉标签。
- `media_describe_video`：模型或确定性降级的短摘要与长简介。
- `asset_register_media_thumbnail`、`asset_register_media_storyboard`：将临时产物发布并登记为版本化派生资产。
- `media_begin_analysis`、`media_commit_analysis`、`media_fail_analysis`：建立业务绑定、提交聚合结果和回写异常终态。

原子生成结果至少携带资产逻辑身份和内容摘要；涉及模型的结果额外携带模型或策略版本。业务聚合只引用已经持久化的 Asset Registry ID，不把执行器临时路径写入 Media Library。

## 执行流程

目标态脚本从 Storage Gateway 获取只读输入，在工作目录生成产物，校验后上传，再调用 Asset Registry 登记 artifact，最后调用 Media Library 更新分析结果。当前过渡实现仍从受控媒体节点的旧路径读取输入。V48 在耗时步骤前建立 `mediaItemId + assetRegistryId + analysisVersion + taskInstanceId` 绑定，缩略图和每一帧故事板均先持久化并登记派生关系，最后一次性提交标签、简介和领域资产 ID。标签模型失败可忽略，必需步骤失败、超时和取消会执行独立场景步骤关闭分析。模型任务创建时应匹配 GPU/模型能力节点。

## DML

默认不直接写业务表，只调用 Asset Registry 和 Media Library API。离线批量回填可以写专用暂存表，但最终合并仍由领域服务完成。

## 迁移

1. 封装 MyTools 当前 FFmpeg、标签、简介实现为脚本包。
2. 对相同样本并行运行旧实现和新脚本，比较产物。
3. DownloadBot 停止最终标签和分析，只创建任务。
4. MyTools 停止进程内分析 Job。
5. 统一模型、Prompt 和输入指纹版本。

## 验收

- 相同输入指纹和版本可复用已有产物。
- 失败不会覆盖上一个有效产物。
- 任务取消能停止 FFmpeg 和模型请求。
