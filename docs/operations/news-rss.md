# 新闻 RSS 运行说明

## 当前状态：已停止每日自动更新

2026-11-22 按用户要求，经当前本地 SSH 配置连接执行服务器，执行 `systemctl --user disable --now mytools-personal-news.timer` 并停止 `mytools-personal-news.service`。复核 timer 为 `disabled/inactive`、无下次触发时间，service 为 `inactive`；用户 crontab 中未发现 news 任务。既有 RSS 和网页文件继续保留，邮件仍关闭。

现已转为 MyTools 工具页「每日新闻」手动触发：点击「获取本周要点」后，获取点击时刻向前七天各分类最多 20 条详情新闻。按同日补充要求，指定自用账号的已完成报告自动同步到下列原有 RSS 地址；独立同步器每 30 秒检查一次，失败重试不会重新采集。完整源和分类源展示最新手动报告，Outlook 源继续保持最多 100 篇、512 KiB。详见[手动周报运行说明](news-weekly.md)。不要在普通部署、故障恢复或回滚中重新启用每日 timer。

下文描述既有 RSS 格式与历史部署；其中每日 07:30 运行、每日发布及未来触发时间均为停用前记录，不代表当前计划。

2026-11-14 运行包 `1.5.3`：**每条新闻一个 RSS 条目，每条都附详情**，标题格式统一为 `【国际】【2026/11/02】新闻标题`。日期为所属日报日期，分类日报继续作为网页归档保留。

## 订阅地址

