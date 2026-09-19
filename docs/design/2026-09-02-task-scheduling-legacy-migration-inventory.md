# 任务调度旧机制迁移清单

> 基线日期：2026-09-02  
> 依据：当前工作树 `@Scheduled`、`Sidecar`、`migration.tasks` 与领域服务 Scheduler 客户端代码扫描。  
> 用途：阶段 0 基线冻结及阶段 5 逐项灰度、对账、回滚和删除门禁。

基线可通过 `scripts/verify-task-scheduling-migration-inventory.sh` 重复校验；新增、删除或改名任何相关入口时，必须同步更新本清单与校验脚本。

## 1. 统计口径

- 根工程共有 13 个包含 `@Scheduled` 的类，其中 10 个属于旧业务任务，3 个属于连接、服务发现与注册邮件影子 Outbox 可靠投递基础设施。
- 独立服务另有 3 个 Outbox Relay/运行对账定时器，它们是可靠投递基础设施，不属于旧业务任务退出范围。
- 根工程当前有 8 组 Sidecar 配置，默认均关闭；其中 2 组已直接调用统一 `TaskSchedulerGateway`，其余调用领域服务 API。
- 设计稿中的“12 个旧 `@Scheduled` + 约 17 个 Sidecar 类”是早期估算；新增的注册邮件 Relay 属于可靠性基础设施，不扩大旧任务退出范围。本清单以当前代码角色分类，不再以类名数量作为删除完成标准。

## 2. 旧业务定时器

| 领域 | 旧入口 | 调度 | 当前开关 | 统一平台候选 | 退出状态 |
| --- | --- | --- | --- | --- | --- |
| 本地文件 | `TaggingJob` | 固定延迟 60 秒 | 无独立开关 | `media_generate_tags` | 待影子对账 |
| 本地文件 | `FileScanJob` | 每日 02:00 | 无独立开关 | `media_scan_directory` | 待影子对账 |
| 本地文件 | `FileMaintenanceJob` | 默认 10 分钟 | 无独立开关 | 文件校验/去重任务定义待确认 | 待定义契约 |
| 本地文件 | `ThumbnailGenerationJob` | 默认 5 秒 | 无独立开关 | 媒体处理任务定义待确认 | 待定义契约 |
| 媒体 | `ManualVideoPackageJob` | 默认 30 秒 | 无独立开关 | 媒体整理任务定义待确认 | 待定义契约 |
| 媒体 | `MediaPackageAnalysisJob` | 默认 15 秒 | 无独立开关 | `media_analyze_video` | 待影子对账 |
| 媒体 | `MediaDirectoryScanSidecarJob` | 默认每日 02:15 | `MEDIA_DIRECTORY_SCAN_SIDECAR_ENABLED` | Media Library 创建 `media_scan_directory` | 已有旁路，待灰度 |
| Reader | `EbookMetadataIndexJob` | 默认 30 秒 | `mytools.ebook.index-enabled` | Reader 索引任务定义待确认 | 待定义契约 |
| Reader | `BookSourceChapterCacheJob` | 默认 1 小时 | 无独立开关 | Reader 缓存维护任务 | 待影子对账 |
| Reader | `ReaderCacheMaintenanceSidecarJob` | 默认 1 小时 | `READER_CACHE_MAINTENANCE_SIDECAR_ENABLED` | Reader Service 缓存维护 API | 已有旁路，待灰度 |

### 2.1 明确排除的根工程基础设施定时器

| 类 | 职责 | 处置 |
| --- | --- | --- |
| `DshEventHub` | DSH 连接重连、交互过期清理与 SSE 心跳 | 保留，不迁移为业务任务 |
| `MdnsAdvertisementService` | 动态网卡 mDNS 广播对齐 | 保留，不迁移为业务任务 |
| `RegistrationMailSidecarPublisher` | 注册邮件影子 Outbox 可靠投递 | SHADOW 期间保留，切换完成后随迁移旁路退出 |

### 2.2 明确排除的独立服务可靠性定时器

| 服务 | 类 | 职责 | 处置 |
| --- | --- | --- | --- |
| Messaging | `AutomationOutboxRelay` | 入站消息 Outbox 可靠转发 | 保留，后续只统一指标与告警 |
| Message Automation | `CompletionOutboxRelay` | 完成事件可靠转发 | 保留，后续只统一指标与告警 |
| Message Automation | `AutomationRunReconciler` | 运行中任务状态对账 | 在 Scheduler Outbox 消费稳定后再评估降频，不直接删除 |

## 3. Sidecar 迁移拓扑

