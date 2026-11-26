# 10 月 20 日夜间下载失败分析

核查时间：2026-10-21 10:51 起（北京时间）。统计范围：2026-10-20 18:00 至 2026-10-21 08:00；数据库查询会话明确设为 +08:00。通过当前本机 SSH 配置验证连接，直接读取生产 Scheduler、Messaging、Automation 状态，未输出或保存密钥、令牌、环境文件内容、用户消息正文及原始下载链接。

## 本次结果

- 25 条 `download_x_post`：12 SUCCEEDED，13 TIMED_OUT，失败占 52%。任务集中提交于 10 月 21 日 02:22–02:38。
- 成功父任务派生的 23 个 `download_http_asset` 均 SUCCEEDED；下载、存储发布、资产登记、媒体登记及结果记录均成功。
- 13 个超时父任务的 task_execution 数均为 0，started_at 为空；dispatch_deadline_at 均精确等于 created_at 加 300 秒，updated_at 与截止回收时间一致。
- 25 条 `AutomationRunCompleted` 全部 published，dead=false；13 条失败消息的自动化结果为 FAILED / AUTOMATION_003，反映下游入口超时。
- 同期 3 次 X 认证健康任务成功。本次窗口未创建 Telegram 附件作业。
- 10:51 核验时 Scheduler、Executor、Messaging、Automation 均 active，最终查询下载相关 QUEUED/RUNNING 数为 0。

## 原因链

1. 生产 `download_x_post.max_concurrency=2`，每个已执行成功的父任务占用 95–297 秒。02:25 的实际在执行任务为两个 X 父任务与四个 HTTP 子任务。
2. X 父任务通过 `download_resolve_x_post/1.0.0` 创建 HTTP 子任务，并等待其完整处理结果后才退出。子任务内含 GPU 标签步骤；本批出现十次 GPU_BUSY 的失败尝试，所属子任务最终均成功，但等待与重试进一步占用父任务处理窗口。
3. 队列创建策略 `GpuTaskRepository.queueTimeoutSeconds` 仅给直接含 GPU 步骤的任务，或名称恰为 `message_download_attachment` 的附件入口，提供 7200 秒排队预算。
4. `download_x_post` 的直接脚本为 `download_resolve_x_post`，不直接声明 GPU 脚本，也不在附件入口特例内，因此仍落到默认 300 秒首次派发期限。
5. 两个父任务处理槽位被占用时，后续 X 请求等待超过五分钟，被 `TaskDeadlineService.expireUnclaimedTasks` 回收为 TIMED_OUT；这 13 条请求从未开始访问 X 或下载文件。

结论：当前队列期限策略漏掉了会等待带 GPU 子任务的 X 编排入口。此前 10 月 18 日修复覆盖了流式附件入口，本次相同类别的排队期限遗漏出现在 X 链接入口，不能将其描述为之前所有下载入口均已修复。

## 修复方向

- 为 X 帖子、X 用户与网页归档等等待下载子任务的入口建立显式、统一的有界排队策略；与其下游可能等待的工作负载预算匹配，避免仅按直接脚本是否使用 GPU 推断。
- 评估将完成判定中的 GPU 标签与文件下载成功解耦，减少父编排占槽时间。不可直接删除标签或把标签失败伪装为成功。
- 不以盲目提高 X 并发作为唯一修复，避免额外放大下游、GPU 与源站压力。
- 对已超时的 13 条原请求使用保留来源及幂等语义的受控重试，不直接改终态或数据库造成功。

本轮按用户“分析原因”请求进行只读生产诊断；没有修改服务配置、部署程序或重试历史消息。上述修复方向尚未实施，13 条超时请求仍未恢复。
