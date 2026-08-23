# MsgService Adapter Service 详细设计

## 定位与边界

该服务是旧 MsgService 历史数据与新 Messaging Service 之间的临时防腐层。它只保存脱敏、标准化的不可变迁移快照并提供稳定分页导出，不负责实时收发、消息规则或新服务数据写入。

适配器独立部署、使用独立 `mytools_msgservice_adapter` schema 和账号。它不复用旧 MsgService 或新 Messaging schema；旧服务继续权威运行，迁移失败不得改变旧消息状态。

## 双重安全门禁

- `MSGSERVICE_ADAPTER_IMPORT_ENABLED=false`：默认拒绝快照装载。
- `MSGSERVICE_ADAPTER_EXPORT_ENABLED=false`：默认拒绝迁移读取。
- 两类接口共用仅限迁移网络面的内部令牌；生产部署可进一步拆分实例和网络策略。
- 接口仅接受固定白名单字段，不接受凭据、邮件原始认证头、Cookie 或任意扩展字段。

## 数据和接口

`legacy_inbound_snapshot` 使用自增序号提供稳定分页，使用 `source_system + legacy_message_id` 唯一键保证旧身份不重复，并保存规范载荷 SHA-256。已写快照不可覆盖；相同身份的载荷变化作为冲突拒绝。

- `POST /internal/v1/migration/inbound-messages/snapshots`：有界装载脱敏快照。
- `GET /internal/v1/migration/inbound-messages`：按不透明游标导出最多 200 条标准消息。
- 导出响应与 `message_migrate_history` 1.0.0 的旧端适配器契约一致。

## 迁移流程

1. 根据旧 MsgService 真实 schema 编写一次性只读映射器，不改旧表。
2. 在隔离环境开启 import，分批装载快照并核对接受、跳过、冲突数量。
3. 关闭 import，使迁移快照冻结。
4. 先开启 export 并执行 `message_migrate_history` dry-run，保存数量和摘要报告。
5. 正式导入 Messaging，重复执行验证全部转为 skipped，并对账总量和冲突清单。
6. 关闭 export 并保留只读快照至观察期结束；无法可靠映射的可再生数据登记后重建。

## 验收

- 默认配置下装载与导出均不可用。
- 重复快照不增加记录，身份冲突不覆盖原记录。
- 分页无重复、无遗漏且单页不超过 200 条。
- 导出内容不包含任何渠道凭据或未知字段。
- 历史导入不生成 Messaging 实时自动化事件。
