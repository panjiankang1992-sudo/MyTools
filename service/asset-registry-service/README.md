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
- `POST /internal/v1/assets/{id}/locations/{locationId}/invalidate`：按乐观版本和幂等键显式失效位置。
- `POST /internal/v1/assets/bundles`：锁定资产版本并原子发布不可变资源包。
- `GET /internal/v1/assets/bundles/{bundleId}`：读取资源包固定清单。
- `GET /internal/v1/assets/reconciliation`：为异步任务返回最多 200 个资产的关系数量和确定性摘要。
- `POST /internal/v1/assets/migrations/legacy-mappings/batches`：预演或幂等导入旧资产身份及标准资产载荷。
- `POST /internal/v1/assets/migrations/legacy-mappings/resolve`：最多批量解析 200 个不可变旧身份到新资产 ID，并明确返回缺失集合。

`asset_register_content` 1.0.0 脚本包可从明确的 `assetOutput`、前序 `publish_asset`、`import_ebook`、`download_asset` 或媒体 `probe` 步骤读取已经校验的 URI、摘要和大小，并通过共享 Executor SDK 登记资产。新建 HTTP、消息附件和媒体目录扫描任务均先把原始文件发布到 Storage Gateway，再以 `STORAGE_GATEWAY` Provider 登记；旧任务实例仍兼容原步骤输出。`asset_register_media_thumbnail` 会先通过 Storage Gateway 持久化缩略图，再把它作为独立资产登记并建立 `THUMBNAIL` 派生关系。Reader 电子书导入、下载、媒体探测及缩略图任务均已追加旁路登记步骤；旧服务和原始文件保持不变，独立任务可用于补偿重放。

V2 新增位置失效审计和不可变资源包。资源包发布在事务内按资产 ID 顺序锁定全部引用资产并校验预期版本，每个成功的幂等键都写入独立绑定；规范清单摘要相同的请求只返回原资源包，后续资产增加位置或关系不会改变已发布清单。全库关系对账通过 `asset_reconcile_registry` 1.0.0 即时任务分页执行，同步 API 始终保持有界；所有关系写入推进单调修订号，扫描期间修订变化会使任务失败，避免输出混合时点报告。

V3 新增 `asset_legacy_mapping`。`legacy_asset_capture_snapshot` 1.0.0 通过旧 MyTools 数据库只读账号，在单个一致性事务中把 `local_file` 物化到独立适配器 schema；`asset_migrate_legacy_mappings` 1.0.0 必须显式指定已封存 `sourceSnapshotId`。dry-run 在真实目标事务中执行资产、来源、位置和 URI 校验后回滚，正式迁移才创建或复用内容资产并绑定旧 ID。相同旧身份和摘要重放为 skipped，载荷变化为 rejected；映射计数和摘要已纳入 Registry 对账报告。

旧身份批量解析接口只返回 `sourceSystem + legacyAssetId + assetId`，不返回旧路径、凭据或迁移载荷。Asset 映射任务只投影资产字段，不把快照中的媒体标签写入 Asset Registry；Media Library 等下游迁移任务必须先确认整批映射无缺失，再使用封存快照中的摘要、大小和领域元数据建立自身关系。

V48 的 `asset_register_media_thumbnail` 和 `asset_register_media_storyboard` 负责把分析临时产物发布到 Storage Gateway，并按分析版本登记不可变派生资产关系。故事板按帧序号使用不同 `artifactKind`，相同任务重试通过幂等键恢复，父资产版本在每个新关系写入后顺序推进。

## 实施要求

- 根据真实旧 schema 实现只读源适配器，并执行生产副本 dry-run、正式迁移和对账。
- 迁移已有能力时保留旧实现和功能开关。
- 在对账与回归通过前不得切换权威数据或生产流量。
