# Outlook RSS 客户端复发排查

日期：2026-11-04，Asia/Shanghai。

当前状态：原订阅已恢复，用户已确认发送/接收正常。以下保留分阶段排查记录。已修复可复现的 RSS 客户端误拦截。第一轮只调整订阅 XML 后，用户复测仍报 `0x80004005`；第二轮补齐文章和图片的兼容规则后，用户再次确认“还是失败，报错一样”，**当时整体故障尚未解决**。不能用服务端探测成功代替 Outlook 实机验收。后续[对照诊断](2026-11-04-outlook-rss-isolation.md)中用户确认两条新源均能添加并显示内容。原地址现已采用相同的完整响应策略，见[调整记录](2026-11-04-outlook-rss-plain-response.md)；用户最终确认“原订阅恢复正常”。

## 新闻发布正常

当天个人任务于 07:30 启动、07:47:38 成功结束，`ExecMainStatus=0`。公开快照为 `20261103T234732-5e99cf231c6c`。`outlook.xml` 为 232,147 字节，100 条独立新闻、83 条含图片；GUID 无重复，XML 可完整解析，无非法控制字符。

SHA-256：`e51b1ae0f573dc0aaf3dd14e1f45eaab7eea1e3195110892fdfb62936b957e3d`。

## 确认并修正的服务配置问题

普通 Outlook 标识可以取得 XML，而 `Windows-RSS-Platform/2.0 (MSIE 11.0; Windows NT 10.0)` 标识连续两次收到 HTTP 403，正文为 `error code: 1010`。执行服务器的同一探测也收到 403。Cloudflare 安全事件中，Ray ID `a5507ad0c98f5212` 对应原订阅的 GET 请求，服务明确为“浏览器完整性检查”。这是探测请求的确定性故障，但尚不能证明它就是用户实际 Outlook 请求的唯一失败原因。

