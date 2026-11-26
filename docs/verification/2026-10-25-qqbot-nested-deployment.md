# 官方 QQ Bot 嵌套消息部署记录

## 发布结果

- 2026-10-25 通过当前本地 SSH 配置直连 Ubuntu 部署，先验证连通性和服务状态。另一条已配置 SSH 入口核对为同一主机，只用于传输检查。
- 独立版本：`/opt/yuyutian/mytools/releases/qqbot-nested-20261025-072454`。
- 仅切换 `mytools-qq-connector-service.service`；全局 `releases/current` 保持原版本，其他服务代码及共享环境文件不变。
- 单服务覆盖文件：`/etc/systemd/system/mytools-qq-connector-service.service.d/99-qqbot-nested-20261025.conf`。
- 新版本自带 Python 3.12.13 和独立 venv，aiohttp 3.14.3 与上线前版本一致；没有替换系统 Python 或其他服务的环境。
- 服务覆盖使用 `UnsetEnvironment=ONEBOT_CONNECTOR_INTERNAL_TOKEN`，已确认新进程不再加载该凭据。嵌套读取、引用恢复与验证通知均走官方 QQ 通道。未停止或修改其他 OneBot 服务。
- 引用缓存位于 `/opt/yuyutian/mytools/runtime/qq/message-references.sqlite3`，服务账户可访问，文件权限为 `0600`。原入站、出站 WAL 与 checkpoint 继续沿用。

## 核验

- 上线前确认线上 7 个原模块与本地改动前基线摘要一致。
- 发布源文件逐项 SHA-256 清单校验通过；运行包 SHA-256 与 [uv 0.11.14 官方下载清单](https://github.com/astral-sh/uv/blob/0.11.14/crates/uv-python/download-metadata.json) 匹配；依赖离线安装前校验摘要。
- 在服务器以 `mytools` 服务账户，使用实际部署的 Python 3.12.13 与安装后的包运行完整测试：**179 passed**。
- 发布树为 `root:mytools`，目录及可执行文件 `0750`，普通文件 `0640`；服务账户只读代码。
- 切换后核对实际进程解释器、工作目录和安装包源码摘要，均指向新版本。
- QQ Gateway 重连成功，`/health` 为 `UP`；入站 pending、processing、dead 均为 0，出站 dead 为 0。
- 通过 Messaging 的官方 QQ 会话回复接口通知授权用户发送样例。HTTP 返回 `202 ACCEPTED`，对应 QQ 出站 WAL 已完成，DONE 总数由 348 增至 349。

## 真实消息验收

用户随后要求自行构造样例。2026-10-25 使用已登录的 macOS QQ 客户端，通过正常聊天、多选合并转发、引用操作向官方 DownloadBot 发送测试消息；从运行中连接器使用官方 `/users/@me` 核实机器人名称一致。以下时间均为北京时间，测试走真实 QQ Gateway、Messaging、下载及标签回执，没有注入合成入站事件，也没有调用 OneBot。

| 时间 | 样本 | 官方事件与处理结果 |
| --- | --- | --- |
| 19:25 | 带 `QQBOT-NESTED-20261025-A` 标记的测试文字 | 普通消息入站，正文保留 |
| 19:27 | 自行生成的 480 × 180 三色色块 PNG | 图片下载完成，回执识别蓝、绿、红三个色块 |
| 19:32:46 | 文字与 PNG 合并为一层聊天记录 | `message_type=102`，Messaging 保留文字及 1 个 IMAGE 分段，下载回执完成 |
| 19:34:18 | 将上一条聊天记录再次合并，形成两层嵌套 | `message_type=102`，最内层 A 标记仍在，1 个 IMAGE 分段，下载回执完成 |
| 19:36:14 | 使用 QQ 的引用功能回复两层嵌套记录，添加 Q 标记 | `message_type=103`，包含 1 个 `msg_elements` 及 `msg_idx/ref_msg_idx`；Q 标记、引用边界、最内层 A 标记与图片全部进入 Messaging，下载回执完成 |

真实合并事件没有 `msg_elements` 数组，而是已经展开的 `content`，包含 `=== 消息 1 ===`、`[关联消息]`、缩进的 `--- 第1条 ---` 及 `[附件1] ... URL:...`。解析器保留这些平台层级文本，从附件摘要提取图片 URL；因此不能将合并事件描述成必然下发结构化消息树。引用事件则确实下发 `msg_elements`，解析后产生 `path=0.0 relation=quote` 标记。这次引用使用内联正文，不能作为仅索引缓存恢复的线上证明。

一层、两层和引用三条主验收消息分别产生 1 个图片分段，没有 `qq-incomplete`。平台附件摘要给出 `3.3KB`，解析后的声明大小为 3379 字节，这是摘要精度，不是实际下载文件字节数；客户端完成回执显示 3.32 KB。测试图片见 [PNG](assets/qqbot-nested-20261025.png)，原文件为 3396 字节，SHA-256 为 `9466b30460b10ef08d36763ee677ae154c907943c8f069e980fdb9ebb61f10b5`。

初次 19:31 的合并操作额外带入同一机器人会话里的一条既有视频，随后重新制作并核对为仅含测试文字和图片的两条记录；该初次消息不作为上表的纯测试样本，未转发给其他联系人。B 标记是独立文字消息，不在最终两层记录中。纯文字没有可下载文件，现有下载自动化返回 `FAILED / 0 files`；这与嵌套文字读取成功是两个不同结果，本次未调整下载任务对纯文字的处理策略。

复核时服务为 `UP`、`gatewayReady=true`，入站 pending/processing/dead 和出站 dead 均为 0。真实验收确认上述样本可以读取，不推导任意客户端、任意层数或平台未下发的历史内容都能恢复。三层、重复图片和未知历史引用仍由既有自动化测试覆盖，尚未做本次真实客户端验收。

## 回退

原版本和全局入口已保留。必要时仅移除上述本次新增的 systemd 覆盖文件，执行 `systemctl daemon-reload` 后重启 `mytools-qq-connector-service.service`，复核 `/health` 和 Gateway。回退不删除运行时 WAL、checkpoint 或引用缓存，不恢复或覆盖共享环境文件。

服务端审计：`/opt/yuyutian/mytools/runtime/repairs/qqbot-nested-20261025/`，保存切换前后健康状态、通知发送结果及最终检查；仓库不保存凭据、真实消息正文或用户标识。
