# 远程迁移与验收报告（2026-08-25）

## 1. 验收范围与环境

- 远程主机：`home-ubuntu`。
- 发布根目录：`/opt/yuyutian/mytools`。
- 当前发布：`remote_candidate_20260825_13`。
- 日志根目录：`/opt/yuyutian/logs/mytools`。
- 旧 MyTools、DownloadBot、MsgService 保持运行，旧数据库、SQLite 一致备份和附件归档均未删除。
- 新 Gateway 路由、实时 DownloadBot 旁路、IMAP/OneBot、PikPak Connector 和迁移适配器在验收结束后恢复为关闭状态。IMAP 凭据已配置，但入站轮询仍保持关闭。

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
- 外部配置补充后的复验 run key 为 `post-external-20260825-02`，四种终态分别为 `SUCCEEDED`、`FAILED`、`TIMED_OUT`、`CANCELLED`。
- Reader 书库 generation `7c7e7c59-890d-4c7c-84c9-ec51444dd16f` 已通过任务 `65560f71-6ccf-4bf1-96dd-55c5c10464ed` 原子发布；当前无可索引电子书，因此 `indexedCount=0`。
- 用户提供的书源 JSON 已验证为 703 条有效输入；由于目标站点持续重置 Ubuntu 的 TLS 连接，采用本机下载校验后经 Reader 受保护的批量接口离线导入。按 `bookSourceUrl` 去重后保存 702 条，数据全部保留，验收仅启用 1 条。健康检查任务 `786f088e-509f-4684-b474-54ca6e7a41ae` 经 Scheduler/Executor 成功完成；该书源规则探测结果为不健康，不影响任务链验收结论。
- 首次健康检查发现 Executor 环境缺少 `READER_RUNTIME_SECURE_KEY`。远程已从既有 Reader Runtime 的受限配置安全映射该键，Executor 重启后复验成功；部署环境模板同步补充该变量。
- Media 迁移后对账处于静止状态：暂存扫描、分析中任务和运行中分析均为 0。
- PikPak 出站改为通过本机 Mihomo SOCKS5 代理后，常驻 rclone RC 成功读取根目录，证明既有 Token 有效。Storage 扫描操作 `0c451661-3d0b-4c2b-81be-f0cf397a3680` 与 Drive 索引操作 `c2078e93-0df3-3d80-a0db-9be8b05f7ecb` 均经 Scheduler/Executor 成功完成，各得到 6,061 项。
- `drive_reconcile_index` 任务完成 Storage/Drive 双端对账：对象数均为 6,061，共享内容摘要均为 `eba153f7566fb618ccad5ffd1fafa6c5e7314ec278ec5dd07ecdb3be4634c609`，`matched=true`。Storage 切换门禁以 4 个已绑定 Provider 和 1 份启用 Provider 对账证据返回 `ready=true`。
- 实测暴露并修复两个索引契约问题：Provider 相对路径错误拒绝合法 ASCII 冒号，以及 Drive 根目录批次错误要求非空 `parentPath`。Storage/Drive Java 21 测试通过，修复已部署。

## 4. 外部连接实测

- SMTP：使用旧 MsgService 当前配置执行 Nodemailer `verify()` 成功；随后将同一主机上的配置安全映射到新 Messaging，启用 Spring Mail 健康检查后 `/actuator/health` 返回 `UP`。两次检查都只完成 TLS/认证握手，没有发送邮件。
- IMAP：Foxmail 隔离账户已写入远程 `0600` 环境文件；对 `imap.qq.com:993` 的只读登录及 `INBOX` 打开成功，确认 6 封现有邮件，未读取正文、未设置已读标志。Messaging 重启健康，`MESSAGING_EMAIL_INGRESS_ENABLED=false` 继续阻止实际轮询。
- OneBot：NapCat 恢复登录后，只读 `get_status` 返回 HTTP 200、`online=true`、`good=true`。
- PikPak：既有 Token 有效；此前连接重置由 `user.mypikpak.com` 直连 SNI 阻断造成。rclone RC 已配置 `socks5://127.0.0.1:7891` 及回环 `NO_PROXY`，常驻 RC 和完整递归扫描均验证成功。进程环境已迁到 `/opt/yuyutian/mytools/config/rclone-rc.env`，权限为 `0600`；remote 业务配置继续复用 DownloadBot 数据目录。
- 外部书源：输入 JSON 有效并已登记 702 个去重书源；Scheduler/Executor 健康检查任务成功完成，当前启用的测试书源探测结果为不健康。

## 5. 部署与安全验收

- `verify_deployment.py --skip-default-disabled`：12 个核心服务健康，1 个 Executor 节点在线。
- Gateway 的 Catalog、Reader、Drive、Media、Messaging、DSH 路由均保持关闭并返回 `GATEWAY_002`。
- 迁移完成后 Legacy Asset、MsgService、DownloadBot 三个适配器和 DSH Connector 已停止；相关导出/导入开关恢复为 `false`。
- 新微服务连接池限制为最大 3、最小空闲 0，避免所有服务的默认池合计耗尽远程 MySQL 连接槽。
- 19 个微服务使用独立日志目录；日志按天轮转，单文件 10 MiB 提前轮转，保留 9 份历史且 `maxage 10`，满足单服务约 100 MiB、最近最多 10 天的上限。
- 下载、Storage、Media 和 Reader 的业务数据目录继续独立于 `/opt/yuyutian/mytools` 发布根目录。

## 6. Gateway 启用条件

Identity、Storage/Drive、Asset、Media、Reader、Messaging、App Catalog、Feedback、DSH 和 Download 历史的数据、重建与外部连接条件均已满足。最终复验结果如下：

1. `verify_domain_rebuilds.py` 返回 `ready=true`，覆盖 Storage 6,061 项扫描、Drive 索引操作和 Reader 原子 generation。
2. `verify_deployment.py --skip-default-disabled` 确认 12 个核心服务健康、1 个 Executor 节点在线。
3. 任务控制面 run key `final-pikpak-20260825-01` 的成功、失败、超时、取消四种终态全部通过，三个异常终态均执行对应特殊步骤。
4. IMAP、SMTP、OneBot、PikPak 和外部书源均已完成隔离实测；Gateway 路由仍按设计保持关闭。

系统现已具备启用所需 Gateway 路由的条件。由于当前没有活跃用户，后续可按实际使用范围直接启用对应路由，不需要灰度、双写或复杂切流。

由于当前没有活跃用户，完成剩余外部验证后可直接启用所需 Gateway 路由，不实施灰度、双写或复杂切流。旧服务与备份在单独确认保留期结束前不得删除。
