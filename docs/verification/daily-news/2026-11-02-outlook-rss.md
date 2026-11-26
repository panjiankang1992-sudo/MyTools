# Outlook RSS 兼容入口验证

日期：2026-11-02。个人运行包 **1.4.2** 已上线，用户在 Windows 经典版 Outlook 添加新地址后明确反馈「已添加成功」。

## 故障证据与判断边界

原 `daily.xml` 是合法、完整的 RSS 2.0，当前 507 条、1,653,552 字节。源站返回 200，无登录要求。公网五种 User-Agent 并发测试在 35 秒限制内均未下载完整，出现 XML 截断；后续单次压缩下载成功，耗时 48.04 秒、传输 399,824 字节。这证明当前链路存在传输耗时问题，但并发测试不能等同于用户 Outlook 的网络环境，也不能证明错误只有单一原因。

没有复现用户机器内部的解析过程，因此不声称 Outlook 有特定 100 条或 512 KiB 的硬限制。此次通过独立兼容入口同时减少下载量并采用基础 RSS 字段，用户验收成功；不据此反推原源 XML 无效。

## 上线内容

- 新地址：`https://toolbox.yuyutian.top/news/rss/outlook.xml`。
- 在最近一周文章中选择最新最多 100 条，文件上限 512 KiB；若超预算，仅移除最旧条目，每篇完整图文不裁剪。
- 使用 RSS 2.0 标准 `description` 承载完整 HTML，省去正文扩展及重复摘要；与原源使用相同 GUID、发布时间、文章链接和正文。
- 订阅入口注明窗口差异；四分类 OPML 不重复加入兼容源。一周完整 `daily.xml` 和原分类源均保持字节一致。
- Nginx 显式配置 RSS/OPML gzip 与 `Vary: Accept-Encoding`；不支持压缩的客户端仍返回完整 XML。
- 发布策略加入渲染版本，升级时即使冻结报告未改也会生成新入口；每日任务切换到不可变运行包 1.4.2。

## 验证结果

| 项目 | 结果 |
| --- | --- |
| Python 回归 | 58 项通过，包含完整详情一致、稳定身份、旧条目裁剪及渲染升级测试 |
| Java 21 模块编译 | `mvn compile` 成功，Java/JAR 无变更 |
| 兼容源 | 100 条，229,937 字节，78 条有配图 |
| 源站 gzip | 77,136 字节；解压后与未压缩响应逐字节相同 |
| 公网模拟 Outlook，HTTP/1.1 + gzip | 200，6.94 秒，100 条完整解析 |
| 公网模拟 Windows RSS，无压缩 | 200，17.75 秒，100 条完整解析 |
| 条件 GET | 304 |
| 私有 manifest 路径 | 404 |
| 原完整源与分类源 | 内容字节未变，正文/图片/GUID/日期未变 |
| W3C Feed Validator | valid RSS；仅提示可选 atom:self 元数据缺失，兼容入口有意只使用基础 RSS 元素 |
| 真实经典 Outlook | 用户反馈「已添加成功」 |
| 定时任务 | timer active + enabled，unit 引用 1.4.2 |

最初旁路尝试从冻结输入重新渲染时，图解字节差异导致旧文章修订发生变化，未部署该候选。最终从已发布 manifest 直接生成兼容 Feed 和入口页，复用所有公开正文/配图；原五个 Feed 对照完全一致。此次最终发布不重新采集、不调用 DSH、不发送邮件，也不修改阅读器订阅和已读状态。

## 证据与回退

- 本地：`out/rss-outlook-20261102/`，含 `http-probes.json`、`public-validation.json`、`server-validation.json`、`origin-validation.json`、`w3c.html` 及部署脚本。
- 执行主机：`~/workspace/news-personal/validation/rss-outlook-20261102/`，含 `public.before`、`daily.service.before`、`news-rss.conf.before`、校验清单。
- 发布：`20261102T023327-b3371f84d03a`；前版本：`20261101T234119-3370d5b41490`。
- 运行包 16 文件校验通过，只有 `scripts/publish_rss.py` 的内容变化；旧 1.4.1 包未修改。
- 回退时恢复旧 unit 并 daemon-reload、恢复 Nginx 片段并经 `nginx -t` 后 reload、原子切换 current 到 `public.before` 的完整站点副本。无需数据库或邮件修改。

参考：[微软 RSS 订阅说明](https://support.microsoft.com/en-us/outlook/subscribe-to-an-rss-feed)、[RSS 2.0 description](https://www.rssboard.org/rss-2-0-11)、[Nginx gzip](https://nginx.org/en/docs/http/ngx_http_gzip_module.html)。
