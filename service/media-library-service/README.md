# Media Library Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

媒体目录、标签关系、播放与分析业务状态。

## 当前阶段

已建立独立 `mytools_media` schema 和服务 MVP，覆盖资产事件收件箱、媒体身份、目录、版本化分析、标签、派生资产、任务绑定、播放进度及 Outbox。旧 MyTools 媒体表和 App API 仍为权威路径。详细设计见 [对应设计文档](../design/10-media-library-service.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。

资产事件按 `eventId + payloadSha256` 幂等；同一所有者和资产只生成一个媒体项。同一媒体和分析版本只能绑定一个任务。播放进度使用 `expectedRevision` 乐观锁，避免多设备静默覆盖。

Scheduler V28 在 `media_probe` 的 Asset Registry 登记之后追加 `media_register_item` 步骤。该步骤不传输宿主机路径，失败策略为 `IGNORE`，因此 Media Library 不可用不会改变现有媒体处理结果。
