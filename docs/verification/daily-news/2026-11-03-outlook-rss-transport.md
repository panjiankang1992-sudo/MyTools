# Outlook RSS 更新失败排查

日期：2026-11-03，Asia/Shanghai。用户反馈 Windows 经典版 Outlook 已订阅的 `outlook.xml` 在自动更新或发送/接收时报 `0x80004005`，同机浏览器可看到 XML。此错误码是通用失败，不能单凭它认定 RSS 格式错误，参见[微软错误码说明](https://learn.microsoft.com/en-us/windows/win32/adsi/generic-com-error-codes)。

## 实测证据

当天新闻任务成功，公开快照为 `20261102T234826-1b58f2c6bb85`，即北京时间 2026-11-03 07:48 发布。兼容源含 100 篇完整详情、73 篇配图，216,713 字节；SHA-256 为 `6f8a5a05780b0604f1cc8c14314fb892f9e259e5ef1cb334b2f039fa89c60236`，XML 可完整解析。

| 路径 | 调整前结果 |
| --- | --- |
| 源站回环 | 200，完整下载约 0.009 秒 |
| 执行主机经公网访问 | 200，gzip 69,744 字节，约 1.76 秒，解压内容与源站一致 |
| 当前客户端，Outlook 标识、gzip | 200，但 45 秒超时，仅收到 55,986 个压缩字节，解压后的 XML 在中间截断 |
| 当前客户端，Windows RSS 标识、不压缩 | 200，但 45 秒超时，仅收到 24,014 字节，XML 截断 |

源站无 `limit_rate` 等限速指令。公网原响应为 `CF-Cache-Status: DYNAMIC`，该域名原先没有 Cache Rules，开发模式关闭。客户端另一次请求在 TLS 建连阶段超时，说明观测线路也有波动。以上证明存在可复现的传输问题，不能据此断言用户 Outlook 的错误只有这一个原因。

## 已实施的调整

通过已有 Cloudflare 管理会话部署 `MyTools public Outlook RSS cache`，规则 ID 为 `b58509c6a3074f8b81f24dcf217b6056`。只匹配：

```text
(http.request.full_uri eq "https://toolbox.yuyutian.top/news/rss/outlook.xml" and http.request.method in {"GET" "HEAD"})
```

设置为符合缓存条件；边缘 TTL 使用源站缓存头，没有缓存头时绕过。源站现有 `Cache-Control: public, max-age=300` 因此作为五分钟有效期。保留默认缓存键、压缩协商和客户端缓存行为。未改变新闻内容、文章数量、GUID、图片、定时任务或运行包，也未改变身份验证、WAF、DNS、TLS、其他 URL 的缓存规则。

[Cloudflare 官方说明](https://developers.cloudflare.com/cache/concepts/cache-responses/)指出 DYNAMIC 表示请求阶段被判定为不符合缓存条件；通过 Cache Rules 可使公开内容进入缓存。本次只为现有公开静态订阅添加缓存，不强制覆盖源站的私有或禁止缓存响应。

## 调整后验证

- Windows RSS 标识、不压缩：200，完整 216,713 字节，11.33 秒，首次 MISS。
- Outlook 标识、gzip：200，69,744 字节，2.69 秒，HIT、Age 51。
- 两份解压正文与源站逐字节一致，均完整解析 100 条图文。
- Outlook 标识的 If-None-Match 条件请求正确返回 304、零字节正文，约 1.75 秒。
- 2026-11-03，用户保留原订阅并重试“发送/接收所有文件夹”后，明确反馈“已恢复正常更新”。真实 Windows 经典版 Outlook 更新验收通过；保留当前缓存规则，无需删除或重新添加订阅。

证据：`out/rss-outlook-recheck-20261103/`，含响应头、成功及截断响应、`before.json`、`after.json` 和管理页截图 `cache-rule.png`。

回退：在 Cloudflare → yuyutian.top → 缓存 → Cache Rules 禁用上述单条规则即可。源站及公开文件没有修改，不需要重新部署新闻运行包。每日发布后边缘节点最多沿用旧内容五分钟；如需即时生效，应仅清除此 URL 的缓存，不清空全站。