| Sidecar | 触发入口 | 当前目标 | 新平台关系 | 开关 | 下一门禁 |
| --- | --- | --- | --- | --- | --- |
| 注册邮件 | `RegistrationMailSidecarPublisher` / `RegistrationMailDeliveryPublisher` | Messaging `/internal/v1/delivery-shadows` / `/internal/v1/deliveries` | SHADOW 写脱敏 Outbox；CANARY/PRIMARY 按稳定邮箱键选择唯一真实路径并写 AES-GCM 加密 Outbox，Messaging 以稳定键创建 Scheduler 邮件任务 | `MESSAGING_REGISTRATION_MAIL_SIDECAR_ENABLED`、`MESSAGING_REGISTRATION_MAIL_MODE`、`MESSAGING_REGISTRATION_MAIL_CANARY_PERCENT` | 代码级单路由已具备且默认 LEGACY；待目标环境迁移、告警和回滚演练后小流量放量 |
| 媒体标签 | `MediaTagSidecarTaskPublisher` | `TaskSchedulerGateway` | 直接创建 `media_generate_tags` | `MEDIA_TAG_SIDECAR_ENABLED` | 对比标签集合，验证业务 fencing |
| 视频分析 | `MediaProcessingSidecarPublisher` | `TaskSchedulerGateway` | 直接创建 `media_analyze_video` | `MEDIA_PROCESSING_SIDECAR_ENABLED` | 对比目标集合、结果摘要与副作用 |
| 媒体目录扫描 | `MediaDirectoryScanSidecarJob` + `MediaDirectoryScanSidecarClient` | Media Library 目录扫描 API | Media Library 创建 Scheduler 任务 | `MEDIA_DIRECTORY_SCAN_SIDECAR_ENABLED` | 单根目录小流量切换与漏扫对账 |
| Reader 搜索 | `ReaderSearchSidecarPublisher` + `ReaderSearchSidecarClient` | Reader `/api/v1/book-searches` | Reader 创建分片搜索任务 | `READER_SEARCH_SIDECAR_ENABLED` | 对比源集合、命中数和耗时分布 |
| Reader 导入 | `ReaderImportSidecarPublisher` + `ReaderImportSidecarClient` | Reader 解析与导入 API | Reader 创建导入任务 | `READER_IMPORT_SIDECAR_ENABLED` | 对比书籍、章节及存储副作用 |
| Reader 发现 | `ReaderDiscoverySidecarPublisher` + `ReaderDiscoverySidecarClient` | Reader `/api/v1/source-discoveries` | Reader 创建发现任务 | `READER_DISCOVERY_SIDECAR_ENABLED` | 对比发现源集合与版本 |
| Reader 缓存维护 | `ReaderCacheMaintenanceSidecarJob` + `ReaderCacheMaintenanceSidecarClient` | Reader 缓存维护 API | Reader 创建维护任务 | `READER_CACHE_MAINTENANCE_SIDECAR_ENABLED` | 对比删除候选数与实际删除数 |

辅助类 `*SidecarProperties`、`*SidecarRequested` 与 `LegacyMediaAnalysisTargetClient` 随对应链路一起退出，不单独计为迁移项。

## 4. 灰度和回滚硬门禁

每个迁移项必须独立完成以下状态转换，禁止跨项批量删除：

1. `DEFINED`：任务名称、参数 Schema、结果 Schema、幂等键、业务 fencing 写点已确认。
2. `SHADOW`：新链路开启但禁止业务副作用，至少覆盖一个完整业务周期。
3. `CANARY`：按稳定业务键取模放量，旧入口对同一业务键停止执行，禁止双写。
4. `PRIMARY`：新平台成为权威入口，旧入口仅保留关闭状态的回退能力。
5. `REMOVED`：终态、结果、任务量和副作用对账通过，观察期结束后删除旧代码与配置。

任何阶段出现以下条件时立即回退到上一状态：

- Scheduler 任务创建或终态成功率低于该业务旧链路基线。
- 同一业务幂等键出现两个有效副作用。
- 终态对账、结果摘要或目标集合不一致。
- Outbox 死信、WAL 未确认报告或受阻队列持续超过告警窗口。

## 5. 配置与发布约束

- 所有现有 Sidecar 开关默认保持 `false`，生产放量必须显式配置。
- 新任务禁止新增根工程专用 Scheduler HTTP 客户端；根工程使用统一 Gateway，独立服务通过 `service/task-scheduler-client` 公共模块接入，领域代码只保留任务契约薄适配器。
- 业务服务访问 Scheduler 必须携带 `X-Task-Business-Token`；令牌来自 `TASK_BUSINESS_INTERNAL_TOKEN`，迁移期允许回退 `TASK_INTERNAL_TOKEN`。
- 删除旧入口前必须保留一个可独立部署的回退版本，并记录数据库/Outbox/WAL 对账查询。

## 6. 建议实施顺序

1. 注册邮件与 Messaging 投递：已有 fencing 条件写，先验证完整灰度闭环。
2. 媒体目录扫描：入口单一、每日批次清晰，适合验证任务量与漏扫对账。
3. Reader 搜索和缓存维护：先无副作用搜索，再删除型缓存维护。
4. 视频分析与媒体标签：完成资产、媒体领域 fencing 后放量。
5. 文件维护、缩略图、媒体整理和电子书索引：先补任务契约与独立开关，再进入影子阶段。

## 7. 注册邮件当前灰度边界

- 开启 `MESSAGING_REGISTRATION_MAIL_SIDECAR_ENABLED` 只记录无副作用影子，不会向 `delivery_request` 写入记录，也不会创建 Scheduler 任务。
- 影子请求不传输邮箱、验证码、主题或正文，只传输使用独立密钥计算的 HMAC-SHA-256 收件人摘要、载荷摘要和 `DELIVERED` 旧链路结果；开启时 `MESSAGING_REGISTRATION_MAIL_SHADOW_HASH_KEY` 必须至少 32 字节。
- 根工程在验证码数据库事务内写入 `t_registration_mail_shadow_outbox`，只保存 HMAC 摘要；Relay 使用条件领取、领取租约、成功 ACK、指数退避、最大尝试次数与 `DEAD` 状态，进程重启后可恢复未确认记录。
- `(owner_id, idempotency_key)` 唯一约束保证响应丢失后的幂等重放；同键不同摘要返回冲突，禁止静默覆盖。Outbox ACK/失败更新同时校验领取截止时间，旧 Worker 不能覆盖已重新领取的结果。
- 已按规范化邮箱和 HMAC 密钥实现旧 SMTP 与 Messaging 单路由；同一验证码命中 Messaging 后只写加密真实投递 Outbox，不调用旧 SMTP。生产仍保持 `LEGACY`，执行目标环境迁移、密钥托管、积压/死信告警和回滚演练后方可配置 CANARY；回滚前必须排空已提交 Outbox。
