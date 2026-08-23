# Drive Service

独立网盘领域服务，使用 `mytools_drive` schema。当前 MVP 提供内部账户登记、可恢复的分批索引写入和只读索引查询；旧 MyTools Drive/rclone 接口仍为权威路径，尚未启用流量切换。

接口：

- `POST /internal/v1/drive/accounts`：按外部账户标识幂等登记账户，只保存 Secret 引用。
- `POST /internal/v1/drive/accounts/{id}/index-batches`：按运行标识和批次游标幂等写入索引。
- `GET /internal/v1/drive/accounts/{id}/items?ownerId=&parentPath=`：按所有者隔离查询索引。

批次完成时才把旧 generation 中未出现的项目标记删除，因此失败重试不会提前破坏当前索引。

## 技术栈

Java 21 / Spring Boot

## 服务职责

网盘账户、索引、文件操作和访问票据。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。详细设计见 [对应设计文档](../design/12-drive-service.md)。

## 实施要求

- 首先实现稳定契约和最小健康检查。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
