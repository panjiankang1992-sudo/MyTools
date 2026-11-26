# 下载批量失败分析与修复

2026-10-18 14:55–15:05（Asia/Shanghai）。通过本机当前 SSH 配置连接 Ubuntu，关联生产 Messaging、Automation、Download、Scheduler、Storage 记录与日志。只输出统计、任务标识、错误类别和必要公开域名，未保存凭据或消息正文。

## 定位结果

本次属于先前 19 个附件完成后的新增批次，不能沿用先前成功快照判断本批状态。

| 问题 | 证据 | 结论 |
| --- | --- | --- |
| 附件入口排队过期 | 86 个 `message_download_attachment` 为 TIMED_OUT，86 个均没有任何 task_execution；创建后恰好 300 秒到 dispatch_deadline_at。初查 Messaging 中 85 FAILED，另 1 条 QUEUED 尚未对账。 | 尚未开始下载即因入口队列期限过短失败。实际下载含 GPU 标签步骤，已有 7200 秒排队预算；包装入口未继承，只有 300 秒。 |
| 自动化查询连接饥饿 | Automation 同期大量 CannotGetJdbcConnectionException；池容量 3；AutomationRepository.mapRun 在主查询持有连接时嵌套 findActions。 | 并发查询持连接再申请第二连接，对账重试耗尽，出现 AUTOMATION_006。143 个动作记录该错误，但与下载失败、成功存在交叉，不能与其他行相加。 |
| 发布文件名超长 | 2 个 HTTP 任务 download_asset 成功，大小 21811、20283 字节，publish_asset 三次重试失败；Storage 为 STORAGE_007。最终路径单段实际 257、296 字节。 | 百分号编码扩长文件名，超过 Linux 单段 255 字节限制，实际文件已下载。 |
| 地址校验拒绝 | 5 个 HTTP 任务为 `url host resolves to a non-public address`，原始地址域名 telegra.ph。 | 当时解析或重定向目标触发公网地址校验；当前域名解析结果已不同，未保存当时具体地址，不能仅凭当前解析确定历史 DNS 根因。保留 SSRF 防护，未放宽私网访问。 |
| Telegram 大文件限制 | 1 个真实附件下载失败，文件 26545013 字节，video/mp4，getFile 三次 HTTP 400。 | 与官方云 Bot API 20 MB 下载上限一致。不能靠重复下载修复，需要支持大文件的接入通道。 |

其他可选步骤曾出现 GPU_BUSY 与缩略图生成失败，但许多所属下载任务最终 SUCCEEDED，不应将所有步骤重试计为文件下载失败。

下载接入层初查：流式附件 117 成功、1 失败；HTTP 14 成功、7 失败。附件入口过期发生在创建真实下载请求前，因此不包含在这组统计内。

## 已部署修复

1. **AutomationRepository**：RowMapper 只映射主记录，主查询释放连接后加载动作；按消息、按运行、事务 FOR UPDATE 查询均使用同一补全路径，保留原响应字段。
2. **GpuTaskRepository**：`message_download_attachment` 继承下游含 GPU 下载的 7200 秒排队预算；执行超时仍为 60 秒。普通 CPU 任务默认 300 秒、原有更长自定义期限均保持原行为。未扩大并发或关闭截止回收。
3. **download_publish_file 1.2.1**：新不可变版本将编码后的文件名限制到 255 字节；截断时保留扩展名并加入原始名称摘要，避免同前缀名称碰撞；人类可读 fileName 与内容不变。Flyway V175 已成功将现有发布步骤切换为新版本。

发布路径：

- `/opt/yuyutian/mytools/releases/download-reliability-20261018-v1`
- `/opt/yuyutian/mytools/releases/download-publish-name-fix-20261018-v1`

均基于当前生产 JAR 或脚本根目录增量发布，保留其他类、既有 GPU 队列修复、启动参数及配置。脚本索引使用新文件，未修改旧硬链接索引。迁移通过 Scheduler Flyway 执行，没有脚本直接修改 Scheduler 数据库。

## 验证与边界

- AutomationConnectionPoolTest：容量 1 的真实 Hikari 池中，运行和动作读取均通过。
- AttachmentQueueBudgetTest：入口与下游相同 7200 秒、普通任务 300 秒、10800 秒配置不被缩短；通过。
- 新发布脚本 8 项测试通过：实际临时文件写入、中文与百分号编码长度、扩展名保留、稳定摘要、同前缀名称区分、展示名不变、旧流程校验。
- 生产 Automation 12 并发、24 次真实状态 GET 全成功，最长 0.032 秒。
- 生产 Flyway V175 success=1；2 个发布步骤均为 1.2.1；Scheduler、Automation、Executor 均 active。
- 扩展运行既有 GpuDispatchAdmissionTest 时，测试上下文被原有 V141 的 H2 不支持 JSON_SET 阻断，未进入 GPU 集成断言。未为本次修复修改旧迁移；不宣称完整 GPU 集成测试通过。
- 历史 86 个超时入口、已终态失败的自动化动作与通知没有通过直接改库伪造成功，也未批量重放。当前修复作用于新任务；恢复历史失败需要保留来源、幂等与审计的显式重试流程。
- 26.5 MB 视频和历史地址校验失败未自动恢复。官方限制依据：https://core.telegram.org/bots/features 与 https://core.telegram.org/bots/api#getfile 。
- 尚未收到部署后的新一批用户消息，未宣称新批次全链路完成。

## 回退

两个发布目录分别对应 systemd drop-in。保留旧 JAR 和旧脚本根目录；可移除本次覆盖并 daemon-reload/restart 恢复旧命令。V175 已切换脚本版本，回退发布脚本时应继续保留 1.2.1 可用，或通过新的受控迁移恢复旧引用，不能只让执行器丢失已引用脚本包。
