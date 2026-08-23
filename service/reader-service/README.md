# Reader Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

书架、阅读进度、书源、章节和电子书业务。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/13-reader-service.md)。

已建立独立 `mytools_reader` schema、可独立构建的 Spring Boot 服务，以及 `reader_source_search` 1.1.0 分片脚本。Scheduler 将一个任务实例展开为稳定的执行目标，脚本依据目标序号确定性分配书源，并使用执行实例隔离的 Reader Runtime 命名空间，避免并发分片互相覆盖书源快照。Reader Service 持久化请求和参数快照，幂等创建任务，保存每个执行目标的原始结果，并按规范化书名合并部分成功结果；现有 MyTools 搜索仍为线上权威实现。

MyTools 通过默认关闭的 `READER_SEARCH_SIDECAR_ENABLED` 开关提交同一书源快照。Reader Runtime 密钥仅由 Executor 的 `script-environments.reader_source_search` 注入，不进入 Scheduler 参数或数据库。

服务默认监听 `127.0.0.1:23230`，使用 `READER_DB_*` 连接独立 `mytools_reader` schema，并通过 `TASK_SCHEDULER_URL` 调用 Scheduler。`POST /api/v1/book-searches` 创建搜索，`GET /api/v1/book-searches/{id}` 查询并聚合分片结果，`POST /api/v1/book-searches/{id}/cancel` 取消执行。所有接口仍处于旁路阶段。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
