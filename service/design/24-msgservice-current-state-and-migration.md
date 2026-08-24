# MsgService 现状审计与迁移设计

## 已核验实现

远程主机上的权威代码位置为 `/opt/code/MsgService`。允许从该目录生成仅包含源码和安全配置样例的压缩包，下载到本地仓库外进行只读核验；打包时必须排除 `.env`、密钥、SQLite 数据库及 WAL/SHM、附件、日志、`node_modules` 和构建产物。当前核验副本位于 `/Users/pankang/mycode/MsgService-remote`，它不是迁移数据源，也不得提交到 MyTools 仓库。服务采用 Node.js 22、TypeScript、Fastify、Nodemailer、ImapFlow、mailparser 和 SQLite，提供 REST 与 MCP 接口。发件流程先写入 `queued`，同步调用 SMTP 后更新为 `sent` 或 `failed`；收件流程轮询 IMAP unseen 邮件，解析 MIME、打标签并以 `external_id` 去重。

SQLite 核心表为 `channels`、`messages`、`tags`、`message_tags`、`templates` 和 `known_recipients`。`messages` 同时保存方向、正文、地址 JSON、附件 JSON、模板引用、状态、外部标识、原文和时间。旧实现可能把解析后的附件 Buffer 直接 JSON 序列化，因此迁移不能只保留附件元数据。

2026-08-24 对运行库执行了不输出正文和地址的只读汇总：共 11 条发件记录，其中 9 条 sent、2 条 failed，6 个附件；入站记录为零；模板 7 条、已知收件人 5 条。随后在远程受控目录完成 SQLite online backup 演练，`integrity_check=ok`，消息与状态数量一致。6 个附件中 4 个已完成内容寻址归档并逐个验证 SHA-256，另 2 个旧记录引用的源文件在运行主机已经不存在，需以 `MISSING` 状态保留旧引用和一致数据库备份，不能标记为完整归档。发件冻结批次摘要为 `7561c4cff6405401aebfac4d20899afbdf6e4bf006bd9074b17d95bd830f36a9`，模板与已知收件人参考数据摘要为 `854e769b83cc5b001ee0ec8fbeb4e283b779049a4514092b08d76cdb771267b8`。

## 数据去向

- 发件记录进入 Messaging 的 `outbound_message_history`，不进入实时 `delivery_request`，不创建调度任务和 Outbox，避免历史邮件被重新发送。
- 附件先从一致备份提取到不可变内容寻址归档，校验大小和 SHA-256 后保存归档引用。若旧库引用的源文件本身已经丢失，则保留 `MISSING` 状态、旧内容引用和声明信息，并在对账中单独计数；不得伪造摘要或假定附件完整。
- 入站记录继续使用既有 `legacy-inbound` 契约；当前数量为零不代表删除该能力。
- 模板和已知收件人进入 Messaging 自有 `message_template`、`known_recipient` 表，通过一个有界批次执行 dry-run、幂等导入和对账。即使模板与内置内容相同也保留旧身份映射，避免提前猜测可再生性。
- 标签及消息标签当前为空，可重新生成；渠道凭据不迁移数据，只在新 Messaging 重新配置密钥引用。

## 执行顺序

1. 若实现发生变化，先从远程 `/opt/code/MsgService` 重新生成脱敏源码包并在仓库外核验；源码包不能代替数据备份。
2. 暂停旧服务写入，或在服务运行时调用 SQLite backup API 生成包含 WAL 状态的一致备份。
3. 记录源表数量、状态分布和稳定集合摘要；不得打印邮件正文、地址或凭据。
4. 提取附件到内容寻址归档，生成只含安全字段的清单并逐个校验。
5. 使用适配器装载冻结发件快照；将同一一致备份生成的参考数据批次一并提交 Messaging dry-run，再正式导入。
6. 重放同一批次应全部 skipped；核对 11 条消息、9/2 状态分布、6 个附件、4 个已归档摘要和 2 个源端已缺失证据。
7. 导出并核对模板和已知收件人后，才允许退役旧数据目录；旧服务代码和库在最终验收前保持不变。

## 实现边界

当前已新增历史发件归档 schema、受保护批量迁移接口、幂等冲突检测、集合对账、适配器发件快照接口及一次性 SQLite 导出器。剩余执行工作是从远程运行库生成受控迁移产物、先 dry-run，再正式导入并完成逐项对账；不引入消息总线、分布式事务或复杂切流机制。
