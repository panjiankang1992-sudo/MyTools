# HarmonyOS 模拟器验收

2026-09-26：已启动 CopilotPerf0912（HarmonyOS 6.1.1 / API 24），安装当前签名 HAP，使用专用验收账户连接真实生产后端。核心分割和文本生成交互通过，模拟器保持运行。这里的结论不包含物理真机或全部八项操作的 UI 覆盖。

## 通过项目

| 项目 | 证据 |
|---|---|
| 工具页入口、独立工作台布局 | 47-tools、48-home |
| 系统文件选择器上传合成双页 PDF | 32-browse 至 36-upload-ready |
| 拖动切线至 63%，恢复 50% | 25-drag-cut、37-submit |
| 垂直中分输出四页，逐页显示 9/10/11/12 | 50-restored-preview、51-page2、52-page3、53-page4 |
| 原生 PDFKit 阅读 | 42-reader |
| 系统保存、取回文件、检查四页版面 | 44-save-picker、45-saved、saved-result.pdf、saved-page-1 至 4 |
| 重装并重启后恢复历史计划 | 49-history、50-restored-preview |
| 中文文本输入、生成、阅读 | 57-text-entered、59-chinese-ready、60-chinese-result、61-chinese-reader |

以上名称对应同目录 PNG 截图和 JSON 布局。最终签名包、结果文件摘要及检查项目见 [机器记录](./acceptance.json)。保存结果为 1412 字节，四页均为 421×595 pt，逐页渲染核对通过。

## 验收发现并修复

1. PDF 工作台原本嵌套在主 Tabs 和 Scroll 内，造成滚动与底栏干扰。调整为独立页面，并使短内容顶部对齐。
2. ArkUI Image 使用裸沙箱路径时缩略图空白。改为 `file://` URI，四页预览已逐页复验。
3. 过期记录原本统一显示处理失败，现明确显示已过期。
4. MySQL 会话仍为 UTC+8，而 JDBC 以 UTC 写入 TIMESTAMP，导致计划在下一轮清理时提前过期。配置同时指定 `connectionTimeZone=UTC` 与 `forceConnectionTimeZoneToSession=true`，PDF 服务已发布到 v4。依据 [MySQL Connector/J 官方文档](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)，仅设置连接时区不会改变会话时区。

迁移校正 62 条已有截止时间，不复活 EXPIRED 记录；私有回滚数据保留在远端配置目录。真实模拟器计划创建约 65 秒后成功提交，超过 60 秒清理周期；后续只读数据库核对计划年龄 616 秒仍 READY，任务年龄 552 秒仍 SUCCEEDED，剩余有效期 85849 秒。新增清理回归测试后 PDF 服务 5 项测试通过。最新 HAP 构建成功，无 ArkTS 警告，`git diff --check` 通过。

## 边界

- 原《第一单元》未上传生产，本次采用无私人内容的合成双页 PDF。
- 其余六项操作已由此前生产 API 验收覆盖，本轮未逐项完成模拟器 UI 操作。
- 物理真机、长期后台恢复、弱网及大文件压力测试未覆盖。
- 模拟器当前登录专用验收账户；原有会话到期后未尝试读取或恢复个人账户密码。
