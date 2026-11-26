# QQ 嵌套合并转发消息读取研究

研究日期：2026-10-25。

## 结论与验证范围

正确读取需要先按协议还原消息树，再沿树中未解析的转发引用补取内容。已经内嵌的内容应直接消费；不能对每个内层消息 ID 都调用 `get_forward_msg`。还必须保留节点顺序、发送者、时间和引用位置，最后才能生成正文或下载附件。

MyTools 已有原始入站事件持久化、异步展开、租约恢复、有限重试、终态发布和附件去重。这些机制可以继续复用。但当前解析器和扁平保存方式尚不能保证完整读取所有嵌套形态。

本次核对了本地实现、OneBot v11 规范、NapCat 源码快照 `3420f0b76d7dc0ac92cde1663f21ea27f61e13bb`、SnowLuma v2.2.0，以及使用合成数据对现有 Java/Python 方法的直接调用。没有连接生产 QQ、读取实际聊天记录或变更线上配置；上游源码快照不能视为当前部署镜像的版本证明。本轮未修改业务代码。

本地验证：

- Messaging 的 `OneBotInboundAdapterTest`：27 项通过，H2 内存数据库。
- 官方 QQ Connector 的 `test_nested_and_composite_attachments_are_deduplicated`：1 项通过。
- 附加重放使用实际 `OneBotInboundAdapter.extractParts`、`MessagingRepository` 和 Connector 方法，没有另写一个模拟解析器来代替被测代码。
- [重放观察结果](/Users/pankang/mycode/MyTools/docs/research/2026-10-25-onebot-nested-forward-probes.json)仅包含合成内容。

## 先确定消息来自哪一条入口

| 入口 | 当前实现 | 读取边界 |
| --- | --- | --- |
| NapCat / OneBot | `onebot-connector-service` → Messaging | 可以调用 OneBot `get_forward_msg`，需要理解 `forward`、`node` 和消息信封。 |
| 官方 QQ Bot Gateway | `qq-connector-service` | 当前扫描已投递事件中的附件、内嵌 JSON 和特定附件描述文本；这是附件提取，不等于恢复整棵聊天记录树。 |

官方入口当前只把顶层 `content` 作为正文，递归附件提取不会保留内层发言者和发言顺序，也没有 OneBot 合并转发查询接口。不能把两条入口的消息 ID 或接口混用。以上是本地代码边界，不能据此推断腾讯未投递内容可以被恢复。

证据：[官方入口正文与附件处理](/Users/pankang/mycode/MyTools/service/qq-connector-service/src/mytools_qq_connector/client.py:219)、[递归附件提取](/Users/pankang/mycode/MyTools/service/qq-connector-service/src/mytools_qq_connector/client.py:295)。

## 三种协议形态需要分别处理

### OneBot v11 标准

事件中的 `forward.data.id` 是合并转发引用。标准查询使用 `get_forward_msg({"id":"..."})`，返回的 `data.message` 是 `node` 消息段数组，内容位于 `node.data.content`；内容可以是消息数组，也可以是字符串。

