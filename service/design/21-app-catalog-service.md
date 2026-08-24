# App Catalog Service 详细设计

## 职责

App Catalog Service 是应用、版本和发布文件元数据的唯一所有者，解决旧架构中 `t_app_market`、`t_app_version`、`t_app_file` 没有新服务承接的问题。首阶段不增加审核、推荐、搜索引擎等能力，只保留目录查询和数据迁移。

## 数据模型

- `app_catalog_entry`：应用主体及发布人、当前版本、状态和说明。
- `app_catalog_version`：不可再生的历史版本说明和旧文件关联。
- `app_catalog_file`：文件名、类型、大小、旧路径、内容 SHA-256、稳定 Storage URI 及 Asset Registry ID。
- `app_catalog_migration_item`：按旧应用标识记录载荷摘要，保证重跑不覆盖变化数据。

旧 Snowflake ID 保存在 `legacy_id`，新领域 ID 使用 UUID。旧文件路径只用于迁移定位；内容进入 Asset Registry 前旧文件不得清理。

## 迁移与实现

Scheduler 的 `app_catalog_migrate_legacy` 任务在旧 `my_tools` schema 上开启只读一致性事务，冻结应用最大 ID，并按应用读取全部版本和文件。每个应用聚合通过内部 API 在一个新库事务中导入。dry-run 不写数据；正式任务可重跑，相同旧 ID 载荷变化会明确拒绝。

验收要求 dry-run、正式任务和正式重放的应用、版本、文件数量及集合摘要一致，拒绝数为零；重放必须全部命中已导入摘要，目标对账数量必须分别等于来源数量。

元数据导入完成后由独立 `app_catalog_migrate_files` 任务迁移文件内容。任务先从 App Catalog 有界分页读取全部未绑定文件，在执行任何写入前冻结集合并校验 `maximumFiles`、`maximumBytes`；Executor 只允许读取 `APP_CATALOG_LEGACY_ROOTS` 中配置的普通文件，不接受符号链接或越界路径。每个文件复核登记大小、流式计算 SHA-256，通过 Storage Gateway 幂等发布，再以 `APP_CATALOG_FILE + legacyId` 登记 Asset Registry，最后回写资产 ID、摘要和 `storage://` URI。任一步骤失败都不删除旧文件；已发布但尚未绑定的内容可凭稳定幂等键安全重试。

Gateway 只开放已发布应用的全局只读目录。`owner_id` 表示发布者，不用于按查看者过滤；草稿、下架、迁移和对账接口不暴露给客户端。该路由由独立开关控制且默认关闭。
