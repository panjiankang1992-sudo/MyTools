# 手动七天新闻运行说明

## 使用

在更新后的 MyTools 中打开「工具 → 每日新闻」，点击「获取本周要点」。窗口由服务器受理时固定为最近 168 小时。退出 App 不会停止任务；重新进入会恢复同一任务。任务完成后可按分类阅读摘要、事实要点、来源和原图，并在「历史记录」查看旧报告。

每类最多 20 个事件，每条最多展示 32 个来源链接，完整合并证据保留在任务材料中。没有可靠日期、正文或来源的材料不进入报告，数量不足时保留实际数量。数据范围是已经接入的来源和最近七天存档，不保证覆盖全网。没有原图的新闻采用文字布局，不生成替代封面。

失败时使用「重试失败分类」，沿用原窗口、任务身份和已完成结果；不要反复点「获取本周要点」来修复旧报告。同一账号同时只运行一个任务。任务运行期间、受理后五分钟内，以及完成后五分钟内，新建请求都会复用原任务或报告；复用不会延长窗口。相同幂等键始终指向原任务。

## 生产构成

| 组件 | 位置或身份 |
| --- | --- |
| 认证入口 | 现有 App Gateway 的 `/api/app/v1/news/weekly-*` |
| 新闻服务 | `mytools-news-weekly-service`，仅监听 `127.0.0.1:23350` |
| 新闻执行器 | `mytools-news-weekly-executor`，节点 `weekly-news-1`，DSH 所属用户运行 |
| RSS 同步 | `mytools-news-weekly-rss`，每 30 秒检查指定自用账号已验收的报告 |
| 手动任务定义 | `news_generate_weekly`，IMMEDIATE，无 cron |
| 不可变工作包 | `news_generate_weekly/1.0.0` |
| 系统发布 | `/opt/yuyutian/mytools/releases/weekly-news-20261122-v2` |
| 分析运行包 | `~/workspace/news-personal/weekly-releases/news-runtime-1.6.3` |
| 材料和恢复点 | `~/workspace/news-personal/weekly-work/work/<jobId>` |
| 私有报告与图片 | MySQL `mytools_news` 及新闻服务专属资产目录，通过认证接口读取 |
| 服务配置与发布审计 | `/opt/yuyutian/mytools/config/weekly-news`，仅 root 可读 |
| DSH 分组 | 每日新闻；标题包含受理日期、时刻、分类和阶段 |

材料与报告保留 90 天。根据用户 2026-11-22 的补充要求，手动报告完成后更新公共 RSS，不发送邮件。原有 `mytools-personal-news.timer` 保持 disabled/inactive，无下一次计划时间。

## RSS 同步与去重

- 只公开配置中指定账号的已验收报告。读取现有内部认证接口，复制字段白名单和原图；不扫描其他用户、不使用任务目录里的未提交草稿。
- 完成后通常在下一次 30 秒检查及本地转换结束后更新；App 无需重新安装。RSS 阅读器何时显示还取决于其自身刷新周期。
- `/news/rss/daily.xml` 及分类源展示最新一次手动报告，每类最多 20 篇独立新闻；原有文章链接和归档保留。空分类保持为空，不从旧日报凑数。部分成功时只发布本次成功分类。
- 原文 URL 规范化后作为稳定 GUID（去除常见跟踪参数）。相同原文更新详情时保留 GUID；同一期合并由既有 DSH 分析完成。不同来源 URL 的跨期同事件不保证都被识别为同一篇。
- `/news/rss/outlook.xml` 同步相同内容，继续保留最多 100 篇、512 KiB 的兼容限制，每篇详情完整。全部分类 Top20 请使用完整源或各分类源；不改动已经验证的 HTTP 缓存和压缩设置。
- 发布使用独立锁、不可变快照和原子指针。转换失败保持旧订阅并重试，不重新采集或调用 DSH。消费水位只在发布成功后写入；重复消费不会追加条目，旧窗口晚完成也不能覆盖新窗口。

发布脚本为 `service/news-digest-service/scripts/weekly_rss.py`。部署步骤为 `service/deploy/deploy_weekly_rss.py stage`（隔离站点验证）与 `activate`（切换新闻服务并启用消费器）。配置 `~/workspace/news-personal/weekly-rss.json` 不含令牌；令牌由 root 专属的 `weekly-news/rss.env` 注入。消费状态保存在 `~/workspace/news-personal/weekly-rss-state/state.json`。

## 排查顺序

1. 先在 App 查看任务状态和失败分类。页面刷新只读取状态，不创建新闻任务。
2. 通过当前配置的本地 SSH 通道连接 Ubuntu，先确认连通性。查看新闻服务、执行器和 RSS 同步器的健康及同一任务 ID，不输出配置文件、环境变量或租约令牌。
3. 若停在 QUEUED，检查新闻节点是否 ONLINE、任务定义是否启用，以及新闻服务到 Scheduler 的授权。不要直接修改 Scheduler 业务表。
4. 若停在 COLLECTING，检查任务目录中的 `collection.log` 错误摘要及临时原图数量。采集需要逐源补正文，材料尚未完成时不会把临时目录当成功输入。
5. 若停在 MERGING/ANALYZING，检查同一任务目录的 `report/progress.json` 和受限分析响应。输出不完整不会当作成功报告；遗漏的聚类编号保守补为独立候选，未知或重复编号仍拒绝。
6. 服务或网络恢复后，从 App 显式重试。新代次可以使用已校验的新运行包；同代次恢复不能悄悄换版本。窗口、配置和材料继续冻结。
7. 若 App 报告已完成但 RSS 未更新，检查 `mytools-news-weekly-rss` 日志中的稳定错误码及消费水位；不要重复创建采集任务来补发 RSS。服务恢复后会继续消费。

`NEWS_034` 表示执行未完成，具体原因在对应任务的采集/分析日志。日志可能含新闻材料，只提取需要的错误码与数量，不把完整日志发送到其他服务。

## 升级与回退

- 工作包和分析运行包使用新目录发布并校验清单，不能覆盖已经发布的版本。
- 分析包升级只需在新闻节点空闲时切换新闻 Executor，其他服务无需重启。此轮操作与回退脚本见 `service/deploy/activate_weekly_news_runtime.py`。
- 需要暂停新请求时，应先让已有任务结束，再通过专用服务配置关闭 `NEWS_WEEKLY_ENABLED` 并重启新闻服务；认证查询路由保留，既有报告不删除。
- 系统首次发布的完整回退脚本为 `service/deploy/deploy_weekly_news.py rollback`。它会禁用手动定义、停止新服务并还原旧 Gateway/Scheduler；数据库和报告保留，但旧网关无法显示新增新闻页。恢复本次发布后可重新读取。
- 仅回退本次 RSS 改动时，停用 `mytools-news-weekly-rss`，按 `weekly-rss-deployment.json` 审计移除本次新闻服务 drop-in，再重启新闻服务。保留已发布的完整快照和历史链接。
- 回退绝不能重新启用每日 timer、旧日报扫描器或邮件。不得删除 DSH 历史来清理失败任务。

当前验证状态以 `docs/design/2026-11-22-weekly-news/IMPLEMENTATION.md` 最新章节为准，任务受理和节点在线不等于最终报告已通过验收。
