# App Catalog Service

应用目录服务使用 Java 21、Spring Boot 和独立 `mytools_app_catalog` schema，接管旧 MyTools 的 `t_app_market`、`t_app_version`、`t_app_file` 数据所有权。当前只实现迁移、只读目录和对账接口，不改变旧应用市场入口。

- `POST /internal/v1/catalog/migrations/legacy-apps`：按应用聚合进行 dry-run 或幂等导入。
- `GET /internal/v1/catalog/entries`：读取全部已发布应用，不返回草稿或已下架应用。
- `GET /internal/v1/catalog/reconciliation`：返回应用、版本、文件及未绑定资产文件数量。

`app_catalog_migrate_legacy` 任务使用旧库只读账号和 Repeatable Read 一致性视图，逐个导入应用及其全部版本、文件。迁移先 dry-run，再正式执行，并使用相同来源摘要和对账数量验收。

`app_catalog_migrate_files` 在元数据保全后迁移不可再生文件。执行节点必须配置只读的 `APP_CATALOG_LEGACY_ROOTS`；任务冻结未绑定文件并强制执行文件数和总字节上限，逐项复核大小、计算 SHA-256、发布到 Storage Gateway、登记 Asset Registry，最后幂等绑定 `asset_id`、摘要和稳定 URI。旧路径和旧文件始终保留，直到未解析文件数为零且完成目标对账。
