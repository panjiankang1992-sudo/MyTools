# DownloadBot Adapter Service 详细设计

## 定位与边界

该服务是旧 DownloadBot 与新 Download Ingestion 之间的防腐层，不是新的下载实现。它只负责旧事件标准化、幂等收件箱、受控旁路转发和后续结果对账；下载计划和业务生命周期归 Download Ingestion，脚本执行归 Executor。

适配器独立部署、独立使用 `mytools_downloadbot_adapter` schema。实时旁路不引用
DownloadBot 数据表，也不共享数据库账号。旧 DownloadBot 在整个旁路阶段继续处理原请求并返回结果。

历史迁移例外使用独立的只读账号对旧 `downloadbot` schema 执行 `SELECT`。捕获任务在
一致性只读事务内固定表高水位，将标准化数据写入适配器独立 schema 并封存；后续导入
只消费封存快照，不允许直接从旧库写入新业务服务。

## 模式与安全门禁

| 模式 | 行为 | 权威性 |
| --- | --- | --- |
| `DISABLED` | 仅将显式投递的脱敏事件写入新收件箱 | 旧链路权威，且不产生新下载任务 |
| `SHADOW` | 写入收件箱并调用 Download Ingestion 创建旁路请求 | 旧链路权威，新结果只供对账 |

服务默认且缺省为 `DISABLED`。不提供自动升为 `SHADOW` 的逻辑；非法模式导致进程启动失败。接口和下游调用分别使用不同的内部令牌，事件不得包含旧数据库凭据、会话 Cookie 或物理根目录。

## 数据模型与 API

`adapter_event` 保存全局唯一 `event_id`、稳定来源身份、下载类型、脱敏参数、转发状态、新下载请求标识和稳定错误码。相同事件标识只有在业务内容完全一致时才允许重放。

- `POST /internal/v1/downloadbot/events`：接受旧请求事件。
- `GET /internal/v1/migration/downloadbot/snapshot-items`：分页读取已封存标准化条目；默认关闭。
- `GET /internal/v1/migration/downloadbot/pikpak-accounts`：分页读取脱敏旧账户配置；使用独立令牌且默认关闭。
- `GET /internal/v1/reconciliation/downloadbot/events/{eventId}`：组合旁路映射和旧内容证据；默认关闭。
- `GET /health`：进程存活检查，不代表 `SHADOW` 已启用。

转发幂等键固定为 `downloadbot:{eventId}`。下游暂时失败时事件标记为 `FAILED`；相同事件重放可继续转发，下游自身幂等契约防止生成第二个请求。

## 分阶段迁移

1. 当前阶段：只新增独立服务和 schema；不改 DownloadBot，实时旁路和快照导出均默认关闭。
2. 快照阶段：由 Scheduler 创建 `downloadbot_capture_snapshot`，Executor 使用旧库只读账号
   在同一一致性视图中捕获 `ingress_events`、`messages`、`assets`、`asset_sources`、
   `link_jobs` 和 `link_asset_sources`，无效记录进入拒绝审计。事件原始载荷和消息身份属于
   不可再生数据，完整封存在受限新 schema；物理路径、原始下载 URL 和凭据不迁移。
   Download Ingestion 使用各类型固定字段白名单写入不可变历史表，不创建活跃下载请求。
3. 导入阶段：同一封存快照先 dry-run，再正式导入；拒绝数必须为零，正式导入可幂等重放。
4. 验收阶段：核对源快照数量、集合摘要与目标历史表数量和摘要。临时文件及可再生摘要重新生成。

PikPak 配置迁移只导出可验证的路由元数据，并用集合摘要检测分页期间的配置变化。Provider UUID
和 Secret 引用必须在迁移任务参数中逐账户显式提供；适配器不读取或推断新服务的 Provider、Secret。

## 实现与验收

- 并发重复事件只产生一条收件箱记录和一个新下载请求。
- `DISABLED` 模式测试必须证明不会调用 Download Ingestion。
- 下游超时不会改变旧任务状态，可通过重放恢复。
- 参数冲突、未授权请求和超大请求体均被拒绝。
- 在结果对账、错误率、耗时和回退演练达标前，不允许把新结果作为用户可见权威结果。
