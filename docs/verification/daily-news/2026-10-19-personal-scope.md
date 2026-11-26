# 自用范围收敛与真实来源检查

用户于 2026-10-19 明确要求降低复杂度，优先个人消息收集、汇总和发送。新版实施依据为[自用版范围](../../design/2026-10-19-daily-news-personal-mvp.md)。本轮停止尚未开始编码的 News 派生期限传播，没有新增数据库迁移或新的治理模块。

本轮使用现有 Java 21 采集器直接访问公开来源，命令：

```bash
mvn -f service/news-digest-service/pom.xml test \
  -Dtest=LiveFeedSmokeTest,LiveTechnicalSourcesTest \
  -Dnews.liveProbe=true -Dnews.liveTechProbe=true
```

4 项通过，无失败或跳过：

| 来源 | 实际验证 |
| --- | --- |
| 中国新闻网国际 RSS | 生产 HTTP 传输器取得 HTTP 200，RSS 解析出非空文章列表 |
| GitHub | 公开仓库搜索解析 3 项汇总元数据，失败数 0 |
| Hacker News | 官方列表与条目接口访问 3 项、接收 3 项，失败数 0 |
| DEV | 官方周列表访问 3 项、接收 3 项，失败数 0 |

这是本机来源检查，未写入业务数据库或发送消息；不代表实际部署节点可访问、每类已足量达到 20/10，或 GitHub 已取得连续七日增量。下一步直接围绕真实日报预览和个人邮件接收推进，不增加与自用主链路无关的前置验收。

本轮核对目标工具状态为 ACTIVE。整体尚未完成，实际收件与每日定时运行仍待验证。

## 真实预览与两封测试邮件

复用现有采集器，在一次性本地 H2 中采集 GitHub、Hacker News、DEV、中新网国际及国内 RSS，五个来源各接收 30 条。生成技术、国际、国内各 20 条标题，共 60 条，带实际原文链接。此次来源配置为 `METADATA_ONLY`，详情数为 0；本地地区未解析，栏目为空。邮件正文、附件和本记录均明确这些缺口，不作为完整 20/10 日报验收。

邮件渲染改为中文栏目、可读时间、来源链接及缺口说明，移除面向收件人的内部标识和哈希。测试预览工具位于 `src/test`，不会连接生产数据库或自动发送消息。

用户明确提供两个测试邮箱后，使用 `ubuntu-mytools-mail` 技能，经当前 SSH 配置直连 Ubuntu，核对运行中的 Messaging 服务，复用其 SMTP 账号。两个独立任务均先校验正文与 HTML 附件，再各发送一次；两份回执均为 `accepted`，SMTP 状态码为 `250`。未改动或重启生产服务。SMTP 接受不能证明收件箱到达，本次也未通过生产 News Outbox 或 Scheduler 执行，尚未启用每日发送。

预览、收件地址和邮件任务文件保存在已忽略的 `out/mail-jobs/news-preview-20261019-ff62b52dc4/`，未将收件配置或生产凭据写入跟踪文件。可复核汇总见 `news-personal-mail-check-20261019.json`。

验证命令使用 Java 21：

```bash
mvn -f service/news-digest-service/pom.xml test \
  -Dtest=NewsDeliveryRendererTest,DispatchIntegrationTest,DispatchRestartPersistenceTest
mvn -f service/news-digest-service/pom.xml package -DskipTests
```

19 项测试通过，无失败或跳过，打包成功。后续只推进有来源的详情、本地来源及每日发送主链路，不增加治理模块。