来源：[OneBot API](https://github.com/botuniverse/onebot-11/blob/master/api/public.md#get_forward_msg-获取合并转发消息)、[消息段规范](https://github.com/botuniverse/onebot-11/blob/master/message/segment.md)。

### NapCat 实现

所查版本接受 `message_id` 或 `id`，返回 `data.messages`。其中通常是带 `sender`、`time`、`message` 的消息对象；内层 `forward.data.content` 可能已经包含下一层完整消息。

NapCat 源码的 `parseMultiMessageContent` 继续以数组格式递归解析内部消息，并明确标注内部生成的消息 ID 仅用于查看。查询实现也有内层消息无法单独获取的失败分支。

因此，以下形态应该直接沿 `content → message` 读取，不能因为还带着 `id` 就再次查询：

```json
{
  "type": "forward",
  "data": {
    "id": "outer-reference",
    "content": [
      {
        "sender": {"user_id": "10001", "nickname": "Alice"},
        "time": 1700000000,
        "message": [
          {"type": "text", "data": {"text": "outer text"}},
          {
            "type": "forward",
            "data": {
              "id": "inner-reference",
              "content": [
                {
                  "sender": {"user_id": "10002", "nickname": "Bob"},
                  "time": 1700000001,
                  "message": [{"type": "text", "data": {"text": "inner text"}}]
                }
              ]
            }
          }
        ]
      }
    ]
  }
}
```

示例 ID 和内容均为合成值，不代表真实 QQ 标识。

来源：[NapCat 转发查询](https://github.com/NapNeko/NapCatQQ/blob/3420f0b76d7dc0ac92cde1663f21ea27f61e13bb/packages/napcat-onebot/action/go-cqhttp/GetForwardMsg.ts)、[内嵌转发构造](https://github.com/NapNeko/NapCatQQ/blob/3420f0b76d7dc0ac92cde1663f21ea27f61e13bb/packages/napcat-onebot/api/msg.ts#L568)、[内层解析与 ID 约束](https://github.com/NapNeko/NapCatQQ/blob/3420f0b76d7dc0ac92cde1663f21ea27f61e13bb/packages/napcat-onebot/api/msg.ts#L1067)。

### SnowLuma 实现

SnowLuma v2.2.0 也返回 `data.messages`，但内层可能保留为仅带资源 ID 的 `forward`，需要再次调用接口。其底层会把外层响应携带的内层数据建立缓存，通过内层资源 ID 再查询时可以直接命中缓存。

这说明两种行为都需要支持：有完整内嵌内容就读取，没有内容只有引用才补取。不能把“NapCat 不必查询已经内嵌的节点”误解为“所有 provider 都只请求一次”。

来源：[SnowLuma 外层下载及内层缓存](https://github.com/SnowLuma/SnowLuma/blob/v2.2.0/packages/core/src/bridge/apis/forward.ts#L739)、[三层嵌套及同级分支测试](https://github.com/SnowLuma/SnowLuma/blob/v2.2.0/packages/core/tests/actions/forward-nested-fetch.test.ts#L252)。上游测试是实现证据，本次没有运行 SnowLuma 真机。

## 当前实现的已确认问题

| 问题 | 本地观察或代码证据 | 影响 |
| --- | --- | --- |
| 标准 `node.data.content` / `node.data.message` 漏读 | 两种合成样本均抛出 `OneBotPayloadInvalidException`。解析器跳过普通节点的 `data`，只对 `forward` 入栈。 | 标准节点数组中的文字和附件可能完全漏掉。 |
| 已内嵌的转发仍被排入查询 | 两层内嵌样本已经读到全部文字和图片，却仍返回外层、内层两个待查询 ID。 | 遇到只能查看的内层 ID，可能增加无效请求和最终失败提示；这是由本地重放和 NapCat 源码共同支持的条件性结论。 |
| 字符串和卡片形态未识别 | 内层字符串、`com.tencent.multimsg` JSON 卡片均不能正常提取；CQ 字符串样本只留下前缀，转发引用消失。 | 有时只看到卡片摘要，或把真实消息当成空消息。 |
| API 响应只接受复数 `messages` | 给真实 Connector 方法提供标准的单数 `message`，报 `returned no messages`。 | 跨 OneBot provider 的兼容性不足。 |
| 文本去重改变内容 | 两个展开任务都包含同一句话，仓储最终只保存一句。 | 无法区分重复存储与两个人确实说了同一句话。 |
| 异步完成顺序替代原文顺序 | 先完成 B、再完成 A，仓储正文就是 B、A。 | 多层对话或带步骤的说明会发生顺序变化。 |
| `message` / `content` 别名都遍历 | 同一消息同时含等价字段时，解析器产出两份文字。 | 依赖后续按文字去重来掩盖结构问题，进一步误删合法重复发言。 |
| 发送者、时间、层级丢失 | 带 Alice/Bob 与时间戳的两个信封只被保存为两个文本分段。 | 下载用途或许够用，准确阅读和 AI 总结不够。 |
| Connector 静默截断 | `messages[:500]` 不返回截断标志。前 500 节点引用同一附件、第 501 节点引用另一附件时，处理后只剩第一份附件，`truncated=false`。 | 可以在无任何截断提示的情况下丢失附件。 |

代码位置：[解析器](/Users/pankang/mycode/MyTools/service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/service/OneBotInboundAdapter.java:92)、[固定查询契约](/Users/pankang/mycode/MyTools/service/onebot-connector-service/src/mytools_onebot_connector/connector.py:74)、[Connector 截断](/Users/pankang/mycode/MyTools/service/onebot-connector-service/src/mytools_onebot_connector/service.py:408)、[展开结果保存](/Users/pankang/mycode/MyTools/service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/repository/MessagingRepository.java:577)、[扁平分段模型](/Users/pankang/mycode/MyTools/service/messaging-service/src/main/java/com/yuyutian/mytools/messaging/model/InboundMessagePart.java:8)。

现有测试主要覆盖任务持久化、失败重试、幂等、并发终态和预算截断，很多模拟展开结果直接给出图片或文件段；27 项通过不代表上述协议形态已经覆盖。

## 建议的正确读取流程

1. **先可靠接收。** 保留现有 WAL、原始事件、外层消息幂等键和异步展开流程。接收确认与最终解析完整性分别记录。
2. **做 provider 级归一化。** 标准 OneBot 的 `data.message`、NapCat/SnowLuma 的 `data.messages` 转成统一节点数组；按实际 provider 决定请求参数。所有 ID 按不透明字符串保存，区分外层消息 ID、合并转发资源 ID、内部展示 ID。
3. **按结构读取。** 普通信封读取 `message`；标准 `node` 读取 `data.content`；已展开的 `forward` 读取 `data.content`。兼容别名由适配器选择一个有效规范字段；两份字段冲突时记录异常，不能盲目合并。字符串正文直接保留；若支持 CQ 字符串，必须解析 CQ 和转义规则，不能用正则删除后声称完整读取。
4. **只补取未解析引用。** 有有效内嵌子节点则直接展开；只有引用才创建查询任务。空数组、缺失字段、失败或不支持结构需要区分，不能把它们统一视为成功的空内容。
5. **按来源识别转发卡片。** JSON 仅对已识别的 `app=com.tencent.multimsg` 从 `meta.detail.resid` 取资源引用；预览 `news`、`summary` 不是完整正文。XML 仅对已识别的转发格式解析 `m_resid`，禁用外部实体。`uniseq` / `m_fileName` 是可能用于内部映射的另一类标识，不能随意当资源 ID 使用。完整协议解析由 provider 负责。
6. **保存消息树。** 至少保存外层根消息、父节点、树内路径、兄弟顺序、发送者、时间、节点类型、来源引用及读取状态。外层发消息的人和转发节点声明的发言者分别保存；后者只是转发内容中的身份信息。
7. **取数可并行，输出必须按树排序。** 提前为每个节点分配位置，例如 `0/1/2`。结果存回对应位置，最终按节点位置生成正文，不能按接口完成时间追加。
8. **分开三种去重。** 入站事件按外层消息键去重；资源请求按 `provider + account + reference` 合并；文件内容按资产校验值去重。相同文字和重复出现的引用位置必须保留。共享引用不等于循环：循环检查使用当前祖先链，已完成缓存可供多个位置复用。
9. **完整性显式表达。** 对整条消息统一限制深度、请求数、节点数、字节数和总时限；任何限额触发都保存截断位置和原因。失败分支可重试，成功分支保留，最终区分完整、部分可读、读取失败。
10. **最后派生业务用途。** 从同一棵树生成带发言者与层级的阅读文本、附件下载清单和 AI 输入。转发内容作为引用材料保存，与外层用户指令分开。附件保留所属节点、provider 文件标识和必要的会话上下文；临时下载地址不作为唯一长期索引。

卡片格式依据：[NapCat 转发卡片生成](https://github.com/NapNeko/NapCatQQ/blob/3420f0b76d7dc0ac92cde1663f21ea27f61e13bb/packages/napcat-core/helper/forward-msg-builder.ts)、[SnowLuma JSON/XML 嵌套测试样本](https://github.com/SnowLuma/SnowLuma/blob/v2.2.0/packages/core/tests/actions/forward-nested-fetch.test.ts#L9)。

```text
外层消息
├─ 节点 0：Alice 的文字与图片
├─ 节点 1：合并转发
│  ├─ 节点 1/0：Bob 的文字
│  └─ 节点 1/1：另一条合并转发引用 → 缺内容才查询
└─ 节点 2：Carol 的文件

网络可以先返回节点 2 的数据；最终阅读顺序仍由树内位置决定。
```

## 建议实施顺序与验收

第一阶段优先修读取正确性：补 `node`、消息信封和卡片适配；内嵌优先；兼容返回字段；所有裁剪都报告不完整；建立与 provider 版本对应的脱敏夹具。这一阶段不需要替换 NapCat。

第二阶段补消息树与稳定顺序，取消按文本内容去重，再从树派生现有平面 `parts`，使既有下载流程可以继续使用。

至少验收以下场景：普通图文、三层内嵌转发、三层仅引用转发、标准 `node`、JSON/XML 转发卡片、字符串/CQ、等价别名、相同文字不同发言者、同一资源多处引用、引用循环、响应乱序、单分支过期、网络超时后恢复、第 501 节点含独有附件、已收到内容但内层 ID 无法查询。

真实环境最终仍需用测试账号发送上述样本，核对原始事件、展开响应、规范消息树、实际附件字节以及原会话回执，才能宣称生产链路完整读取。
