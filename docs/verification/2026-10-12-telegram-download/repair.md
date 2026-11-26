# Telegram 附件下载故障修复（2026-10-12）

## 故障证据

通过当前本机 SSH 配置连接生产 Ubuntu，关联 Messaging、Download Ingestion、Scheduler 数据库及轮转日志，仅输出状态和脱敏错误。

- 修复前最近 30 天 Telegram 附件作业：28 条 `FAILED / ATTACHMENT_TASK_FAILED`，15 条 `RESOLVED`，均未绑定下载请求。
- 附件包装任务 43 条失败，共 129 次步骤失败，记录为 `REMOTE_SERVICE_UNAVAILABLE / HTTP Error 500`。
- 10 月 6 日轮转日志揭示真实调用链：Scheduler 拒绝参数返回 422，Download Ingestion 转为 503，Messaging 最终暴露 500。
- Messaging 的流式请求携带 `resourceUsername`、`assetMimeType`、`receivedAt`；生产 `download_message_attachment` 的 schema 未声明这三个字段，且 `additionalProperties=false`。因此请求在下载任务创建前被拒绝。
- 最近七天 23 条流式请求停留 `ACCEPTED`，没有 Scheduler 绑定。15 条可恢复作业对应其中部分请求，其他已终态失败作业没有自动重放。
- 最近七天其他下载任务：X post 56 成功、2 超时，HTTP 66 成功，X user 2 成功。它们与附件参数拒绝属于不同情况。

## 修复与生产验证

新增 Flyway V172，为流式附件协议增加上述元数据字段，保留必填字段、20 GiB 限制及禁止未知字段。迁移按任务名称匹配，并递增版本。

发布方式：复制当前生产 Scheduler JAR，仅追加新迁移；沿用全部启动参数、配置和已有迁移。生产无运行中任务时重启 Scheduler。首次新增 systemd drop-in 被更晚的既有覆盖项压过，已修正排序，并增加有效 ExecStart 和真实迁移检查，防止仅凭服务 active 宣称发布成功。

- 生产 `flyway_schema_history` 核验 V172 `success=1`。
- 一个真实 Telegram 附件先行验证：下载、存储发布、资产登记、媒体登记、结果记录、标签生成、标签记录均成功。
- 随后仅通过 Messaging 已有 execute API 恢复余下 14 条 `RESOLVED` 作业，再通过 GET API 对账。
- 15 条恢复请求最终全部 `SUCCEEDED`，15 个文件，总计 16,258,791 字节。
- 保留 28 条原终态失败记录；没有直接修改 Scheduler 数据库，也没有把失败状态改成成功。
- 当前 Messaging 健康检查 UP，15 次真实恢复未复现早前数据库连接池超时；未确定该短暂超时的独立根因，因此未扩大连接池或重启 Messaging。

## 验证范围

`StreamedAttachmentMetadataContractTest` 通过：H2 执行新迁移、任务版本递增、其他任务不变、旧请求兼容、新元数据合法、未知字段拒绝、超限大小拒绝、路径穿越用户名拒绝、无效时间拒绝、缺失附件作业标识拒绝。

`mvn -q -f service/task-scheduler-service/pom.xml compile` 通过。部署脚本 Python 语法检查通过。

真实先行样本的可选缩略图步骤报 `ffmpeg produced an invalid thumbnail`，未阻断下载、入库或标签。此次验证确认主下载链路恢复，不代表所有历史缩略图均可生成，也没有测试机器人新的入站消息或完成通知。

发布路径：`/opt/yuyutian/mytools/releases/streamed-attachment-metadata-20261012-v1`。
迁移 SHA-256：`0c570b93eb9e850a69a48baa5d6021cacfb3c806e0ca5b7b6def73b68477e319`。
回退：移除本次 systemd drop-in，daemon-reload 后重启 Scheduler；V172 仅增加可选参数，对旧调用保持兼容，无需回滚数据库协议。
