# 官方 QQ Bot 嵌套消息读取

本次实现位于 `service/qq-connector-service`，目标是独立使用官方 QQ Bot 读取已收到的嵌套消息，不通过 OneBot、NapCat 或 `get_forward_msg` 获取内容。此前 OneBot 研究文档不作为这次实现方案。

## 官方依据

检查腾讯维护的仓库，固定到以下版本，避免把普通连续消息合批误认为平台合并转发：

- [qqbot-nodejs event-dispatcher.ts](https://github.com/tencent-connect/qqbot-nodejs/blob/429a0f1b798f3b0fcef3679e207dbc0469a1df2d/src/protocol/gateway/event-dispatcher.ts)：引用类型为 103；从 `message_scene.ext` 的 `msg_idx=...`、`ref_msg_idx=...` 和引用消息 `msg_elements` 提取索引；注释明确指出缓存未命中时，平台下发的引用元素是内容来源。
- [qqbot-nodejs quote-ref.ts](https://github.com/tencent-connect/qqbot-nodejs/blob/429a0f1b798f3b0fcef3679e207dbc0469a1df2d/src/middleware/quote-ref.ts)：引用正文可来自本地索引或内联元素；优先使用内联正文；附件含语音识别文本。
- [openclaw-qqbot types.ts](https://github.com/tencent-connect/openclaw-qqbot/blob/89988812442583bf47c34b89f9abf5fd4b85df0b/src/types.ts)：`MsgElement` 本身可继续携带 `msg_elements`，故需要递归展开，而不只读第一层。

以上是腾讯实现提供的能力依据。初始测试使用据此构造的合成事件；部署后应用户要求，又在真实 QQ 客户端自行制作文字、图片、两层合并转发及引用样例，端到端读取和图片下载成功。不能据此承诺所有 QQ 客户端显示的合并转发，都会向机器人下发完整消息树。

## 实现行为

| 场景 | 行为 |
| --- | --- |
| 普通正文和附件 | 写入标准 Messaging TEXT / ATTACHMENT 分段 |
| 多层 `msg_elements` | 深度优先、保留数组顺序，逐层读取正文和附件 |
| 已展开为正文的合并转发 | 保留平台提供的层级文本，从附件摘要读取下载地址 |
| 重复文本 | 原样保留，不全局去重 |
| 内联引用正文 | 直接使用，不额外查询缓存或网络 |
| 仅 `msg_idx` 的引用节点 | 查询同应用、账户、会话的缓存 |
| 仅 `ref_msg_idx` 的引用 | 建立引用子节点后查询缓存 |
| 图片、视频、文件、语音 | 正确映射 IMAGE / VIDEO / FILE / RECORD |
| 语音 WAV 与原始地址 | 优先 WAV，原始地址作为去重别名；保留识别文字 |
| 同一附件重复出现 | 只创建一次附件分段，保留出现位置标记 |
| 无正文的预览卡片、未知引用 | 输出 `qq-incomplete`，不把预览当完整正文 |
| 太深、太宽、分段或正文超限 | 保留已读部分，附明确的截断提示 |
| 引用里的登录命令 | 作为消息内容，不触发旧 OneBot 登录桥接 |

Messaging 现有接口没有树形元数据字段，本次使用有序 TEXT 标记保存路径和上下文，避免额外修改共享数据库模型。例如：

```text
current request
[qq-message path=0.0 relation=quote metadata={"sender":"Alice"}]
quoted text
[qq-message path=0.0.0 relation=element metadata={}]
nested text
```

附件分段插入所属节点的正文之后、子节点之前。平台没有提供的发送者和时间不猜测。TEXT 分段拼接结果与 body 一致，防止两个入口触发不同的自动化结果。带时区的平台时间用于 receivedAt。

## 本地引用恢复与边界

缓存保存实际收到的原始事件，按自身 `msg_idx`（缺失时用消息 ID）索引，绝不使用 `ref_msg_idx` 覆盖被引用者。缓存独立于入站 WAL，WAL 继续负责投递恢复。接收 Gateway 事件时在推进 checkpoint 和唤醒 worker 之前建立引用索引；重新投递同一事件不会覆盖原文。SQLite 事务支持并发 worker，单次连接使用后关闭。

缓存默认保留 7 天、4096 条、正文总计 64 MiB、单条 256 KiB。TTL 或容量淘汰、连接器启动前的消息、其他会话的消息、平台没有下发的原文、没有可匹配索引的机器人出站消息，都可能无法恢复，此时明确标记 `reference_unavailable`。超大事件仍走正常入站解析，但不会在缓存内保存部分摘要冒充原文。

本次没有实现凭 ID 查询任意私聊/群历史或展开平台未下发的合并转发资源。官方频道取单条消息的接口不能当作通用群/私聊历史接口。也没有将 SDK 的连续消息合批 `mergedMessages` 当作官方合并转发协议。

## 验证

本地使用 Python 3.12.13 运行该服务完整测试集：179 项通过。Ruff 的 E9/F 检查通过，Git diff 空白检查通过。2026-10-25 已部署至 Ubuntu，服务器以服务账户复跑 179 项测试通过。同日使用真实 QQ 客户端自行构造样例，一层合并、两层嵌套及引用嵌套记录均保留内层文字并成功下载图片。详见 [部署记录](../verification/2026-10-25-qqbot-nested-deployment.md)。

实测 `message_type=102` 的合并记录通过 `content` 下发带层级标记的全文，并没有 `msg_elements`；`message_type=103` 的引用则包含一个内联 `msg_elements`。这说明接收端必须同时兼容正文摘要和结构化元素。此处只保留脱敏描述和自行生成的测试素材，不保存附件签名 URL、用户标识或私人正文。

覆盖三层嵌套、顺序与元数据、重复文本、嵌套 JSON、内联引用优先、仅索引引用、引用循环、结构循环、语音、附件别名与 URL 去重、120 个附件、500 分段上限、深度与字符限制、预览卡片、损坏结构、重启恢复、会话隔离、缓存 TTL / 条数 / 字节预算及并发写入。连接器集成测试同时检查官方 QQ 到 Messaging 的载荷、平台时间、Gateway 索引顺序以及不配置 OneBot 的启动行为。

```bash
cd service/qq-connector-service
PYTHONPATH=src python -m pytest -q
```

后续可补充三层转发、重复图片、未知历史引用的真实客户端验收；仅索引缓存恢复此次仍以自动化测试为证。检查实际 Gateway 的 `content/msg_elements` 与 Messaging 分段的对应关系，平台未下发且未缓存的内容应明确提示不完整。
