# 下载完成回复显示文件大小

APP 下载聊天与 QQbot 完成回执均在每个文件名下增加大小行，读取下载结果的 `sizeBytes`，按 1024 换算 B/KB/MB/GB/TB。缺失或无效的大小不会误显示为零；真实零字节显示 0 B。原有标签、归档信息与分页机制保留。

验证：
- DownloadIngestionClientTest 9 项、CompletionOutboxRelayTest 38 项通过，包含大小解析与 QQ 回执精确文本验证。
- APP HAP 构建通过并安装模拟器 127.0.0.1:5555。
- 使用已有下载历史验收，完成回复实际显示 1.07 MB、1.09 MB、764.76 KB、1.32 MB、1.15 MB，见 simulator.json。
- QQ 回执服务部署版本 download-size-20260927-v1，实际运行 jar 路径核对通过，健康状态 UP。
- 没有发送测试 QQ 消息、没有创建测试下载；临时脚本与控件树清理。真机未连接，未安装真机。