- [订阅说明与新闻历史](https://toolbox.yuyutian.top/news/subscribe/)
- [全部新闻](https://toolbox.yuyutian.top/news/rss/daily.xml)
- [Outlook 兼容入口](https://toolbox.yuyutian.top/news/rss/outlook.xml)
- [技术](https://toolbox.yuyutian.top/news/rss/tech.xml)
- [国际](https://toolbox.yuyutian.top/news/rss/world.xml)
- [国内](https://toolbox.yuyutian.top/news/rss/domestic.xml)
- [江苏本地](https://toolbox.yuyutian.top/news/rss/local/cn-js.xml)
- [流行](https://toolbox.yuyutian.top/news/rss/trending.xml)
- [军事](https://toolbox.yuyutian.top/news/rss/military.xml)
- [财经](https://toolbox.yuyutian.top/news/rss/finance.xml)
- [社会](https://toolbox.yuyutian.top/news/rss/society.xml)
- [体育](https://toolbox.yuyutian.top/news/rss/sports.xml)
- [娱乐](https://toolbox.yuyutian.top/news/rss/entertainment.xml)
- [分类 OPML](https://toolbox.yuyutian.top/news/rss/categories.opml)

将 XML 地址添加到阅读器即可。全部新闻与分类源二选一；OPML 只包含分类源，避免重复导入。每条新闻有标题、摘要、事实要点、来源链接及必要的材料限制，优先展示新闻原图、图注和来源；前十名缺少可用原图时才生成要点图解，并明确标注非现场照片。每日每类仅保留 Top10，每条保留完整图文。只有标题或无实质正文的条目暂不发布，不把占位提示当详情。

流行指网络热点与流行趋势，目前接入百度公开热榜，热度仅代表该平台，不声称全网统计。军事接入央视军事，财经、社会、体育、娱乐接入中新网对应栏目。每类最多 30 个候选，跨分类去重后最多发布 10 条，不为填满栏目重复新闻。旧四分类材料仍可恢复。标题前缀不改变文章 GUID，重复抓取更新既有条目而不是额外创建副本。

Feed 保留按报告时区计算的最近 7 天，网页保留 90 日。相同原文 URL 跨日入榜只保留一条；改标题、名次和详情不会产生新的 GUID。不同网址是否为同一事件，仍由每日 DSH 聚类处理，不承诺任意跨日转载均自动归并。旧阅读器中已下载的分类日报不会被强制删除，刷新后新增内容是逐条新闻。

经典版 Outlook 遇到「链接可能未指向有效的 RSS 源」时，可使用 `outlook.xml`：从一周窗口中提供最新最多 100 条、未压缩文件不超过 512 KiB，完整 HTML 图文放在 RSS 2.0 标准 `description` 中。达到大小预算时移除最旧条目，单篇详情不截断；GUID、文章链接、发布时间与完整源一致。此预算是本服务的兼容策略，不是微软公布的 Outlook 硬性限制。初次订阅只导入该窗口，较旧内容仍可在 `daily.xml` 或网页查看。兼容源与完整源二选一，分类 OPML 不额外加入兼容源。

2026-11-03 为 `outlook.xml` 增加 Cloudflare 精确 URL 的 GET/HEAD 缓存规则，沿用源站五分钟有效期，以改善更新时公网下载截断。其他地址及安全策略不变。实测与回退见[传输排查记录](../verification/daily-news/2026-11-03-outlook-rss-transport.md)。

2026-11-04 发现 Windows RSS 标识被浏览器完整性检查拦截（403/1010），已为原订阅 XML、公开文章修订页与新闻图片的 GET/HEAD 配置仅跳过该项检查、保留请求日志的兼容规则。同时显式设置该 XML 的 Browser TTL 尊重源站五分钟缓存头，修正原来客户端收到四小时缓存头的差异。仅补齐图文资源时用户仍报 `0x80004005`；后续完整响应调整后已恢复，分阶段证据见[客户端复发排查](../verification/daily-news/2026-11-04-outlook-rss-client.md)和[临时诊断源](../verification/daily-news/2026-11-04-outlook-rss-isolation.md)。后续发布使用 `python3 service/news-digest-service/scripts/check_public_rss.py --plain-response` 验证两种客户端、压缩、条件请求和同站图文资源，不能只验证普通浏览器下载。

用户随后确认简版与完整图文诊断源均可添加并显示内容。原 `outlook.xml` 已改用相同的 `no-store, no-transform`、无 gzip/ETag、忽略 If-Modified-Since 的完整响应，用户随后保留原订阅执行发送/接收，确认“原订阅恢复正常”，本次更新验收通过。继续使用原 `outlook.xml`，保留该兼容配置。成功添加的 `outlook-full-check.xml` 继续跟随每日发布，保留诊断 include 以免后续部署使它失效；见[响应调整记录](../verification/daily-news/2026-11-04-outlook-rss-plain-response.md)。

除上述 Outlook 路径外，其他 RSS 和 OPML 在 Nginx 层按客户端 `Accept-Encoding` 协商 gzip，带 `Vary: Accept-Encoding`，不支持压缩时仍提供完整 XML。公网传输成功不能替代真实 Outlook 添加订阅验收。新版 Outlook 不支持 RSS，参见[微软说明](https://support.microsoft.com/en-us/outlook/subscribe-to-an-rss-feed)。

停用前每日任务于北京时间 07:30 启动，跳过邮件阶段，采集、去重分析后为 RSS 补详情并发布。十分类运行的采集预算为 40 分钟，任务总预算为 120 分钟；RSS 详情补充使用独立的 900 秒预算，失败可复用缓存恢复。江苏源按执行服务的地区生成，与阅读者所在地无关。

2026-11-02 按用户要求移除全部两个收件人，生产个人配置已设为 `recipients: []`、`mailEnabled: false`。每日 07:30 的采集、分析和 RSS 发布继续启用，邮件阶段跳过；既有投递历史保留。配置修改当日若手动重跑 `run`，冻结配置校验可能拒绝执行，补 RSS 应使用 `publish-only`；次日按新配置正常运行。

## 配置与目录

2026-11-14 起，新产生的新闻 DSH 会话统一登记到“每日新闻”工作区（`~/workspace/news-personal/dsh/workspace`）。标题使用报告日期和分类，例如 `2026-11-14 技术 Top10`、`2026-11-14 全分类 跨分类去重`、`2026-11-14 国际 详情补全 1`；结构修复重试追加 `· 修复2` 等次数，历史补跑按原报告时区和日期命名。分析缓存仍保存在每日报告目录。此前生成的历史会话未迁移。

实现使用新闻专属 `dsh-news-session.patch.yml`：在 `agent/created` 阶段通过 DSH 标题服务固定标题、持久化会话，再通过权限为 `0600` 的本机 Unix socket 请求 DSH Web 进程登记分组。Web profile 的 `news-workspace-registration` 插件加载 `runtimes/1.5.3/scripts/dsh_news_workspace.mjs`，由已有工作区服务核验会话 cwd 并写入分组，避免多个 headless 进程直接改写 Web 的工作区缓存。启动前先探测登记服务，登记不可用则明确失败，不降级到未分组分析。因此新闻任务需要 `dsh.service` 正常运行；可用 `test -S ~/workspace/news-personal/dsh/group.sock` 检查入口是否存在。

上线时通过 Web profile 热加载插件，DSH Web 进程未重启。版本 `1.5.3` 保留原采集器及分析规则，只调整会话元数据和运行目录。回退资料位于 `~/workspace/news-personal/validation/dsh-group-20261114/`：先恢复 `personal-news.service.before` 到用户服务并 `systemctl --user daemon-reload`，再从 Web profile 移除 `news-workspace-registration` 插件行。历史会话及“每日新闻”分组保留，RSS 发布快照无需回退。

验收通过 18 项报告测试、3 项详情测试及 Node 套接字登记测试，覆盖历史日期、重试命名、归组失败阻止模型启动。生产完成一次短任务及一次当日技术材料分析（3 个候选），核对压缩会话日志中的固定标题和 `completed` 终态，以及工作区成员记录。真实分类会话标题为 `2026-11-14 技术 Top10`。运行包 19 个文件哈希一致，定时任务下一次启动为北京时间 2026-11-15 07:30，RSS 发布后校验 drop-in 继续生效。生产元数据验收记录为上述目录的 `acceptance.json`。

2026-11-05 原订阅再次报告 `0x80004005`。当天 07:41 发布完成，兼容源含当日 86 条及前一日 14 条。源站在北京时间 16:39:44 记录到 Windows Edge 获取全文，在 16:44:46 记录到 Outlook 16.0.10417 获取全文，两次均返回 HTTP 200、244593 字节。用户确认后一次发送/接收已收到当日文章。此次报错时刻尚未匹配到原订阅的请求，Cloudflare 按路径查看的采样事件中也未找到对应拦截记录。根因仍待定位。跨日验收看原订阅连续两期自动出现带新日期的新闻。

每次个人日报服务成功结束后，`20-outlook-edition-audit.conf` 调用 `~/workspace/news-personal/rss-audit/1.0.0/audit_outlook_edition.py`。它按 Outlook 客户端特征从公网实际下载一次订阅，核对本期日期、完整图文订阅文件的 SHA-256 和 HTTP 响应。查看记录可用 `journalctl --user -u mytools-personal-news.service -g outlook_edition_audit -o cat`。检查失败单独记入日志，日报任务保留自己的退出状态。首轮同环境基线检查通过，测试单元为 `mytools-outlook-edition-baseline-20261105.service`。诊断跨日问题时，把基线与 `/var/log/nginx/mytools-news-rss-outlook.log` 中同一分钟的 Outlook 请求及客户端错误详情对应起来。回退该检查时移除用户服务同名 drop-in 并执行 `systemctl --user daemon-reload`。

2026-11-12 用户反馈 11 月 11 日和 12 日再次报错，尚未提供本次错误行的任务名称、订阅地址或分钟级时间。11 月 6 日至 12 日的七次发布后公网核验全部通过。12 日任务于 07:42:54 成功结束，当前兼容源为 232255 字节、100 条，其中当日 90 条，SHA-256 为 `dd524a1a5108d81db602a76a2a6da693c9899fc577315c26da32435782bbbdbc`。11:33 服务器公网复测完整下载耗时 2.28 秒，本机复测耗时 24.57 秒，正文哈希一致。两处请求均返回 HTTP 200、`no-store, no-transform`、Cloudflare `BYPASS`。

检查保留的 Nginx 当前及轮转日志，11 月 6 日至 12 日未找到真实 Outlook／Windows RSS 客户端的新闻请求，原订阅最后一条真实 Outlook 请求仍为 11 月 5 日 16:44:46。确认 Nginx 正在写入正确的专用日志。Cloudflare 按原订阅路径查看过去 72 小时的安全事件，仅显示正常巡检对应的跳过记录，未发现可关联的失败事件；采样日志缺失不能排除边缘故障，服务器巡检成功也不能证明用户电脑的连接或导入正常。本次未更改订阅内容、响应配置或安全规则。下一步取得 Outlook“发送/接收进度 → 错误”的任务行截图，确认实际任务和地址，再关联对应时间的请求，避免将邮箱同步错误或诊断订阅错误误归于原 RSS。

个人配置的 `rssConfig` 指向独立的 `rss-publishing.json`：

```json
{
  "enabled": true,
  "baseUrl": "https://toolbox.yuyutian.top/news",
  "directory": "/var/lib/mytools-news-public",
  "feedFormat": "articles",
  "feedDays": 7,
  "archiveDays": 90,
  "maxFeedBytes": 8388608
}
```

`feedFormat` 默认为 `articles`；`editions` 仅用于兼容原分类日报模式。逐条模式超出体积预算时明确失败、保留上次站点，不静默删掉一周内的新闻或省略详情。`mailEnabled: false` 支持 `recipients: []`，无需创建邮件任务。RSS 需显式 `--publish`；生产 unit 使用 `run --send --publish`。

`/var/lib/mytools-news-public/current` 指向 `releases/<id>/site`。Nginx 仅允许 RSS、OPML、文章、归档网页和 JPEG/PNG/GIF。`manifest.json` 位于 site 外，包含每日归档与独立文章索引；个人配置、抓取证据、模型响应和邮件回执均不公开。

文章页面：`articles/<canonical-url-sha256>/r1.html`；`index.html` 指向该文章最新内容。网页修订路径不可变。发布器整体切换站点，保留最近三个快照。

`--region-input` 指向原始采集包的 `report-input.json`，同时用于解析相邻 `images/` 中的新闻原图；`personal_daily.py` 会自动传入。原图读取仅限该目录，拒绝越界路径、符号链接、来源不匹配和损坏图片。

`NEWS_CONTENT_PYTHON` 指向已安装 Pillow 的正文环境，SMTP 继续使用原解释器。运行包含 16 个受 SHA-256 清单保护的文件，Java 采集器与脚本按同一版本校验。

## 详情补充与恢复

`skills/analyze-rss-articles/SKILL.md` 由 DSH 无头执行：一次最多 10 条已合并新闻，逐条返回摘要和要点，不能继续合并或改变输入身份。已有合格详情直接复用；仅为前十名中详情不足的条目补充分析，旧报告的后十条不再触发补充。材料不足返回 `insufficient`，暂不进入 Feed。

每个日期的 `rss-articles/` 保存私有副本 `report.json`、详情缓存、模型响应与 `summary.json`。缓存按技能和输入材料哈希隔离；冻结日报和邮件任务不修改。单次补充总预算 900 秒，每批最多 300 秒，进程锁防止重入。

先按当前本机 SSH 配置验证连接，以下命令在执行主机运行：

```bash
NEWS_CONTENT_PYTHON="$HOME/workspace/news-personal/venvs/content-1.2.0/bin/python" \
PYTHONDONTWRITEBYTECODE=1 \
python3 "$HOME/workspace/news-personal/runtimes/1.5.3/scripts/personal_daily.py" \
  "$HOME/workspace/news-personal/personal.json" publish-only --date 2026-11-01
```

日期替换为需要重试的已存报告。此命令不重新采集、不发送邮件；有缺失详情缓存时会调用 DSH。全部缓存齐全时只校验、渲染和发布。相同内容返回 `unchanged`。

`rss-summary.json` 记录发布结果。详情分析失败时保留原站点，重跑复用已完成的批次。分类导出失败可发布其他分类，归档总览注明缺失。RSS 发布失败不会撤销已提交邮件。不要为补 RSS 重跑旧日期的整条邮件任务。

## 回退和验收

1.5.2 将分析、校验、图文报告和 RSS 统一为每日每类最多十条，候选池仍为约三十条。最近一周 RSS 按日期和类别限额，旧文章链接继续保留；阅读器已经下载的旧条目不会因 Feed 缩小而自动删除。部署和回退见[Top10 验证](../verification/daily-news/2026-11-04-news-top10.md)。

1.5.1 的分类、日期前缀、阅读器容量和恢复记录见[十分类验证](../verification/daily-news/2026-11-02-rss-categories.md)。部署备份位于 `~/workspace/news-personal/validation/rss-categories-20261102/`。

1.4.2 的 Outlook 兼容部署备份位于 `~/workspace/news-personal/validation/rss-outlook-20261102/`，含原 unit、Nginx 片段和完整公开快照。真实经典版 Outlook 已添加成功，详见[兼容入口验证](../verification/daily-news/2026-11-02-outlook-rss.md)。

1.4.1 部署备份位于执行主机 `~/workspace/news-personal/validation/rss-source-images-20261101/`，含原 unit、RSS 配置、Nginx 片段和切换前公开快照。内部配置备份保持私有。原 `1.2.0`、`1.3.0`、`1.4.0` 运行包保持不变。

回退时恢复原 unit 与 RSS 配置、执行 daemon-reload，再原子切换 current 到备份快照；Nginx 片段恢复后先 `nginx -t` 再 reload。禁用后续发布可关闭 RSS 配置或移除 unit 的 `--publish`，已有公开网页仍可读。

原图优先调整见[原图发布验证](../verification/daily-news/2026-11-01-rss-source-images.md)；逐条文章验收见[逐条文章验证](../verification/daily-news/2026-11-01-rss-articles.md)；此前分类日报版本见[1.3.0 验证](../verification/daily-news/2026-11-01-rss-publishing.md)。