Cloudflare 的 [1010 说明](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1010/)及[浏览器完整性检查文档](https://developers.cloudflare.com/waf/tools/browser-integrity-check/)说明此类请求可因客户端签名被拦截，并支持按精确请求条件配置例外。

最初仅调整 XML 后，又验证其文章链接和图片使用同一 Windows RSS 标识也返回 403。对于 Outlook 下载链接文章的功能，这些请求仍会失败；微软的[RSS 设置说明](https://support.microsoft.com/en-us/outlook/change-settings-for-rss-feeds)明确该功能会下载链接页面作为 HTML 附件。尚未获得用户是否开启此选项的证据，不将这一发现直接视为其错误原因。

最终已部署自定义规则 `MyTools Outlook RSS browser compatibility`，ID：`1bc68ac204634b58a132381bf94a65c3`：

```text
(http.request.method in {"GET" "HEAD"} and http.request.uri.query eq "" and (
  http.request.full_uri eq "https://toolbox.yuyutian.top/news/rss/outlook.xml"
  or http.request.full_uri wildcard "https://toolbox.yuyutian.top/news/articles/*/r*.html"
  or http.request.full_uri wildcard "https://toolbox.yuyutian.top/news/assets/*.jpg"
  or http.request.full_uri wildcard "https://toolbox.yuyutian.top/news/assets/*.png"
  or http.request.full_uri wildcard "https://toolbox.yuyutian.top/news/assets/*.gif"
))
```

只跳过 Browser Integrity Check，匹配请求日志开启。没有选择跳过其他自定义规则、速率限制、托管规则、自动程序规则、区域锁定或用户代理阻止。仅原公开订阅 XML、公开文章修订页和新闻 JPG/PNG/GIF 图片的 HTTPS 读取匹配，不含查询参数和写入方法。源站 Nginx 仍按 64 位十六进制文章/图片标识等原白名单校验路径，不因此开放其他文件。原始图文、GUID、新闻运行包与源站文件保持原样。

另确认边缘缓存虽然尊重源站 300 秒，但客户端响应原为 `Cache-Control: public, max-age=14400`。在既有精确 URL 缓存规则 `b58509c6a3074f8b81f24dcf217b6056` 中增加 Browser TTL“接受源服务器 TTL”，实测响应已变为 `max-age=300`。这项调整避免客户端长期缓存；不将其描述为 `0x80004005` 的已证实原因。已下载到客户端的旧缓存不能通过边缘设置强制清除。

## 验证与剩余问题

调整后，本机 Windows RSS 非压缩请求返回 200，完整下载耗时 9.76 秒；压缩请求返回 200，解压后与源文件逐字节一致。该客户端条件请求返回 304。HEAD 返回 200，缓存有效期为 300 秒。

补齐资源规则后，同一篇文章从 403 恢复为 200，耗时 3.23 秒；同一张新闻原图从 403 恢复为 200，50,054 字节，耗时 3.01 秒。二者使用 Windows RSS 标识完成实际 GET 下载，而非只查看浏览器页面。

11:51 的最终服务器公网验收八项全部通过：两种客户端的完整下载、gzip、条件更新，以及文章和图片 GET；每项耗时 0.61—1.60 秒。证据为 `server-acceptance-final.json`。模拟未发布文章路径仍返回 404，源站白名单继续有效。此结果是服务侧验收，不替代用户 Outlook 的最终反馈。

执行服务器经公网的独立验收六项全部通过：

| 客户端 | 请求 | HTTP | 耗时 |
| --- | --- | --- | --- |
| Outlook 标识 | 未压缩 | 200，完整 100 条 | 1.54 秒 |
| Outlook 标识 | gzip | 200，完整 100 条 | 1.32 秒 |
| Outlook 标识 | 条件更新 | 304 | 0.92 秒 |
| Windows RSS 标识 | 未压缩 | 200，完整 100 条 | 1.11 秒 |
| Windows RSS 标识 | gzip | 200，完整 100 条 | 0.87 秒 |
| Windows RSS 标识 | 条件更新 | 304 | 0.64 秒 |

当前本机整组验收仍记录到一次 TLS 建连超时，以及一次已返回 HTTP 200 但压缩流在 30 秒内未完整下载。结果正确标为失败，未自动重试掩盖失败。此处线路波动与 BIC 误拦截是两个不同观测，不能归并成“全部修复”。用户在规则调整后明确回复“仍报 0x80004005”，因此尚需 Windows 客户端证据。源站日志可能不含边缘缓存命中或边缘拦截，Cloudflare 采样日志也不能证明请求绝对未到达。

本地证据：`out/rss-outlook-recheck-20261104/`。其中 `before.json` 保留 403 证据，`acceptance.json` 保留本机失败结果，`server-acceptance.json` 保留服务器六项通过结果，`final-head.headers` 保留五分钟缓存响应，`compatibility-rule.png` 保留规则上线状态。

## 可复用验收

新增 `service/news-digest-service/scripts/check_public_rss.py`，仅执行无凭据的公开读取请求；分别检查两种客户端的原文、gzip 及本轮成功下载后的条件更新，并抽查同站一篇文章和一张配图。校验 HTTP 状态、RSS MIME、完整 XML、条目数量、GUID、详情字段、快照哈希及资源 MIME；不会探测 XML 中的其他域名。任何一个请求失败即返回非零退出码，不自动重试。

```bash
python3 service/news-digest-service/scripts/check_public_rss.py
```

发布验收时可加 `--expected-sha256` 与源站文件比较。六项回归测试通过，覆盖 403 拦截页、200 HTML 页面、截断 XML、孤立 304、不同快照、普通客户端成功但 Windows RSS 被拦截、文章单独被拦截及拒绝探测外部域名的情况。此脚本也已复制到执行服务器的 `~/workspace/news-personal/validation/rss-client-20261104/`，不更改已发布的不可变运行包，不新增定时任务。

## 回退

在 Cloudflare 自定义安全规则中禁用 `1bc68ac204634b58a132381bf94a65c3` 可撤销本次 BIC 兼容调整。缓存规则中移除本次新增的 Browser TTL 项可恢复原客户端缓存策略。无需修改 DNS、TLS、源站部署或新闻快照。
