# 手动七天新闻开发验证

状态：后端手动服务已部署，真实 App 端到端验收通过；报告 SUCCEEDED，共 163 条详情、82 张来源原图。完整进度见 [实施进度](../../design/2026-11-22-weekly-news/IMPLEMENTATION.md)。

## 生产手动链路

- Gateway、Scheduler 增量发布保留原有其他业务；新增独立新闻服务和 DSH 用户所属的新闻 Executor。发布目录 `weekly-news-20261122-v2`，当前分析包 `news-runtime-1.6.3`，工作包 `news_generate_weekly/1.0.0` 不可变。
- 真实已登录 App 在「工具 → 每日新闻」受理任务 `7d314656-3c23-484e-b1e7-4bec35d48251`；退出 App 后服务器继续采集，重新进入恢复同一任务。
- 首轮去重遗漏四个编号，严格验证拒绝发布。修复保守保留遗漏项后，从 App 重试到 attempt=2，复用原材料和原窗口，未新增历史任务。第二次保存发现 42 个来源链接超出阅读上限，修复后 attempt=3 成功；第三次复用 18 份分析结果，没有再调用模型。
- 能力接口返回手动开启、自动关闭、168 小时、Top20 和十个分类；匿名 Gateway 请求返回 401。
- 真实 DSH 会话的标题与 registry 均已只读核查，例如 `2026-11-22 16:38 技术 Top20`、`2026-11-22 16:38 国内 Top20`，均在「每日新闻」分组。
- 正常入口 HAP 已构建并在模拟器验证真实 API；实际用户手机尚未安装验证。开发签名 HAP 为 `out/news-weekly-dev-20261122/mytools-weekly-news-final.hap`。

## 已验证

- 新闻服务 Java 328 项：0 failures/errors，30 项外部环境测试跳过。JDK 21，`-Xlint:all -Werror`。
- Gateway 新闻路由 14 项，Scheduler 新闻授权/迁移 7 项，均通过。
- Python 新闻组合 46 项、周报/缓存专项 9 项、新执行包 6 项通过。全仓库 166 个执行包装配校验通过。
- 后续恢复专项共 28 项通过，覆盖模型漏编号、非法聚类仍拒绝、显式新代次运行包切换、来源失败定向重采、保留成功分类、丢失发布回执恢复和紧凑精选 30 → Top20 详情、GitHub 数字活跃时间和合并来源链接上限。
- Scheduler 新增部署授权、审计幂等及 V185 不依赖旧新闻种子的检查通过；发布时只允许修复唯一失败迁移记录，其余历史校验和逐项核对未变。
- 新 App 响应边界测试通过，正常入口和临时验收入口的 HAP 都构建成功。既有下载页剪贴板权限编译提示仍在，新闻功能没有新增告警或权限。
- 模拟器以真实 `WeeklyNewsPage` 和离线 API 适配器验证首次空态、点击受理、轮询、部分完成、分类列表、详情、来源链接显示、返回、历史和动态标题。离线适配器不调用生产 API、不发布假新闻；生产受理另由正常 App 入口完成。
- 页面在 320 vp 宽度下可用。已修复标题单字换行，结果页收起介绍区；标题和空态文案切换响应正确。
- 深色模式和 1.3 倍应用字号另用临时离线包验证，未改变系统字号设置；构建脚本退出恢复生产源码，验收后恢复正常入口 HAP。

截图（均为明确标注的离线验收数据）：

- [首次页面](../../../output/weekly-news-native/empty.jpeg)
- [分类结果](../../../output/weekly-news-native/results.jpeg)
- [单篇详情](../../../output/weekly-news-native/detail.jpeg)
- [历史记录](../../../output/weekly-news-native/history.jpeg)
- [深色空态](../../../output/weekly-news-native/dark-empty.jpeg)
- [大字号空态](../../../output/weekly-news-native/large-font-empty.jpeg)

真实生产截图：[后台进度](../../../output/weekly-news-native/live-progress.jpeg)、[结果](../../../output/weekly-news-native/live-results.jpeg)、[原图列表](../../../output/weekly-news-native/live-list.jpeg)、[详情](../../../output/weekly-news-native/live-detail.jpeg)、[历史](../../../output/weekly-news-native/live-history.jpeg)。逐条 API 验收、分类计数与访问隔离结果见 [验收数据](weekly-live-20261122.json)。

## 发布与运行约束

`out/news-weekly-dev-20261122/` 保存各次候选及最终产物。当前生效的是 `runtime-1.6.3`、`packages-1.0.0-final`、`gateway-delta-complete.zip` 和 `scheduler-delta-v2.zip`；较早 RC 和首版迁移仅保留审计用途。运行包 23 个文件逐项哈希校验一致。

使用本地 SSH 配置中的 `home-ubuntu` 连接并验证连通性，配置与令牌仅在远端受限进程中读取，未回显。Scheduler V185 经 Flyway 正式应用；手动定义通过带审计的受限接口启用。专用 Executor 运行在 DSH 所属用户下，未放宽 grouping socket 或其他系统服务目录权限。

每日新闻 timer 多次核对均 disabled/inactive，无下一次计划时间。手动链路不发送邮件、不更新 RSS；未来跨过次日 07:30 的持续观察尚未发生，不能写成已实测。操作与回退见 [运行说明](../../operations/news-weekly.md)。
