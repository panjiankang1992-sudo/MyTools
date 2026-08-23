# Storage Gateway Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

本地文件与 rclone 等存储后端的安全操作。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/09-storage-gateway-service.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
