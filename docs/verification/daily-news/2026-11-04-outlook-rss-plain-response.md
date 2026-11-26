# 原 Outlook RSS 地址采用完整响应

日期：2026-11-04，Asia/Shanghai。

## 实机证据与判断边界

用户确认简版 `outlook-check.xml` 和完整图文 `outlook-full-check.xml` 均能在经典 Outlook 添加成功并显示文章。专用日志对应 14:09:03/06 的简版 200/669 字节，以及 14:09:23/26 的完整版 200/224886 字节，客户端为 Outlook `16.0.10417`。

完整诊断源通过 alias 指向原 `outlook.xml`，内容哈希一致，说明当前 XML 和图文内容可以在该客户端导入。诊断同时改变了 URL、订阅状态、缓存、压缩及条件响应，因此不能单独认定是 gzip 缺陷、304 缺陷或 Outlook 本地缓存损坏。

用户在原地址完成调整后执行“发送/接收所有文件夹”，明确反馈“原订阅恢复正常”。本次故障已通过实机更新验收，继续使用原 `outlook.xml`；保留当前兼容配置。已成功添加的完整版仍跟随每日发布，不自动删除订阅或 Outlook 数据文件。

## 部署变更

仅为原地址 `/news/rss/outlook.xml` 新增精确 Nginx location：

- 指向同一已发布 XML，MIME 为 `application/rss+xml; charset=utf-8`。
- `gzip off`、`etag off`、`if_modified_since off`。
- `Cache-Control: no-store, no-transform`，保留固定 Content-Length。
- 保留原有内容安全策略和 nosniff；只允许 GET/HEAD。
- 独立访问日志 `/var/log/nginx/mytools-news-rss-outlook.log`，后续排查仅输出时间、路径、状态、响应字节和客户端标识，不输出 IP 或凭据。

其他新闻源、图片、HTML 页面、每日任务和 RSS 数据均未修改。既有诊断 include 保留。未改 Cloudflare 控制台规则；其原有缓存规则尊重源站头，公网现已验证为 BYPASS。

依据：[Nginx 核心模块](https://nginx.org/en/docs/http/ngx_http_core_module.html#if_modified_since)、[Cloudflare Cache-Control](https://developers.cloudflare.com/cache/concepts/cache-control/)；`no-store` 禁止存储响应，`no-transform` 禁止边缘转换负载。这只是兼容配置，不代表已证明此前错误的唯一根因。

## 验收

首次部署验证在平滑 reload 返回后立即访问，仍遇到旧 worker 的五分钟缓存响应，安装器自动恢复原配置。补充至多五秒的就绪检查后重新部署：第一轮仍为旧头，第二轮已为新头；随后完整内容验收通过。没有忽略失败或将旧响应当作上线成功。

源站五项验证通过：普通 GET、请求 gzip 的 GET、携带旧真实 ETag 的 GET、单独携带未来 If-Modified-Since 的 GET、HEAD。所有请求均为 200，前四项均为 224886 字节，SHA-256 为 `e680687c58ca858e10a93631f337bb8f410d2bd2071f1d1dda8d90ecc90c7f99`，无 ETag、Content-Encoding 或 Transfer-Encoding。两个诊断源、daily 和订阅页仍可用。

更新公网校验器 `check_public_rss.py --plain-response`，分别模拟已观测的 Outlook 客户端和 Windows RSS 平台，测试原文、请求压缩、旧 ETag 和未来日期条件，并抽查同站文章及图片。ETag 与日期分开测试，避免验证器优先级掩盖问题。

- 校验器八项单元测试通过。
- 执行主机经公网的十项检查均通过。八次 XML 均为 200、固定 224886 字节、相同哈希、无压缩，Cache-Control 正确且 CF-Cache-Status 为 BYPASS。XML 请求耗时约 1.89–2.49 秒。
- 本机线路另一次公网请求收到正确的新响应头，但十五秒仅下载 164114 字节后超时，明确保留为失败。该线路结果不代表用户 Windows 电脑，也不因服务端检查通过而忽略。
- 当前 XML 仍为一百条完整图文；每日每类最多十条的发布规则保持有效。

最终实机验收：2026-11-04，用户在保留原订阅的情况下执行发送/接收，反馈“原订阅恢复正常”。该反馈与上述服务端验证共同确认本次恢复；尚未进行跨日长期稳定性验证。缓存、压缩和条件响应同时调整，因此不将恢复单独归因于某一项。

## 证据与回退

本地：`out/rss-plain-response-20261104/`。执行主机：`~/workspace/news-personal/validation/rss-plain-response-20261104/`。

备份配置 SHA-256：`4881496001f6a759bc21c476e2a70f417e53ea04cda3677c82451ab0a493ac79`。调整后：`2c2d67bc55bea5adf23804aa0464a33619dfbe0ea51ed99cb3c9c554a5aaed2a`。

回退时仅移除新增的正式 Outlook 精确 location，经 `nginx -t` 后 reload。只有当前文件仍匹配调整后哈希时才直接恢复 `news-rss.conf.before`，否则按差异移除，避免覆盖他人修改。保留已有诊断 include、成功添加的完整版别名和所有新闻快照。正式地址会恢复此前的五分钟缓存和压缩行为。
