# 下载工具补充验收

## 通过

- 虚拟机系统图库视频：通过文件预览的系统“分享 → 保存至图库”导入合成 MP4，再从 MyTools“＋ → 图片 / 视频”选择、确认、上传。自动跳过外链下载，完成标签和归档；APP 显示完成回复。证据 extended-gallery-selected.png、extended-gallery-uploaded.png。
- 恰好 512 MiB 文件：经正式鉴权网关分块上传 536870912 字节、提交、调度、入库、打标签和归档均成功；归档 contentSha256 与提交时逐块计算摘要一致。
- 上限边界：512 MiB + 1 字节被拒绝。非法磁链被拒绝。
- ZIP：合成 ZIP 自动解包，文本子文件归档并生成标签。
- 磁链：使用 [WebTorrent 官方文档中的 Sintel 演示种子](https://github.com/webtorrent/webtorrent/blob/master/docs/get-started.md)。修复参数后重新发送，自动完成 11 个文件，包括 129241752 字节视频；所有结果 TAGGED。APP 显示完成结果，见 extended-final-replies.png。

## 未通过及实际限制

- 512 MiB APP 公网上传：虚拟机选择真实 512 MiB 文件发送，服务端在 125 秒收到 3 MiB，约 24.6 KiB/s。预计数小时，完整上传测试主动停止，不能标记通过。后台已取消该未完成上传并确认临时文件删除。该项与从服务器本机通过网关完成的 512 MiB 测试分开记录。没有定位到公网低速的根因。
- 磁链首次自动执行遇到 Connector HTTP 409 / REMOTE_STATE_CONFLICT；相同诊断操作手动推进后可提交。随后通过 APP 正式接口创建全新请求，自动下载、标签及归档成功。诊断操作已取消。首次失败说明仍需关注 Connector 偶发冲突，本轮未修改其重试策略。
- 未穷举所有网站、私密链接、登录/Cookie 规则及磁链资源。外站既有规则不能据此宣称全部支持。

## 发现并修复

APP 磁链多传了 resourceUsername，而正式 download_pikpak_magnet 参数 schema 禁止额外字段，导致入口返回 503。已移除该字段，保持现有磁链契约；未绑定任务的失败链接不再误显示“文件尚未上传完成”。增加严格参数集合回归测试。

后端已增量发布到 download-chat-20260926-v3，网关仍为 v1。没有修改既有任务定义和 Connector 配置。Python 59 tests + 13 subtests passed。APP 未修改，继续使用已经安装的 PDF 按钮修复版本。

清理过程中只读 SQLite 游标与取消更新发生锁冲突，已关闭查询并重启 ingestion 释放事务，随后取消及历史查询恢复正常。临时文件清理和最终健康检查另有记录，不将该故障隐去。

结果见 extended-acceptance.json、extended-tests.log。只使用合成文件与官方公开示例。

验收文件已按用户要求清理，范围及复查结果见 [CLEANUP.md](CLEANUP.md)。报告、截图及审计记录保留。
