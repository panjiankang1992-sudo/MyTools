# App Catalog Service

应用目录服务使用 Java 21、Spring Boot 和独立 `mytools_app_catalog` schema，接管旧 MyTools 的 `t_app_market`、`t_app_version`、`t_app_file` 数据所有权。当前只实现迁移、只读目录和对账接口，不改变旧应用市场入口。

- `POST /internal/v1/catalog/migrations/legacy-apps`：按应用聚合进行 dry-run 或幂等导入。
- `GET /internal/v1/catalog/entries`：读取新目录。
- `GET /internal/v1/catalog/reconciliation`：返回应用、版本、文件及未绑定资产文件数量。

`app_catalog_migrate_legacy` 任务使用旧库只读账号和 Repeatable Read 一致性视图，逐个导入应用及其全部版本、文件。旧文件路径暂存为迁移定位信息，后续通过 Asset Registry 登记成功后再绑定 `asset_id`；在此之前不得删除旧文件。迁移先 dry-run，再正式执行，并使用相同来源摘要和对账数量验收。
