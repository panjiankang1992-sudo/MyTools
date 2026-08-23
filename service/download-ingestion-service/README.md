# Download Ingestion Service

## 技术栈

Python 3.12

## 服务职责

下载请求解析、下载计划与下载业务生命周期。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/07-download-ingestion-service.md)。

已建立独立 `mytools_download` schema 的首版迁移、下载请求聚合、任务类型映射和幂等父任务编排。现阶段仍由 DownloadBot 旧 worker 执行实际下载。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
