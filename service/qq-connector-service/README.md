# QQ Connector Service

官方 QQ Bot 原子连接器。它维护 Gateway，把私聊、群 @、频道与频道私信中的正文、嵌套 `msg_elements` 和附件标准化写入 Messaging。消息读取与引用恢复只使用官方下发事件和本地缓存，不依赖 OneBot。授权发送者提交的 URL 或附件由 Message Automation 创建下载任务，连接器提供内部鉴权文本接口发送任务完成回执；被动回复窗口失效时自动降级为主动消息。

纯 QQ Bot 模式不设置 `ONEBOT_CONNECTOR_INTERNAL_TOKEN` 即可启动，精确“登录/登陆”文本此时作为普通消息处理。兼容旧流程：配置该令牌时，授权发送者的纯文本登录命令仍可创建 `onebot_relogin` 任务并读取二维码；引用和复合消息不触发该控制命令。

嵌套消息按原有数组顺序展开，TEXT 分段中的 `qq-message` 标记携带层级路径、引用关系和平台实际提供的发送者、时间信息；保留重复正文，附件去重但保留重复出现的位置。支持图片、视频、文件、语音及官方语音识别文本，正文和 TEXT 分段同时移除已结构化的附件 URL，避免重复下载。`payload` 内嵌 JSON 和旧附件摘要文本属于兼容格式，预览图、头像等任意对象不会被当作附件。

官方引用索引读取 `message_scene.ext` 中的 `msg_idx` / `ref_msg_idx`，以及 `msg_elements` 节点的 `msg_idx`。有内联正文优先使用；仅有索引时在同应用、同账户、同会话的持久化缓存中恢复原文。缓存默认位于 `QQ_CONNECTOR_INBOUND_WAL_PATH + ".references.sqlite3"`，可用 `QQ_CONNECTOR_REFERENCE_STORE_PATH` 覆盖，部署时应指向 release 目录外。默认保留 7 天、最多 4096 条、合计正文 64 MiB、单条 256 KiB；重放不延长保留期。Gateway 在唤醒并行 worker 前写入索引，减少引用先于原文处理的竞争。

读取上限为 32 层、4096 个节点、100 万正文字符及 500 个 Messaging 分段（预留一个错误提示分段）。缺失原文、缓存未命中、坏结构、不可下载附件或超限时，正文与 TEXT 分段包含 `qq-incomplete` 及原因，不将其伪装成完整读取。这里只保证读取已下发或已缓存的结构，不能读取平台根本没有推送的转发记录，也没有调用非官方补取接口。实现依据和复验场景见 [官方 QQ 嵌套消息说明](../../docs/research/2026-10-25-qqbot-nested-messages.md)。

凭据只来自服务端环境；连接器不执行 Shell、不接受路径、不直接修改业务数据库。同一 AppId 不得同时启用两个 Gateway 消费者。

每个受支持的 Gateway Dispatch 都先写入 `QQ_CONNECTOR_INBOUND_WAL_PATH` 指向的 release 外原子文件 WAL，再持久化 session/seq checkpoint 并推进内存游标。独立 worker 从 WAL 向 Messaging 投递，瞬时错误有限退避且不阻塞后续事件；预算耗尽记录保留在有界可恢复 DEAD 区。运维可使用带内部 Bearer 鉴权的 `POST /internal/v1/inbound-wal/{eventKey}/redrive` 精确恢复单条记录，永久坏载荷不可恢复。

登录命令另以 `QQ_CONNECTOR_LOGIN_WAL_PATH` 为运行时真源；worker 可在进程重启后继续使用 Scheduler 幂等键恢复任务，并以有限退避进入完成或 DEAD。当前 MySQL `qq_command_outbox` 与 `qq_connector_checkpoint` 表仅为预留模型，不参与运行时投递，禁止与本机 WAL 同时作为运行时真源。

自动化文本回执必须携带 `idempotencyKey`，并先写入 `QQ_CONNECTOR_OUTBOUND_WAL_PATH`。同键同载荷的已完成请求直接成功返回，不同载荷返回冲突；瞬时失败有限重试，重试耗尽后可通过 `POST /internal/v1/outbound-wal/{eventKey}/redrive` 人工恢复。该端点会立即续投，并允许同一事件在人工恢复中断或瞬时失败后再次调用；完成后还需恢复对应 Automation completion outbox，使页游标和发布状态收敛。发送成功到 DONE 落盘之间崩溃时采用有界 at-least-once，极小概率会重复主动消息；主动 C2C/群消息每次尝试使用新的 `msg_seq`，不把它当作平台幂等证明。容量由 `QQ_CONNECTOR_OUTBOUND_MAX_RECORDS` 控制，重试预算由 `QQ_CONNECTOR_OUTBOUND_MAX_ATTEMPTS` 控制；健康接口仅暴露各状态计数，并在存在出站 DEAD 时返回未就绪。
