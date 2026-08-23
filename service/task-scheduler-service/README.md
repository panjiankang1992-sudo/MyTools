# Task Scheduler Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

任务定义、实例、步骤、父子任务、集群节点与调度控制面。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/05-task-scheduler-service.md)。

当前已经实现独立 `mytools_task` schema 的 Flyway 基础结构，以及任务定义、脚本步骤、任务实例、集群、节点和多对多节点分配接口。创建 schema：

任务执行内部协议同时支持按节点集群领取、租约续期、取消状态返回、步骤结果上报和执行完成。领取通过任务状态条件更新保证同一任务实例只产生一个有效领取者。

```bash
mysql -u root -p < deploy/create-schema.sql
```

运行服务前使用环境变量配置专用账号：

```text
TASK_DB_URL=jdbc:mysql://127.0.0.1:3306/mytools_task?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
TASK_DB_USERNAME=mytools_task
TASK_DB_PASSWORD=通过部署 Secret 注入
```

数据库账号的创建和授权由部署系统完成，不在仓库中保存生产用户名之外的凭据。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
