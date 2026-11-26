# 验收文件清理记录

2026-09-26 按用户要求，清理本轮下载工具验收素材。

- 服务器：删除 22 个归档、上传源及测试素材文件；删除 25 个已结束验收任务的工作目录，共 1869491245 字节。归档文件删除前校验 SHA-256，删除后逐项确认不存在。详细清单见 cleanup-server.json。
- 云盘：删除 Sintel 验收操作独占目录下的 11 个文件（129302391 字节）；再次列举返回目录不存在。诊断操作的隔离目录也已确认不存在。见 cleanup-cloud.json。
- 虚拟机：删除 Download 中的 download-chat-fixture.png、download-chat-fixture.mp4、app-512MiB.bin，以及 /data/local/tmp/download-chat-fixture.mp4；逐项检查不存在。图库中本轮导入的视频已删除，同名查询为 0 条。
- 本机：/tmp/download-chat-fixture.png、/tmp/download-chat-fixture.mp4、/tmp/app-512MiB.bin 均已确认不存在。
- 保留验收报告、截图、日志、任务及资产审计元数据。历史记录中的测试产物链接在清理后不再可下载。

本轮未清理既有 PDF 验收素材或其他用户文件。
