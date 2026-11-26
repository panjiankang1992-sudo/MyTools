# News page acceptance

所有身份和新闻均为离线测试夹具。服务只监听回环地址，不能用于生产数据源或真实登录。这里验证前端交互；Gateway 的 owner 隔离仍由 Java 回归验证。

## State tests

From `webapp`:

```bash
pnpm exec tsx --test tests/news/model.test.ts
pnpm typecheck
pnpm exec oxlint src/service/api/news.ts src/service/api/news.types.ts src/locales/news.ts src/views/news tests/news/model.test.ts --deny-warnings
pnpm exec eslint src/views/news/index.vue src/views/news/modules/news-reader.vue --max-warnings 0
pnpm build
```

## Browser fixture

分别启动以下两个本地进程：

```bash
node webapp/tests/news/fixture-server.mjs
```

```bash
cd webapp
VITE_ROUTE_HOME=home VITE_SERVICE_BASE_URL=/ VITE_NEWS_GATEWAY_URL=http://127.0.0.1:24142 VITE_API_BASE_URL=http://127.0.0.1:24142 pnpm exec vite --mode test --host 127.0.0.1 --port 24141 --strictPort --open false
```

使用 Playwright CLI 独立 session 打开浏览器。在该测试页面的初始化脚本中把 `token` 设为公开的夹具值 `news-ui-fixture`，再访问 `http://127.0.0.1:24141/news`。这是模拟登录，不能替代真实认证验收。

`browser-check.js` 是传给 CLI `run-code` 的完整函数。读取文件并以单个参数传入，避免经过 shell 展开；它操作实际页面并保存 19 项断言结果到 `window.__newsAcceptance`。用 CLI `eval` 读取该变量获取结果。每次完整重跑前重启夹具服务，以恢复初始订阅版本、首次预览中断和待取消任务。

浏览器另行验证：390px/360px 宽度下 `document.documentElement.scrollWidth <= innerWidth`，序号不换行；新建无 storage 的浏览器 context，直接访问 `/news` 应跳转 `/login`。截图保存到仓库 `output/playwright/daily-news/`。

页面请求经过 Vite 同源代理；夹具服务模拟响应丢失、409 版本冲突和 503 历史查询失败。控制端点 `/__news_test` 只存在于此独立夹具进程，不打包进产品。

## Delivery panel acceptance (batch 9)

状态测试同时运行 `pnpm exec tsx --test tests/news/model.test.ts tests/news/delivery-model.test.ts`，共 28 项。投递 DTO 和状态模型纳入 oxlint，新增 Vue 组件纳入 ESLint；不要把 TypeScript 文件交给只配置 Vue 的 ESLint，否则工具会报告未匹配配置的提示。

同一夹具服务加载 `delivery-fixture.mjs`，模拟目标版本冲突、重试响应丢失、UNKNOWN、REJECTED 与取消期间仍为 SENDING。该数据不会调用 Messaging、SMTP 或飞书。

CLI `run-code` 执行 `delivery-browser-check.js`，完成后 `eval window.__deliveryAcceptance` 应返回 19 项；原 `browser-check.js` 仍应返回 19 项。每次完整重跑前重启夹具进程并刷新页面，以重置版本和首次丢失响应状态。文件参数使用子进程参数数组传入，避免 shell 展开。

`delivery-layout-check.js` 检查页头入口聚焦、390/360px 无水平溢出、匿名访问登录跳转，共 4 项，结果在 `window.__deliveryLayout`。响应式布局切换需要等待应用侧栏动画完成；超时仍视为失败。执行前确保 `output/playwright/daily-news/` 存在，截图写入 delivery-desktop.png 和 delivery-mobile.png。

所有确认框操作等退出动画完成后再开始下一步，避免旧弹层与新弹层暂时共存造成定位歧义。不要在 HMR 切换页面组件期间运行整套验收，代码变更后先完整刷新。


## Citation availability (batch 11)

状态测试加入 `tests/news/evidence-model.test.ts`：`pnpm exec tsx --test tests/news/model.test.ts tests/news/delivery-model.test.ts tests/news/evidence-model.test.ts`，共 33 项。新增类型、本地化、状态模型纳入 Oxlint；阅读组件纳入 ESLint，并运行全项目 typecheck/build。

重启夹具、刷新页面后，先运行 `browser-check.js`（19 项），再运行 `evidence-browser-check.js`（10 项）。结果分别为 `window.__newsAcceptance` 和 `window.__evidenceAcceptance`。后者验证三种本地保留状态、缺失时无虚构期限、失败后保留正文而清除旧可用标记、恢复及 390/360px 布局。截图为 `evidence-desktop.png` 和 `evidence-mobile.png`。

`failEvidence` 只在夹具控制接口存在；不打包进入产品。全部状态来自明确的离线数据，不是外部原站可用性或报道真实性的验收。
