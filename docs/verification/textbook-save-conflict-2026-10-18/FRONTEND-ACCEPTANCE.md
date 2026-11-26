# 教材保存冲突：前端真实浏览器验收

日期：2026-10-18。结果：3 个场景全部通过。

## 范围

- 使用实际 Chrome、实际 Vite 页面及其表单、状态机和 API 客户端。
- 本地地址为 `http://127.0.0.1:4187`；全部管理 API 使用 Playwright 路由模拟响应。没有登录生产账号，没有写入生产数据。
- 此报告验证前端请求顺序、修订号、重试和输入保护；后端三路合并算法与生产数据的验证由同目录的后端证据另行覆盖。

## 操作与结果

每次从草稿修订 4、旧基线 `book-1@1.0.3` 开始，实际在页面把 `苹果 / apple` 修改为 `青苹果 / green apple`，并新增 `早上好 / good morning`，然后点击“保存发布”。

| 场景 | 实际请求 | 验收结果 |
| --- | --- | --- |
| 基线过期且可安全合并 | 保存一次得到修订 5 → 发布收到 `baselineMismatch` → 对齐一次返回修订 6、基线 `1.0.5` → 使用新修订和基线发布 → 读取已发布草稿 | 成功提示出现；修改、新增行及新版的 `运动场 / sports ground` 均保留；只保存一次、发布请求两次、对齐一次 |
| 对齐结果未知后重试 | 首次对齐模拟服务端完成但网络响应丢失 → 输入和新增行仍显示 → 再点“保存发布” | 第二次对齐沿用完全相同的 `Idempotency-Key` 和 `If-Match: draft-5`；不重复保存；安全对齐返回后使用修订 6 发布成功 |
| 同一字段真实冲突 | 保存成功 → 发布收到基线冲突 → 对齐返回 `rebaseConflict` | 显示具体的 `“green apple”的中文释义`；两行输入完整保留；不自动重新读取；无第二次发布；“保存发布”禁用以阻止覆盖 |

三个场景均没有“其他人更新”的误导提示。单侧开始填写第二行后，备用第三行立即出现。浏览器未捕获到 JavaScript `pageerror`。模拟 HTTP 409 与网络中断产生的请求错误是测试输入，不是未解释的运行错误。

## 证据

- 请求轨迹与逐项断言：[acceptance-results.json](../../../output/playwright/textbook-save-conflict-2026-10-18/acceptance-results.json)
- 浏览器操作脚本：[acceptance.js](../../../output/playwright/textbook-save-conflict-2026-10-18/acceptance.js)
- 成功及新版内容保留：[rebase-success.png](../../../output/playwright/textbook-save-conflict-2026-10-18/rebase-success.png)
- 网络中断时输入保留：[rebase-network-retry-pending.png](../../../output/playwright/textbook-save-conflict-2026-10-18/rebase-network-retry-pending.png)
- 同键重试后成功：[rebase-network-retry.png](../../../output/playwright/textbook-save-conflict-2026-10-18/rebase-network-retry.png)
- 真正冲突的具体提示和保留输入：[rebase-conflict.png](../../../output/playwright/textbook-save-conflict-2026-10-18/rebase-conflict.png)

已检查成功及冲突截图。此次验证不宣称进行了生产发布操作，也不替代后端对真实教材快照的无损合并校验。

验收完成后已关闭此次 Chrome 会话并停止本地 Vite，4187 端口不再监听。
