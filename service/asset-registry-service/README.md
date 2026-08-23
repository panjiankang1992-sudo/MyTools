# Asset Registry Service

## 技术栈

Java 21 / Spring Boot

## 服务职责

统一资产、来源、哈希、位置和资源包登记。

## 当前阶段

该目录属于旁路迁移工作区，不参与现有 MyTools 根工程构建和生产启动。已建立独立 `mytools_asset` schema、内容资产、业务来源、存储位置、派生关系和事务 Outbox。详细设计见 [对应设计文档](../design/08-asset-registry-service.md)。

资产身份由小写 SHA-256 与字节数确定；不同租户和系统通过来源关系引用同一内容资产，路径变化只增加或更新位置关系。来源事件、位置及派生关系均有独立幂等键；位置和派生写入还要求 `expectedAssetVersion`，防止并发任务覆盖新状态。存储 URI 必须包含 scheme 且禁止内嵌凭据。

内部接口：

- `POST /internal/v1/assets`：按内容和业务来源发现或创建资产，可原子登记首个位置。
- `GET /internal/v1/assets/{id}`：查询来源、位置和直接派生关系。
- `POST /internal/v1/assets/{id}/locations`：按乐观版本登记位置。
- `POST /internal/v1/assets/{id}/artifacts`：按生成器版本登记派生资产。

`asset_register_content` 1.0.0 脚本包可从明确的 `assetOutput`、前序 `import_ebook`、`download_asset` 或媒体 `probe` 步骤读取已经校验的 URI、摘要和大小，并通过共享 Executor SDK 登记资产。`asset_register_media_thumbnail` 会先通过 Storage Gateway 持久化缩略图，再把它作为独立资产登记并建立 `THUMBNAIL` 派生关系。Reader 电子书导入、HTTP 下载、媒体探测及缩略图任务均已追加旁路登记步骤；迁移期使用 `IGNORE` 失败策略，因此 Registry 或 Storage Gateway 故障不会改变已经成功的领域任务状态，独立任务可用于补偿重放。

## 实施要求

- 继续实现位置失效、资源包发布、迁移映射和批量对账。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
