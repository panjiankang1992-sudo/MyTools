# 远程迁移与验收报告（2026-08-25）

## 1. 验收范围与环境

- 远程主机：`home-ubuntu`。
- 发布根目录：`/opt/yuyutian/mytools`。
- 当前发布：`remote_candidate_20260825_10`。
- 日志根目录：`/opt/yuyutian/logs/mytools`。
- 旧 MyTools、DownloadBot、MsgService 保持运行，旧数据库、SQLite 一致备份和附件归档均未删除。
- 新 Gateway 路由、实时 DownloadBot 旁路、IMAP/OneBot、PikPak Connector 和迁移适配器在验收结束后恢复为关闭状态。

迁移证据保存在远程 `/opt/yuyutian/mytools/migration`，权限仅允许部署账号读取。旧 MyTools 备份清单 SHA-256 为 `8ba98940e236246902a5f946ef5e90b4c16031e6e65ce1b1ba55e4324bbeb05f`；迁移计划在创建任务前均校验该清单。

## 2. 不可再生数据迁移结果

| 领域 | 源数据 | 结果 |
|---|---:|---|
| Identity | 4 | 正式导入 4，重放 skipped 4，目标摘要一致 |
| Drive account | 4 | 正式导入 4，重放 skipped 4，目标摘要一致 |
| Storage provider | 4 | 4 个 Provider 与 Drive 绑定完成；3 个旧 WebDAV Provider 保持禁用，PikPak Provider 保持启用 |
| Asset | 36,405 条非删除记录 | 36,404 条有效记录导入；1 条零字节旧记录明确拒绝并保留备份；重放 skipped 36,404 |
| Media | 35,690 条媒体来源 | 35,299 个去重媒体实体、35,690 条来源关系、134,369 条来源标签；714 条非媒体资产明确跳过 |
| Reader | 40 | 40 条阅读进度导入；书架与书签为空；重放 skipped 40 |
| MsgService outbound | 11 | 9 sent、2 failed 全部归档，不触发重发；重放 skipped 11 |
| MsgService reference | 12 | 7 个模板、5 个已知收件人导入并完成幂等重放 |
| MsgService inbound | 0 | 空集合高水位、摘要和目标对账通过 |
| App Catalog | 0 | 三张旧表均为空，空集合迁移和目标对账通过 |
| Feedback | 0 | 旧库明确不存在 `t_feedback`；以已知缺表空源执行并留证 |
| DSH | 8 | 正式导入 8，重放 skipped 8，目标摘要一致 |
| DownloadBot history | 38,864 | 一致快照零拒绝；全部导入不可变历史表，重放 skipped 38,864，不触发下载 |

Asset 的唯一拒绝项在旧库中大小为零，不能满足新 Asset Registry 的正数大小约束。该记录没有可迁移文件内容，旧数据库完整备份仍保留；迁移和 Media 门禁均通过显式预期拒绝数承认该差异，没有静默计入成功数量。

MsgService 的 6 个旧附件中，4 个已进入内容寻址归档并验证 SHA-256；2 个附件在旧主机上原本已缺失，迁移保留 `MISSING` 状态、旧引用和 SQLite 一致备份，不伪造文件内容或摘要。

## 3. 任务控制面与可再生数据

- Scheduler 和 Executor 远程验收再次通过成功、失败、超时、取消四种终态；失败、超时、取消均执行对应终端步骤。
- Reader 书库 generation `7c7e7c59-890d-4c7c-84c9-ec51444dd16f` 已通过任务 `65560f71-6ccf-4bf1-96dd-55c5c10464ed` 原子发布；当前无可索引电子书，因此 `indexedCount=0`。
- Media 迁移后对账处于静止状态：暂存扫描、分析中任务和运行中分析均为 0。
- Storage/Drive 的 PikPak 索引尚未成功。rclone RC 已认证并能识别 `pikpak` remote，但 PikPak 验证码初始化接口持续被对端重置连接，Storage 返回 `STORAGE_014`，Drive 索引任务保留 FAILED 证据。该问题不影响已迁移的 Provider、Drive 账户或其他领域数据，但在外部连接恢复并完成索引及摘要对账前，不能宣称 Storage/Drive 重建完成。

## 4. 部署与安全验收

- `verify_deployment.py --skip-default-disabled`：12 个核心服务健康，1 个 Executor 节点在线。
- Gateway 的 Catalog、Reader、Drive、Media、Messaging、DSH 路由均保持关闭并返回 `GATEWAY_002`。
- 迁移完成后 Legacy Asset、MsgService、DownloadBot 三个适配器和 DSH Connector 已停止；相关导出/导入开关恢复为 `false`。
- 新微服务连接池限制为最大 3、最小空闲 0，避免所有服务的默认池合计耗尽远程 MySQL 连接槽。
- 19 个微服务使用独立日志目录；日志按天轮转，单文件 10 MiB 提前轮转，保留 9 份历史且 `maxage 10`，满足单服务约 100 MiB、最近最多 10 天的上限。
- 下载、Storage、Media 和 Reader 的业务数据目录继续独立于 `/opt/yuyutian/mytools` 发布根目录。

## 5. Gateway 启用条件

Identity、Asset、Media、Reader、Messaging、App Catalog、Feedback、DSH 和 Download 历史的数据条件已经满足。当前仍不启用任何新 Gateway 路由，原因是完整系统验收还缺少：

1. PikPak 外部连接恢复后的 Storage 扫描、Drive 索引和双端摘要闭合。
2. 使用隔离测试账号完成 IMAP、OneBot 和外部书源的真实连接测试；这些能力目前按设计关闭，未使用生产凭据进行破坏性试投递。
3. 在上述两项通过后，重新运行全服务健康、任务控制面和相应业务路由烟雾测试。

由于当前没有活跃用户，完成剩余外部验证后可直接启用所需 Gateway 路由，不实施灰度、双写或复杂切流。旧服务与备份在单独确认保留期结束前不得删除。
