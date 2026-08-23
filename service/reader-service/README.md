# Reader Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

书架、阅读进度、书源、章节和电子书业务。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/13-reader-service.md)。

已建立独立 `mytools_reader` schema 首版迁移，以及 `reader_source_search` 1.0.0 单节点脚本。脚本保留多书源部分成功、并发上限、跨书源书名去重和结果数量边界；现有 MyTools 搜索仍为线上权威实现。

MyTools 通过默认关闭的 `READER_SEARCH_SIDECAR_ENABLED` 开关提交同一书源快照。Reader Runtime 密钥仅由 Executor 的 `script-environments.reader_source_search` 注入，不进入 Scheduler 参数或数据库。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
