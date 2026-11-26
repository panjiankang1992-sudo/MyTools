# Outlook RSS 实机分层诊断

日期：2026-11-04，Asia/Shanghai。

用户确认在 RSS、文章和图片的 BIC 兼容调整后，Outlook 仍报同一错误。此前八项公网检查只证明服务可以下载，不构成实机故障解决证据。原订阅和新闻内容保持不变，本轮增加两个临时、只读的诊断入口，用实际下载记录区分问题位置。

## 诊断地址

| 地址 | 内容 | 目的 |
| --- | --- | --- |
| `https://toolbox.yuyutian.top/news/rss/outlook-check.xml` | 669 字节，一条 ASCII 纯文本诊断条目 | 检查最小 RSS 下载与导入 |
| `https://toolbox.yuyutian.top/news/rss/outlook-full-check.xml` | 与原 `outlook.xml` 逐字节相同，当前 232,147 字节、100 条图文 | 检查完整内容在新 URL 和简单 HTTP 传输下的结果 |

两条路径都是独立的精确 Nginx location，只允许 GET/HEAD。设为 `application/rss+xml; charset=utf-8`、固定 Content-Length、关闭动态 gzip、关闭 ETag/If-Modified-Since 条件响应，设置 `Cache-Control: no-store, no-transform`。因此诊断结果不仅比较新旧订阅状态，还包含缓存、压缩和 HTTP 传输差异，不应仅凭一个结果就断言根因。

完整版通过 alias 指向 `/var/lib/mytools-news-public/current/rss/outlook.xml`，后续发布会自然更新，不复制或修改正式文件。简版位于 `/var/lib/mytools-news-diagnostics/20261104/minimal.xml`，仅含诊断消息，不伪装成新闻。

既有 Cloudflare BIC 兼容规则 `1bc68ac204634b58a132381bf94a65c3` 的 XML 分支改为三个精确 URL 的集合：原 `outlook.xml` 和上述两个诊断地址。只读方法、空查询参数、其余新闻资源范围及只跳过 BIC 的操作保持不变。缓存规则没有扩展到诊断 URL，公网实测为 DYNAMIC、不缓存。

## 部署与验证

- 已按当前 SSH 配置验证连接，直接部署。
- 片段：`/etc/nginx/snippets/mytools-news-rss-diagnostics.conf`。
- 在既有 `/etc/nginx/snippets/mytools-news-rss.conf` 末尾追加该片段的 include，`nginx -t` 通过并 reload 成功。
- 原片段 SHA-256：`00c308668c4600f9b8c9518768c4421acac1217087d9eb9b0cf15b82deac8002`。
- 执行主机备份与验证材料：`~/workspace/news-personal/validation/rss-client-check-20261104/`。
- 本地材料：`out/rss-outlook-client-check-20261104/`。
- 源站两条 HEAD 均为 200，Content-Length 分别为 669 和 232,147，MIME 与缓存头正确。
- 执行服务器经公网使用 Windows RSS 标识实际 GET：简版 200、0.89 秒；完整版 200、1.57 秒。两者都完整解析，完整版 SHA-256 与正式源一致：`e51b1ae0f573dc0aaf3dd14e1f45eaab7eea1e3195110892fdfb62936b957e3d`。
- 本机线路：简版成功；完整版收到 HTTP 200 后于 30 秒超时，仅下载 226,048 字节，XML 截断。这是本机线路的真实失败记录，不能把它当成用户所在 Windows 电脑的实测。

## 客户端结果及日志关联

用户已确认两个诊断源均能添加成功并显示文章。14:09:03、14:09:06 的简版请求返回 200/669 字节；14:09:23、14:09:26 的完整版请求返回 200/224886 字节。User-Agent 为 Windows 经典 Outlook `16.0.10417`，与此前附带 `MyTools-Check/1.0` 的服务侧探测区分。完整版大小因同日 Top10 发布从 232147 变为 224886 字节，仍与正式源相同。

诊断访问单独写到 `/var/log/nginx/mytools-news-rss-check.log`，不缓存使对应请求可以到达源站记录；边缘或建连阶段的失败仍可能不到源站。日志沿用 combined 格式，排查输出应只取时间、请求路径、状态、响应字节数、User-Agent，不输出客户端 IP、Cookie、凭据或生产环境内容。已执行的服务侧公网探测标识附加 `MyTools-Check/1.0`，用于区分测试和真实客户端。

源站日志的完整响应字节数只表示发送给前置代理的结果，不保证 Windows 客户端收到全部内容；本次本机超时请求在源站同样记录了 232,147 字节。必须结合客户端结果判断，不能重复把源站 200 当成端到端成功。现已取得真实 Outlook 请求及用户成功显示文章的确认；这证明当前完整版内容可导入，但不能单独证明是缓存、压缩或既有订阅状态中的哪一项导致原订阅失败。

| 观察 | 后续方向（需与日志结合） |
| --- | --- |
| 两个都成功 | 对比原 URL 的缓存、压缩、响应头与既有订阅状态 |
| 仅简版成功 | 检查完整版是否下载完整，再检查 HTML、体积及导入失败 |
| 两个都失败 | 看是否到达源站、是否获得完整 669 字节，再检查客户端连接或导入环境 |
| 仅完整版成功 | 核对简版的实际错误、下载与导入记录，不能归为网络故障 |

已将原订阅调整为同样的无缓存、无压缩、完整 200 响应策略，见[原地址响应调整](2026-11-04-outlook-rss-plain-response.md)。用户随后执行发送/接收并确认“原订阅恢复正常”，本次更新验收通过。继续使用原地址，成功添加的完整版也保留可用；未修改或清空用户的 Outlook 数据文件、阅读状态或现有订阅。

## 回退

两个地址已被用户添加，不能自动移除完整版路径。仅在确认不再使用这些诊断订阅后，才可从主新闻 Nginx 片段中移除这一个诊断 include，执行 `nginx -t` 后 reload；源站路径会恢复为 404。再从 BIC 规则 XML URL 集合中移除两个诊断地址。不会影响正式 RSS。备份可用于对照，但不要覆盖之后其他人新增的配置。确认诊断结束后再清理专用文件、日志与用户测试订阅，不自动删除用户数据。
