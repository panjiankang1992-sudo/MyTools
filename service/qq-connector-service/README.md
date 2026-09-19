# QQ Connector Service

官方 QQ Bot 原子连接器。它维护 Gateway、把 C2C 消息及附件标准化写入 Messaging，并仅对授权发送者的精确“登录/登陆”命令创建 `onebot_relogin` 任务。任务成功后从 OneBot Connector 读取新鲜二维码并回复原消息。授权发送者提交的 URL 或附件由 Message Automation 创建下载任务，连接器提供内部鉴权文本接口发送任务完成回执；被动回复窗口失效时自动降级为主动消息。

凭据只来自服务端环境；连接器不执行 Shell、不接受路径、不直接修改业务数据库。同一 AppId 不得同时启用两个 Gateway 消费者。

每个受支持的 Gateway Dispatch 都先写入 `QQ_CONNECTOR_INBOUND_WAL_PATH` 指向的 release 外原子文件 WAL，再持久化 session/seq checkpoint 并推进内存游标。独立 worker 从 WAL 向 Messaging 投递，瞬时错误有限退避且不阻塞后续事件；预算耗尽记录保留在有界可恢复 DEAD 区。运维可使用带内部 Bearer 鉴权的 `POST /internal/v1/inbound-wal/{eventKey}/redrive` 精确恢复单条记录，永久坏载荷不可恢复。

登录命令另以 `QQ_CONNECTOR_LOGIN_WAL_PATH` 为运行时真源；worker 可在进程重启后继续使用 Scheduler 幂等键恢复任务，并以有限退避进入完成或 DEAD。当前 MySQL `qq_command_outbox` 与 `qq_connector_checkpoint` 表仅为预留模型，不参与运行时投递，禁止与本机 WAL 同时作为运行时真源。

自动化文本回执必须携带 `idempotencyKey`，并先写入 `QQ_CONNECTOR_OUTBOUND_WAL_PATH`。同键同载荷的已完成请求直接成功返回，不同载荷返回冲突；瞬时失败有限重试，重试耗尽后可通过 `POST /internal/v1/outbound-wal/{eventKey}/redrive` 人工恢复。该端点会立即续投，并允许同一事件在人工恢复中断或瞬时失败后再次调用；完成后还需恢复对应 Automation completion outbox，使页游标和发布状态收敛。发送成功到 DONE 落盘之间崩溃时采用有界 at-least-once，极小概率会重复主动消息；主动 C2C/群消息每次尝试使用新的 `msg_seq`，不把它当作平台幂等证明。容量由 `QQ_CONNECTOR_OUTBOUND_MAX_RECORDS` 控制，重试预算由 `QQ_CONNECTOR_OUTBOUND_MAX_ATTEMPTS` 控制；健康接口仅暴露各状态计数，并在存在出站 DEAD 时返回未就绪。
