# Media Intelligence 详细设计

## 定位

Media Intelligence 主要是一组版本化脚本包和模型配置，而不是持有业务数据库的在线服务。它由媒体 Executor 执行，为 Media Library 和 Asset Registry 产生结构化派生物。

## 脚本包

- `media_probe`：ffprobe 元数据。
- `media_generate_thumbnail`：缩略图。
- `media_generate_storyboard`：约 12 张截图。
- `media_generate_tags`：视觉标签。
- `media_generate_summary`：短摘要。
- `media_generate_description`：长简介。

每个结果包含 `assetId`、内容哈希、生产者、脚本版本、模型、Prompt 版本、输入指纹和生成时间。

## 执行流程

目标态脚本从 Storage Gateway 获取只读输入，在工作目录生成产物，校验后上传，再调用 Asset Registry 登记 artifact，最后调用 Media Library 更新分析结果。当前过渡实现仍从旧媒体路径读取输入：`media_probe` 后按内容摘要和实际大小登记原媒体的逻辑位置；`media_generate_thumbnail` 后将缩略图上传 Storage Gateway，登记为独立资产并建立 `THUMBNAIL` 派生关系。旁路登记失败不会改变原任务结果。模型任务创建时应匹配 GPU/模型能力节点。

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
