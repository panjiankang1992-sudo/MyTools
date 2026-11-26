# 下载主流程与后台后处理拆分验收

日期：2026-11-04。目标：消除附件轮询和标签生成长期占用下载槽位的问题。此前分析见 `../2026-11-04-download-latency/analysis.md`。

## 已发布的行为

- HTTP、消息附件、本机存储、远端存储四类下载在文件保存、资产登记和结果回写后完成。缩略图、标签和媒体投影转入独立 `download_enrich_file` 任务；文本归档保持原流程。
- Download 请求和终态对账作业在同一事务写入；文件结果和后处理作业在同一事务写入。后台消费者使用短租约、领取令牌、确定性幂等键，重启或提交响应丢失不会丢任务或重复创建任务。
- 后处理通过 Asset Registry 和 Storage Gateway 从持久存储重新取文件，核验资产、大小及 SHA-256，不依赖已清理的下载工作目录。
- 后处理集群并发为 1、任务优先级为 10。附件提交脚本不再创建 `message_reconcile_attachment_download` 轮询子任务；终态对账在 Download 后台进行。
- 下载成功与后处理状态分开返回。APP 可显示“下载完成”及“标签生成中，文件已保存”。标签失败不把已保存文件改成下载失败。
- 标签模型必须确认实际 GPU 驻留；冷启动受限预加载，CPU 驻留时拒绝视觉推理，避免静默 CPU 推理拖长任务。
- APP 历史记录在释放 SQLite 全局锁后读取结果快照，不再持锁串行访问外部服务。
- 排队不设置五分钟失效限制；本次没有增加排队到期机制。

不可变脚本版本：`download_record_result/1.1.0`、`download_record_tags/1.2.0`、`media_generate_tags/1.4.2`、`message_submit_attachment_download/1.2.1`。下载 Schema V7、Scheduler Flyway V180。

## 生产验收

证据：`acceptance.json`、`robot-boundary.json`。专用账号、合成 PNG，未发送外部机器人消息。

1. 明确设置 `DOWNLOAD_ENRICHMENT_PAUSED=true`，通过真实 APP 网关上传 4 个合成文件。初始完成检查只读 Download 数据库，不靠 APP 详情查询触发对账。
2. 4 个请求全部成功，用时分别为 2.1223、2.2574、2.1972、2.3966 秒（Download 请求创建至成功状态落库）。此时 4 条 ENRICHMENT 记录仍是 PENDING，4 个文件 tag_status=PENDING。APP 全部返回下载成功、postProcessingStatus=PENDING。
3. 重复上传相同块、重复 complete 得到相同请求；其他账号访问详情返回 404。
4. 恢复消费者并重启 Download，4 条持久任务全部 SUCCEEDED、4 个文件全部 TAGGED；下载状态始终 SUCCEEDED。此前第一轮 4 个文件也完成全部标签。检查时共 36 条 ENRICHMENT 作业成功，其中含发布后既有业务任务；该数量不是 36 个独立验收样本。
5. 使用现有已完成附件的绑定，运行实际发布 submit 1.2.1 脚本调用 Messaging execute 接口，绑定未变，无轮询子任务。完整包装任务重放被既有 resolve_provider_file 的终态保护拒绝；因此本项仅证明 submit 脚本的真实 HTTP 边界，不宣称真实新入站 Telegram 全流程已验收。单元测试覆盖不创建子任务的行为。
6. 最终 Download、Scheduler、Executor 健康接口均 UP；`DOWNLOAD_BACKGROUND_ENABLED=true`、`DOWNLOAD_ENRICHMENT_PAUSED=false`。

上述 2.12～2.40 秒是空闲环境下小型合成文件的端到端完成时间，不代表所有远程站点、大文件或批量历史积压都能达到同样时延。

## 发布过程中修正的问题

V180 首次执行遗漏既有必填字段 child_aggregation_policy_json，被数据库拒绝。只读确认新集群、节点关联和任务定义没有部分业务写入后，使用官方 Flyway repair 修复失败历史记录，再从新发布目录启动修正 JAR。没有通过脚本直接修改 Scheduler 业务表。新增真实基础表迁移测试覆盖该约束。原失败发布包保留，已成功应用的迁移不再改写。

Schema V7 回填活跃请求后，发现 29 条历史附件的 Messaging 包装任务已终态失败且 downloadRequestId 为空，另有一条 9 月的 APP 无绑定请求不符合当前协议。后台将对应对账记录明确标为 FAILED，错误码分别为 MESSAGE_JOB_UNBOUND_TERMINAL 和 BACKGROUND_REQUEST_INVALID，保留原始请求与消息状态，不无限重试、不伪造成功。这 29 条异常不等于 29 个新的文件下载失败；部分底层文件实际已成功保存。它们仍需按历史附件恢复流程单独处理，不能用本次性能修复视为已恢复。

生产目录：

- 第一版异步发布：`/opt/yuyutian/mytools/releases/download-async-20261104-v1`
- Scheduler/Executor 修复发布：`/opt/yuyutian/mytools/releases/download-async-schema-repair-20261104-v1`
- 最终 Download 后台消费者：`/opt/yuyutian/mytools/releases/download-async-worker-20261104-v2`

## 检查结果

- Download unittest：63 passed。
- 新版本脚本 pytest：49 passed、5 subtests passed。
- Scheduler `DownloadAsyncMigrationTest`：通过，覆盖真实基础约束、并发隔离、四类下载版本、文本流程保持原状及附件提交版本。
- APP 下载回复/历史策略脚本：通过。
- APP signed HAP assemble：通过。原有 DownloadToolsPage.ets:79 剪贴板权限警告仍存在，非本次改动引入，未新增权限。
- Python 编译及 git diff --check：通过。

## 回退与范围

维护时可将 DOWNLOAD_ENRICHMENT_PAUSED=true 并重启 Download，后处理留在数据库；RECONCILE 仍运行。恢复 false 后自动接管。只有已排空异步工作后才可关闭 DOWNLOAD_BACKGROUND_ENABLED。要回退生产者步骤，应新增前向 Flyway 迁移恢复旧同步版本和启用步骤，不修改已成功应用 V180，不直接 DML Scheduler 表。旧版本脚本仍保留在发布根目录。

本次解决占槽轮询、同步后处理和历史查询锁争用。没有调整各站点批量并发公平策略，没有重构 Scheduler 步骤耗时埋点，也没有更改音频归档目录所需的元数据规则。APP 改动已构建，未自动安装到用户手机。
