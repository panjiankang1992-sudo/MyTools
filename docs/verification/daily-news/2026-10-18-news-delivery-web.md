# 第九批：网页接收配置与投递状态

日期：2026-10-18。持续目标保持 **ACTIVE**。本批完成网页工程和本地验收，未部署或发送真实消息。

## 本批结果

- `/news` 页头新增消息投递入口，可滚动并聚焦投递面板。复用已有登录、Gateway 及内部 owner 隔离；浏览器只提交 destinationId、mode、enabled 和 expectedRevision，不接收邮件地址、Webhook、渠道密钥或 owner。
- 读取当前账号的已验证目标，最多选择五个；完整/精简格式、停用与移除分开控制。QQ 不进入可选目标，并明确官方主动推送限制。新目标及形式变更影响下一次每日运行；停用/移除同时请求停止未完成投递。
- 后台刷新不覆盖接收草稿。NEWS_022 冲突保留草稿并禁止继续覆盖；重新载入需确认会替换未保存内容。服务投递关闭时仍展示配置与历史，允许移除目标和请求停止。
- 最近五十个目标批次按片段展示状态、发送代数、过期时间和错误码。PROVIDER_ACCEPTED 仅显示“提供方已接收”，不声称已送达收件箱或已读。
- FAILED 可显式重试；UNKNOWN 须明确接受重复发送风险。响应丢失时，当前页面保留原 expectedGeneration 和确认标记，恢复请求不会生成新的重试代数。查询确认新代或持久化意图后才清除恢复状态。
- REJECTED 使用原片段恢复；取消展示服务端实际状态与停止请求，不能将 SENDING 伪装成 CANCELLED。后台轮询的过时响应不会回退已经完成的用户操作，卸载后丢弃迟到结果。

## 验证

| 检查 | 结果 |
| --- | --- |
| 原新闻页面状态测试 | 13 项通过 |
| 新增投递状态测试 | 15 项通过 |
| 原新闻页面浏览器回归 | 19 项断言通过 |
| 新投递交互浏览器验收 | 19 项断言通过 |
| 导航聚焦、390px / 360px 布局、匿名登录跳转 | 4 项断言通过 |
| 全项目 vue-tsc、Vite 生产构建 | 通过 |
| 相关 TypeScript/Vue 的 oxlint；Vue ESLint | 零错误、零告警 |

浏览器使用独立回环测试服务。409、502（模拟响应丢失）、503 均为有意注入，已验证错误保留和恢复；无提供方网络请求。Node 26 执行 tsx 时显示依赖工具的 DEP0205 弃用提示，不是应用 Lint/编译告警。

验收脚本：

- `webapp/tests/news/model.test.ts`、`delivery-model.test.ts`。
- `webapp/tests/news/browser-check.js`、`delivery-browser-check.js`、`delivery-layout-check.js`。
- 启动与重置步骤见 `webapp/tests/news/README.md`。截图来自夹具，不能作为真实内容/送达证据。

截图：[桌面](../../../output/playwright/daily-news/delivery-desktop.png)、[手机](../../../output/playwright/daily-news/delivery-mobile.png)。机器摘要：[news-delivery-web-test-summary.json](news-delivery-web-test-summary.json)。

## 保留边界

1. 目的地所有权验证及绑定仍走 Messaging 管理接口；本页不提供未经验证的任意地址输入，也未实现邮箱验证码流程。
2. 请求恢复状态只保存在当前页面内存；重新打开页面先读取服务端最新代数、意图和投递结果，不持久化跨账号状态。未知结果仍须人工核对或明确接受重复风险。
3. 本批不重复计入第八批的 282 项后端通过结果；未运行真实 MySQL、执行节点、SMTP、飞书或 QQ 送达验收。
4. 长期开放采集/投递之前，继续实现保留清理、运维指标、部署默认关闭配置和恢复手册；真实节点、授权目标以及七日影子观察仍待验收。
