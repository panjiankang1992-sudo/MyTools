# 原型验证记录

验证日期：2026-11-22。范围：设计原型与停止每日自动任务，不包含新 App 或后端功能的生产验收。

## 交互与布局

本地预览通过 `python3 -m http.server 8769 --bind 127.0.0.1 --directory docs/design/2026-11-22-weekly-news` 启动。应用内浏览器控制连接两次超时后，使用 Playwright 浏览器完成实际渲染与交互检查。

| 检查 | 观察结果 |
| --- | --- |
| JavaScript 语法 | `node --check prototype.mjs` 通过。 |
| 工具入口 | 点击「每日新闻 最近七天要点」进入首次获取页。 |
| 时间说明 | 明确展示最近七天、北京时间、11/15 14:30 至 11/22 14:30 的演示范围。 |
| 后台交互演示 | 点击获取后进入运行态；返回工具页可见运行入口；演示任务完成后重新进入可见结果，未重新进入空态。 |
| 分类与限额 | 全部分类弹层包含十类；技术展开后有 20 张卡片，最后排名为 20。 |
| 数量不足 | 本地显示 16 条及说明，展开后有 16 张卡片，没有补齐成 20。 |
| 新闻详情 | 卡片进入标题含分类/日期的详情页，存在原图位置、摘要、要点和合并来源。 |
| 部分失败 | 两分类失败时其他结果可读；重试显示原八类已完成、军事整理中、娱乐等待，随后恢复十类完成。 |
| 历史入口 | 展示多次生成记录，包含各自范围、条数和部分完成状态。 |
| 窄屏 | 320 px 视口下文档宽度为 320 px，无横向溢出；主按钮完整可见，正文可滚动。 |
| 桌面评审 | 1440 × 1080 下导航、手机和说明三栏正常；手机模拟宽度 390 px。 |
| 浏览器日志 | 初次缺 favicon 的 404 已修复；重新载入后 console 为 0 errors / 0 warnings。 |

截图：

- [工具入口](../../../../output/playwright/weekly-news-tools.png)
- [首次获取](../../../../output/playwright/weekly-news-empty.png)
- [后台生成](../../../../output/playwright/weekly-news-running.png)
- [分类结果](../../../../output/playwright/weekly-news-results.png)
- [新闻详情](../../../../output/playwright/weekly-news-detail.png)
- [部分完成](../../../../output/playwright/weekly-news-partial.png)
- [历史记录](../../../../output/playwright/weekly-news-history.png)
- [320 px 窄屏](../../../../output/playwright/weekly-news-320.png)

## 原型边界

- 新闻标题、数字和时间固定为演示数据；图片是明确标注的版式占位，不是真实新闻图片。
- 模拟任务使用浏览器计时器，只有未来真实后端才能保证关闭 App/设备后继续执行。本次仅验证页面切换流程。
- 不发起新闻采集、模型分析、邮件或公共 RSS 发布。
- 历史卡片演示进入报告的路径，旧日期卡片复用同一演示报告，不作为真实持久化验证。
- 原型展示浅色模式；深色模式、大字号、认证隔离、重启恢复及服务端幂等属于实现后的验收项。
- HTML 可独立打开，样式和普通脚本从同目录加载；无外部 CDN 依赖。

## 生产停用证据

已连接配置中的 SSH 主机并执行停用操作。复核摘要：

```text
mytools-personal-news.timer
  ActiveState=inactive
  UnitFileState=disabled
  NextElapseUSecRealtime=
mytools-personal-news.service
  ActiveState=inactive
```

既有网页、RSS、DSH 服务与历史文件保留。后续发布新手动功能不得重新启用每日 timer。完整操作背景见[运行说明](../../../operations/news-rss.md)。
