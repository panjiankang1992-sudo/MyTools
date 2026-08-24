# MyTools 旧数据处置矩阵

## 目标

旧库在迁移验收前保持只读可用，并保留一份完整、可读取的备份。在线新服务只导入不可再生的业务数据；缓存、索引、执行痕迹和分析产物不复制到新 schema，需要时由任务重新生成。这样保证原始数据不丢失，同时避免为历史派生数据建设额外服务。

## 处置矩阵

| 旧表 | 数据性质 | 目标服务/处置 | 验收方式 |
| --- | --- | --- | --- |
| `t_user`、`t_role`、`t_user_role` | 账号与权限，不可再生 | Identity Service | 用户、角色、关系迁移对账 |
| `t_token` | 旧会话，可重新登录 | 仅完整备份，不导入 | Identity Service 重新签发会话 |
| `sys_login_attempt` | 登录失败锁定状态，可重新生成 | 仅完整备份，不导入 | Identity Service 按新登录行为重新记录 |
| `t_email_verification_code` | 短期凭证 | 仅完整备份，不导入 | 过期后重新生成 |
| `webdav_account` | 外部存储配置，不可再生 | Storage Gateway | 账户迁移对账，密文原样迁移 |
| `drive_account` | 网盘配置，不可再生 | Drive Service | 账户数量与业务键对账 |
| `drive_item_index` | 远端索引，可再生 | 仅完整备份，不导入 | 创建网盘索引重建任务 |
| `local_directory`、`local_file` | 文件身份与位置 | Asset Registry、Media Library | 旧 ID 映射、来源数、摘要与标签关系对账 |
| `file_tag` | 可能包含人工标签 | Media Library | 按旧文件来源保留全部标签关系 |
| `media_package`、`media_package_asset` | 文件包清单，可由包目录恢复 | 仅完整备份，不导入 | 扫描 `metadata.json` 和资源文件重新登记 |
| `media_tag_artifact` | 模型分析审计，可再生 | 仅完整备份，不导入 | 按需创建媒体分析任务 |
| `ebook_metadata` | 文件解析索引，可再生 | Reader Service 重建 | 创建电子书书库重建任务 |
| `t_shelf_book`、`t_reading_progress`、`t_reader_marker` | 阅读业务状态，不可再生 | Reader Service | 数量、业务键和关系对账 |
| `t_synced_book_source` | 用户书源配置，不可再生 | Reader Service | 内容摘要与版本对账 |
| `t_book_source_search_cache` | 外部搜索缓存 | 仅完整备份，不导入 | 查询时重新创建搜索任务 |
| `t_app_market`、`t_app_version`、`t_app_file` | 应用目录与文件关系，不可再生 | App Catalog Service | 应用、版本、文件数量与摘要对账 |
| `t_feedback` | 用户反馈，不可再生 | Messaging Service | 旧 ID、数量和内容摘要对账 |
| `t_dsh_session_binding` | 外部会话绑定，不可再生 | DSH Connector Service | 旧 ID、会话业务键与摘要对账 |
| `refresh_log`、`t_api_log` | 历史操作与接口日志 | 仅完整备份，不提供在线迁移 | 需要审计时从备份恢复到临时库 |
| `file_maintenance_log` | 文件移动恢复线索 | 完整备份；有效文件位置由 Asset Registry 保存 | 备份行数校验，抽样验证原路径与目标路径 |

`t_error_code` 若生产库实际存在，属于静态配置，随完整备份保留，运行时由代码中的统一错误码重新生成。生产库中出现本矩阵之外的表时必须先分类，不允许直接忽略。

## 最小数据保全门禁

1. 停止旧库写入后执行全库备份，不按表筛选。
2. 记录备份文件 SHA-256 和每张表的 `COUNT(*)`；清单中的 `inventoryComplete` 设为 `true`。
3. 将备份加载到临时数据库，至少完成建表、逐表读取和行数核对后，才可将 `readVerified` 设为 `true`。
4. 使用 `python3 service/scripts/legacy_data_retention_gate.py manifest.json` 校验清单和备份文件。
   门禁会流式读取 `backupFile`、核对实际 SHA-256，并拒绝不存在、非普通文件或符号链接的
   备份；相对路径以清单所在目录为基准。任何未知表先补入本矩阵，再把
   `unclassifiedTables` 清空。
   若矩阵中的已知表在生产 Schema 中实际不存在，必须在 `absentTables` 中显式列出，不能
   伪造零行计数；门禁会拒绝未知、重复以及同时出现在 `tables` 中的缺失表。
5. 所有不可再生数据的领域迁移门禁通过后，旧库才能退出在线使用；备份不随旧服务删除。

清单示例：

```json
{
  "backupFile": "/backup/mytools-20260824.sql.gz",
  "sha256": "64-character-lowercase-sha256",
  "readVerified": true,
  "inventoryComplete": true,
  "unclassifiedTables": [],
  "absentTables": ["t_feedback"],
  "tables": {
    "local_file": 120
  }
}
```

示例只展示一个表；实际清单必须包含门禁脚本列出的全部已知表，空表也记录为零。
