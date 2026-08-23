# Media Intelligence

## 技术栈

Python 3.12 / Shell

## 服务职责

媒体探测、标签、缩略图、截图和简介脚本包。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/11-media-intelligence-service.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。

## 已落地脚本包

- `packages/media_generate_tags/1.0.0`：生成版本化标签结果，只写任务结果文件，不修改 MyTools 或 DownloadBot 数据库。
- `packages/media_compare_tags/1.0.0`：读取前一步生成结果与旧链路标签快照，生成精确匹配和 Jaccard 相似度。
- 旁路输入使用内容 SHA-256 与策略版本形成幂等键；旧实现继续作为线上权威结果。
- 双跑期间由后续对账任务比较标签集合、模型、内容哈希和策略版本，不直接覆盖旧标签。

## MyTools 旁路开关

- `MEDIA_TAG_SIDECAR_ENABLED=false`：默认关闭，保持现有运行行为。
- `TASK_SCHEDULER_URL`：任务调度服务地址。
- `MEDIA_TAG_POLICY_VERSION`：参与任务幂等键的标签策略版本。
- 开启后只有旧标签事务提交成功才异步创建任务；调度器不可用只记录告警，不回滚旧标签。
