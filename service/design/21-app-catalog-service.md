# App Catalog Service 详细设计

## 职责

App Catalog Service 是应用、版本和发布文件元数据的唯一所有者，解决旧架构中 `t_app_market`、`t_app_version`、`t_app_file` 没有新服务承接的问题。首阶段不增加审核、推荐、搜索引擎等能力，只保留目录查询和数据迁移。

## 数据模型

- `app_catalog_entry`：应用主体及发布人、当前版本、状态和说明。
- `app_catalog_version`：不可再生的历史版本说明和旧文件关联。
- `app_catalog_file`：文件名、类型、大小、旧路径及后续 Asset Registry ID。
- `app_catalog_migration_item`：按旧应用标识记录载荷摘要，保证重跑不覆盖变化数据。

旧 Snowflake ID 保存在 `legacy_id`，新领域 ID 使用 UUID。旧文件路径只用于迁移定位；内容进入 Asset Registry 前旧文件不得清理。

## 迁移与实现

Scheduler 的 `app_catalog_migrate_legacy` 任务在旧 `my_tools` schema 上开启只读一致性事务，冻结应用最大 ID，并按应用读取全部版本和文件。每个应用聚合通过内部 API 在一个新库事务中导入。dry-run 不写数据；正式任务可重跑，相同旧 ID 载荷变化会明确拒绝。

验收要求 dry-run、正式任务和正式重放的应用、版本、文件数量及集合摘要一致，拒绝数为零；重放必须全部命中已导入摘要，目标对账数量必须分别等于来源数量。文件内容迁移是后续独立任务，不阻塞元数据保全。
